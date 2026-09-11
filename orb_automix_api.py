"""Remote musical intelligence for Orb Automix.

Mount this router into the existing Orb FastAPI service. The Android client uploads only its
standalone lightweight analysis rendition (normally YouTube Opus), never the Lossless/Hi-Res
playback file. Audio is deleted immediately after feature extraction; only derived metadata is
kept in a bounded in-memory cache.
"""
from __future__ import annotations

import asyncio
import math
import os
import tempfile
import threading
from collections import OrderedDict
from typing import Any

import av
import numpy as np
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

router = APIRouter(prefix="/api/automix", tags=["automix"])

API_VERSION = 1
SAMPLE_RATE = int(os.getenv("AUTOMIX_SAMPLE_RATE", "22050"))
MAX_UPLOAD_BYTES = int(os.getenv("AUTOMIX_MAX_UPLOAD_BYTES", str(24 * 1024 * 1024)))
MAX_DURATION_SECONDS = float(os.getenv("AUTOMIX_MAX_DURATION_SECONDS", "900"))
MAX_CACHE_ENTRIES = int(os.getenv("AUTOMIX_CACHE_ENTRIES", "512"))
MAX_CONCURRENT_ANALYSES = max(1, int(os.getenv("AUTOMIX_MAX_CONCURRENT", "2")))

_analysis_slots = asyncio.Semaphore(MAX_CONCURRENT_ANALYSES)
_cache_lock = threading.Lock()
_analysis_cache: OrderedDict[str, dict[str, Any]] = OrderedDict()


class PlanRequest(BaseModel):
    version: int = API_VERSION
    outgoing: dict[str, Any]
    incoming: dict[str, Any]


def _finite(value: Any, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _clamp(value: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, value))


def _cache_get(track_id: str) -> dict[str, Any] | None:
    with _cache_lock:
        value = _analysis_cache.get(track_id)
        if value is not None:
            _analysis_cache.move_to_end(track_id)
        return value


def _cache_put(track_id: str, value: dict[str, Any]) -> None:
    with _cache_lock:
        _analysis_cache[track_id] = value
        _analysis_cache.move_to_end(track_id)
        while len(_analysis_cache) > MAX_CACHE_ENTRIES:
            _analysis_cache.popitem(last=False)


def _decode_mono(path: str) -> np.ndarray:
    """Decode any PyAV-supported input to mono float32 at SAMPLE_RATE."""
    chunks: list[np.ndarray] = []
    samples_left = int(MAX_DURATION_SECONDS * SAMPLE_RATE)
    with av.open(path) as container:
        streams = [s for s in container.streams if s.type == "audio"]
        if not streams:
            raise ValueError("no audio stream")
        stream = streams[0]
        resampler = av.AudioResampler(format="fltp", layout="mono", rate=SAMPLE_RATE)
        for frame in container.decode(stream):
            converted = resampler.resample(frame)
            if converted is None:
                continue
            frames = converted if isinstance(converted, list) else [converted]
            for out in frames:
                arr = out.to_ndarray()
                if arr.ndim == 2:
                    arr = arr[0]
                arr = np.asarray(arr, dtype=np.float32).reshape(-1)
                if not arr.size:
                    continue
                take = min(samples_left, arr.size)
                chunks.append(arr[:take])
                samples_left -= take
                if samples_left <= 0:
                    break
            if samples_left <= 0:
                break
    if not chunks:
        raise ValueError("empty decode")
    audio = np.concatenate(chunks).astype(np.float32, copy=False)
    if not np.isfinite(audio).all():
        audio = np.nan_to_num(audio, copy=False)
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak > 1.5:
        audio = audio / peak
    return audio


def _frame_signal(audio: np.ndarray, frame: int, hop: int) -> np.ndarray:
    if audio.size < frame:
        padded = np.zeros(frame, dtype=np.float32)
        padded[: audio.size] = audio
        return padded[None, :]
    count = 1 + (audio.size - frame) // hop
    shape = (count, frame)
    strides = (audio.strides[0] * hop, audio.strides[0])
    return np.lib.stride_tricks.as_strided(audio, shape=shape, strides=strides, writeable=False)


def _normalized(values: np.ndarray) -> np.ndarray:
    if not values.size:
        return values
    lo = float(np.percentile(values, 10))
    hi = float(np.percentile(values, 95))
    if hi <= lo + 1e-9:
        return np.zeros_like(values)
    return np.clip((values - lo) / (hi - lo), 0.0, 1.0)


def _spectral_band_energy(frames: np.ndarray, lo_hz: float, hi_hz: float) -> np.ndarray:
    window = np.hanning(frames.shape[1]).astype(np.float32)
    spec = np.abs(np.fft.rfft(frames * window, axis=1)) ** 2
    freqs = np.fft.rfftfreq(frames.shape[1], 1.0 / SAMPLE_RATE)
    mask = (freqs >= lo_hz) & (freqs < hi_hz)
    if not np.any(mask):
        return np.zeros(frames.shape[0], dtype=np.float32)
    return np.mean(spec[:, mask], axis=1).astype(np.float32)


def _beat_grid(onset: np.ndarray, hop_seconds: float) -> tuple[float, float, list[float]]:
    """Energy-onset autocorrelation tempo + best phase. Returns bpm, confidence, beat times."""
    if onset.size < 16 or float(np.max(onset)) <= 1e-8:
        return 0.0, 0.0, []
    signal = onset.astype(np.float64)
    signal -= float(np.mean(signal))
    min_bpm, max_bpm = 55.0, 210.0
    min_lag = max(1, int(round(60.0 / max_bpm / hop_seconds)))
    max_lag = min(signal.size - 2, int(round(60.0 / min_bpm / hop_seconds)))
    if max_lag <= min_lag:
        return 0.0, 0.0, []
    scores = np.array([
        np.dot(signal[:-lag], signal[lag:]) / max(1, signal.size - lag)
        for lag in range(min_lag, max_lag + 1)
    ])
    best_i = int(np.argmax(scores))
    lag = min_lag + best_i
    best = float(scores[best_i])
    # Normalized autocorrelation at the winning lag. Do not subtract the median of all positive
    # lags here: a clean periodic pulse train has equally strong harmonics at 2x/3x the period,
    # making that median nearly the winner and incorrectly scoring perfect rhythm as zero.
    denom = float(np.dot(signal, signal) / max(1, signal.size)) + 1e-9
    confidence = _clamp(best / denom, 0.0, 1.0)
    bpm = 60.0 / (lag * hop_seconds)

    # Fold absurd half/double choices toward typical musical tempo without hiding the raw evidence.
    while bpm < 70.0:
        bpm *= 2.0
        lag = max(1, int(round(lag / 2.0)))
    while bpm > 190.0:
        bpm /= 2.0
        lag *= 2

    phase_scores = [float(np.sum(onset[p::lag])) for p in range(min(lag, onset.size))]
    phase = int(np.argmax(phase_scores)) if phase_scores else 0
    beats = [i * hop_seconds for i in range(phase, onset.size, lag)]
    return bpm, confidence, beats


def _key_from_audio(audio: np.ndarray) -> tuple[str, float]:
    if audio.size < SAMPLE_RATE:
        return "", 0.0
    # Analyze up to ~90 s spread across the track to keep the backend bounded.
    max_samples = 90 * SAMPLE_RATE
    if audio.size > max_samples:
        idx = np.linspace(0, audio.size - 1, max_samples, dtype=np.int64)
        sample = audio[idx]
    else:
        sample = audio
    n = 1 << int(math.floor(math.log2(max(2048, min(sample.size, 1 << 20)))))
    sample = sample[:n] * np.hanning(n)
    spectrum = np.abs(np.fft.rfft(sample))
    freqs = np.fft.rfftfreq(n, 1.0 / SAMPLE_RATE)
    chroma = np.zeros(12, dtype=np.float64)
    valid = (freqs >= 55.0) & (freqs <= 5000.0)
    for freq, mag in zip(freqs[valid], spectrum[valid], strict=False):
        midi = 69.0 + 12.0 * math.log2(float(freq) / 440.0)
        chroma[int(round(midi)) % 12] += float(mag)
    total = float(np.sum(chroma))
    if total <= 1e-9:
        return "", 0.0
    chroma /= total
    major_profile = np.array([6.35,2.23,3.48,2.33,4.38,4.09,2.52,5.19,2.39,3.66,2.29,2.88])
    minor_profile = np.array([6.33,2.68,3.52,5.38,2.60,3.53,2.54,4.75,3.98,2.69,3.34,3.17])
    scores: list[tuple[float, int, str]] = []
    for tonic in range(12):
        scores.append((float(np.dot(chroma, np.roll(major_profile, tonic))), tonic, "major"))
        scores.append((float(np.dot(chroma, np.roll(minor_profile, tonic))), tonic, "minor"))
    scores.sort(reverse=True)
    best, tonic, mode = scores[0]
    second = scores[1][0]
    confidence = _clamp((best - second) / (abs(best) + 1e-9) * 8.0, 0.0, 1.0)
    names = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
    return f"{names[tonic]} {mode}", confidence


def _candidate_points(
    times: np.ndarray,
    energy: np.ndarray,
    vocal: np.ndarray,
    downbeats: list[float],
    duration: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    def at(curve: np.ndarray, t: float) -> float:
        if not curve.size or not times.size:
            return 0.5
        return float(curve[int(np.clip(np.searchsorted(times, t), 0, len(curve) - 1))])

    incoming: list[dict[str, Any]] = []
    outgoing: list[dict[str, Any]] = []
    for t in downbeats:
        e = at(energy, t)
        v = at(vocal, t)
        if 0.5 <= t <= min(duration * 0.35, 45.0):
            score = _clamp(0.50 + 0.30 * (1.0 - v) + 0.20 * e, 0.0, 1.0)
            incoming.append({"time": round(t, 4), "score": round(score, 4), "type": "downbeat"})
        if max(duration * 0.55, duration - 75.0) <= t <= duration - 0.5:
            score = _clamp(0.45 + 0.35 * (1.0 - v) + 0.20 * (1.0 - e), 0.0, 1.0)
            outgoing.append({"time": round(t, 4), "score": round(score, 4), "type": "downbeat"})
    incoming.sort(key=lambda x: x["score"], reverse=True)
    outgoing.sort(key=lambda x: x["score"], reverse=True)
    return incoming[:8], outgoing[:8]


def _analyze(path: str, track_id: str, declared_duration: float) -> dict[str, Any]:
    audio = _decode_mono(path)
    duration = min(audio.size / SAMPLE_RATE, declared_duration if declared_duration > 0 else float("inf"))
    duration = float(duration if math.isfinite(duration) else audio.size / SAMPLE_RATE)

    # 0.5 s feature grid: dense enough for structure/vocal decisions, cheap enough for JSON.
    frame = max(1024, int(0.50 * SAMPLE_RATE))
    hop = frame
    frames = _frame_signal(audio, frame, hop)
    times = np.arange(frames.shape[0], dtype=np.float64) * (hop / SAMPLE_RATE)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1) + 1e-12)
    energy = _normalized(rms)
    low = _normalized(_spectral_band_energy(frames, 35.0, 250.0))
    mid = _spectral_band_energy(frames, 250.0, 4000.0)
    high = _spectral_band_energy(frames, 4000.0, 9000.0)
    vocal_raw = mid / (mid + 0.7 * high + 1e-9)
    vocal = _normalized(vocal_raw) * np.clip(energy * 1.25, 0.0, 1.0)
    vocal_probability = float(np.clip(np.mean(vocal[energy > 0.12]) if np.any(energy > 0.12) else 0.0, 0.0, 1.0))

    # Finer 100 ms onset envelope for tempo.
    beat_frame = max(512, int(0.10 * SAMPLE_RATE))
    beat_frames = _frame_signal(audio, beat_frame, beat_frame)
    beat_rms = np.sqrt(np.mean(beat_frames.astype(np.float64) ** 2, axis=1) + 1e-12)
    onset = np.maximum(0.0, np.diff(np.log1p(beat_rms * 1000.0), prepend=0.0))
    bpm, beat_conf, beats = _beat_grid(onset, beat_frame / SAMPLE_RATE)
    beat_interval = 60.0 / bpm if bpm > 0 else 0.0

    # We do not pretend to infer meter with confidence we do not have: four-beat bars are a safe
    # structural grid and the local planner still gates all ambitious use by beat confidence.
    downbeats = beats[::4] if beats else []
    phrases = downbeats[::4] if downbeats else []
    first_beat = beats[0] if beats else 0.0

    active = np.flatnonzero(energy >= 0.06)
    audible_start = float(times[active[0]]) if active.size else 0.0
    content_end = min(duration, float(times[active[-1]] + hop / SAMPLE_RATE)) if active.size else duration

    # Structure anchors from sustained changes in the normalized energy envelope.
    intro_limit = min(len(energy), max(1, int(45.0 / (hop / SAMPLE_RATE))))
    intro_candidates = np.flatnonzero(energy[:intro_limit] >= 0.35)
    intro_end = float(times[intro_candidates[0]]) if intro_candidates.size else min(8.0, duration * 0.1)
    tail_start_idx = max(0, int(len(energy) * 0.55))
    tail = energy[tail_start_idx:]
    quiet_tail = np.flatnonzero(tail <= 0.35)
    outro_start = float(times[tail_start_idx + quiet_tail[0]]) if quiet_tail.size else max(0.0, content_end - min(20.0, duration * 0.12))

    key, key_conf = _key_from_audio(audio)
    mix_in_candidates, mix_out_candidates = _candidate_points(times, energy, vocal, downbeats, duration)
    mix_in = float(mix_in_candidates[0]["time"]) if mix_in_candidates else intro_end
    mix_out = float(mix_out_candidates[0]["time"]) if mix_out_candidates else content_end

    return {
        "duration": round(duration, 4),
        "bpm": round(bpm, 5),
        "beatInterval": round(beat_interval, 6),
        "beatConfidence": round(beat_conf, 5),
        "downbeats": [round(float(x), 4) for x in downbeats[:1024]],
        "phraseBoundaries": [round(float(x), 4) for x in phrases[:256]],
        "firstBeat": round(first_beat, 4),
        "key": key,
        "keyConfidence": round(key_conf, 5),
        "audibleStartTime": round(audible_start, 4),
        "pickupTime": round(first_beat or audible_start, 4),
        "introEndTime": round(intro_end, 4),
        "contentEndTime": round(content_end, 4),
        "outroStartTime": round(outro_start, 4),
        "mixInTime": round(mix_in, 4),
        "mixOutTime": round(mix_out, 4),
        "mixInCandidates": mix_in_candidates,
        "mixOutCandidates": mix_out_candidates,
        "energyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, energy, strict=False)
        ],
        "lowEnergyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, low, strict=False)
        ],
        "vocalActivityMask": [round(float(x), 5) for x in vocal],
        "vocalProbability": round(vocal_probability, 5),
        "trackId": track_id,
    }


@router.get("/health")
async def health() -> dict[str, Any]:
    return {"ok": True, "version": API_VERSION, "analyzer": "orb-remote-dsp-v1"}


@router.get("/analysis/{track_id}")
async def cached_analysis(track_id: str) -> dict[str, Any]:
    return {"version": API_VERSION, "analysis": _cache_get(track_id)}


@router.post("/analyze")
async def analyze(
    track_id: str = Form(...),
    duration_seconds: float = Form(0.0),
    version: int = Form(API_VERSION),
    audio: UploadFile = File(...),
) -> dict[str, Any]:
    if version > API_VERSION:
        raise HTTPException(status_code=409, detail="unsupported Automix protocol version")
    track_id = track_id.strip()
    if not track_id or len(track_id) > 256:
        raise HTTPException(status_code=400, detail="invalid track id")
    existing = _cache_get(track_id)
    if existing is not None:
        return {"version": API_VERSION, "analysis": existing, "cached": True}

    suffix = os.path.splitext(audio.filename or "analysis.bin")[1][:16]
    temp_path = ""
    written = 0
    try:
        with tempfile.NamedTemporaryFile(prefix="orb-automix-", suffix=suffix, delete=False) as tmp:
            temp_path = tmp.name
            while True:
                chunk = await audio.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="analysis audio too large")
                tmp.write(chunk)
        if written == 0:
            raise HTTPException(status_code=400, detail="empty analysis audio")
        async with _analysis_slots:
            result = await asyncio.to_thread(_analyze, temp_path, track_id, float(duration_seconds))
        if not 40.0 <= _finite(result.get("bpm")) <= 220.0:
            raise HTTPException(status_code=422, detail="tempo could not be measured reliably")
        _cache_put(track_id, result)
        return {"version": API_VERSION, "analysis": result, "cached": False}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"analysis failed: {type(exc).__name__}") from exc
    finally:
        await audio.close()
        if temp_path:
            try:
                os.remove(temp_path)
            except OSError:
                pass


@router.post("/plan")
async def plan(request: PlanRequest) -> dict[str, Any]:
    if request.version > API_VERSION:
        raise HTTPException(status_code=409, detail="unsupported Automix protocol version")
    a, b = request.outgoing, request.incoming
    a_bpm = _finite(a.get("bpm"))
    b_bpm = _finite(b.get("bpm"))
    a_conf = _clamp(_finite(a.get("beatConfidence")), 0.0, 1.0)
    b_conf = _clamp(_finite(b.get("beatConfidence")), 0.0, 1.0)
    a_vocal = _clamp(_finite(a.get("vocalProbability")), 0.0, 1.0)
    b_vocal = _clamp(_finite(b.get("vocalProbability")), 0.0, 1.0)

    if not (40.0 <= a_bpm <= 220.0 and 40.0 <= b_bpm <= 220.0):
        style = "EQUAL_POWER"
        reason = "missing-tempo"
    elif min(a_conf, b_conf) < 0.18:
        style = "EQUAL_POWER"
        reason = "weak-grid"
    else:
        ratio = b_bpm / a_bpm
        while ratio > 1.5:
            ratio /= 2.0
        while ratio < 0.67:
            ratio *= 2.0
        delta = abs(1.0 - ratio)
        vocal_clash = a_vocal >= 0.68 and b_vocal >= 0.68
        if delta <= 0.05 and min(a_conf, b_conf) >= 0.35 and not vocal_clash:
            style = "DJ_BLEND"
            reason = "compatible-grid"
        elif vocal_clash and delta > 0.08:
            style = "EQUAL_POWER"
            reason = "vocal-clash"
        else:
            style = "DJ_FILTER"
            reason = "assisted-transition"
    return {"version": API_VERSION, "style": style, "reason": reason}
