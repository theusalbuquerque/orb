"""Remote musical intelligence for Orb Automix.

Mount this router into the existing Orb FastAPI service. The Android client uploads only its
standalone lightweight analysis rendition (normally YouTube Opus), never the Lossless/Hi-Res
playback file. Audio is deleted immediately after feature extraction; only derived metadata is
kept in a bounded in-memory cache. Automix v5 makes the server the sole authority for choosing
the transition family, timing and choreography; Android only executes or transport-falls back.
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

API_VERSION = 5
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


def _downbeats_from_beats(
    beats: list[float],
    onset: np.ndarray,
    hop_seconds: float,
) -> list[float]:
    """Choose the strongest of the four possible 4/4 bar phases.

    Tempo detection gives us a beat grid, but its first beat is not necessarily beat 1 of a bar.
    Treating beats[0] as a downbeat shifts every phrase decision when the detector locked to beat
    2, 3 or 4. Accent strength across the whole track is a better phase cue.
    """
    if len(beats) < 4 or onset.size == 0 or hop_seconds <= 0.0:
        return beats[::4]

    strengths: list[float] = []
    for beat in beats:
        center = int(round(beat / hop_seconds))
        lo = max(0, center - 1)
        hi = min(onset.size, center + 2)
        strengths.append(float(np.max(onset[lo:hi])) if hi > lo else 0.0)

    phase_scores: list[float] = []
    for phase in range(4):
        values = np.asarray(strengths[phase::4], dtype=np.float64)
        if values.size == 0:
            phase_scores.append(float("-inf"))
            continue
        # Median resists one oversized fill/crash deciding the bar phase by itself; the upper
        # quartile still rewards a phase that repeatedly carries strong first-beat accents.
        phase_scores.append(
            0.65 * float(np.median(values))
            + 0.35 * float(np.percentile(values, 75))
        )

    best_phase = int(np.argmax(np.asarray(phase_scores)))
    return beats[best_phase::4]


def _phrase_boundaries_from_structure(
    downbeats: list[float],
    times: np.ndarray,
    energy: np.ndarray,
    low: np.ndarray,
    vocal: np.ndarray,
) -> list[float]:
    """Choose a four-bar phrase phase from arrangement changes on real downbeats."""
    if len(downbeats) < 4:
        return downbeats
    if times.size == 0:
        return downbeats[::4]

    gaps = [
        right - left
        for left, right in zip(downbeats, downbeats[1:], strict=False)
        if right > left
    ]
    bar_seconds = float(np.median(gaps)) if gaps else 2.0
    if bar_seconds <= 0.0:
        return downbeats[::4]

    def window_mean(values: np.ndarray, start: float, end: float, default: float) -> float:
        mask = (times >= start) & (times < end)
        if not np.any(mask):
            return default
        return float(np.mean(values[mask]))

    boundary_scores: list[float] = []
    context = max(bar_seconds, 2.0 * bar_seconds)
    for t in downbeats:
        e_before = window_mean(energy, max(0.0, t - context), t, 0.5)
        e_after = window_mean(energy, t, t + context, e_before)
        l_before = window_mean(low, max(0.0, t - context), t, 0.5)
        l_after = window_mean(low, t, t + context, l_before)
        v_before = window_mean(vocal, max(0.0, t - context), t, 0.5)
        v_after = window_mean(vocal, t, t + context, v_before)

        change = (
            0.45 * abs(e_after - e_before)
            + 0.30 * abs(l_after - l_before)
            + 0.20 * abs(v_after - v_before)
            + 0.05 * max(0.0, e_after - e_before)
        )
        boundary_scores.append(change)

    phase_scores: list[float] = []
    for phase in range(4):
        values = np.asarray(boundary_scores[phase::4], dtype=np.float64)
        if values.size == 0:
            phase_scores.append(float("-inf"))
            continue
        phase_scores.append(
            0.70 * float(np.mean(values))
            + 0.30 * float(np.max(values))
        )

    best_phase = int(np.argmax(np.asarray(phase_scores)))
    return downbeats[best_phase::4]


def _chroma_window(audio: np.ndarray) -> np.ndarray | None:
    """Pitch-class energy for one contiguous window.

    Keeping the window contiguous matters: selecting evenly spaced samples from a long track and
    treating them as adjacent audio changes the effective sample rate and therefore the pitches.
    """
    if audio.size < 2048:
        return None

    max_fft = 1 << 17  # ~5.9 s at 22.05 kHz: enough resolution without one giant FFT.
    usable = min(audio.size, max_fft)
    n = 1 << int(math.floor(math.log2(max(2048, usable))))
    if n > audio.size:
        return None

    offset = max(0, (audio.size - n) // 2)
    sample = np.asarray(audio[offset:offset + n], dtype=np.float64)
    sample = sample - float(np.mean(sample))
    rms = float(np.sqrt(np.mean(sample * sample) + 1e-12))
    if rms <= 1e-5:
        return None

    spectrum = np.abs(np.fft.rfft(sample * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / SAMPLE_RATE)
    valid = (freqs >= 55.0) & (freqs <= 5000.0)
    if not np.any(valid):
        return None

    valid_freqs = freqs[valid]
    valid_mag = np.sqrt(np.maximum(spectrum[valid], 0.0))
    midi = 69.0 + 12.0 * np.log2(valid_freqs / 440.0)
    pitch_classes = np.mod(np.rint(midi).astype(np.int32), 12)

    chroma = np.zeros(12, dtype=np.float64)
    np.add.at(chroma, pitch_classes, valid_mag)
    total = float(np.sum(chroma))
    if total <= 1e-9:
        return None
    return chroma / total


def _key_from_audio(audio: np.ndarray) -> tuple[str, float]:
    if audio.size < SAMPLE_RATE:
        return "", 0.0

    # Analyze several *contiguous* windows across the arrangement. This sees key changes and
    # repeated harmonic material without frequency-warping the source or only trusting the intro.
    window_samples = min(audio.size, 6 * SAMPLE_RATE)
    if audio.size <= window_samples:
        starts = [0]
    else:
        window_count = min(8, max(3, int(math.ceil(audio.size / (30.0 * SAMPLE_RATE)))))
        max_start = audio.size - window_samples
        starts = [
            int(round(x))
            for x in np.linspace(0, max_start, num=window_count)
        ]

    observations: list[tuple[np.ndarray, float]] = []
    for start in starts:
        segment = np.asarray(audio[start:start + window_samples], dtype=np.float32)
        if segment.size < SAMPLE_RATE:
            continue
        rms = float(np.sqrt(np.mean(segment.astype(np.float64) ** 2) + 1e-12))
        chroma = _chroma_window(segment)
        if chroma is not None:
            observations.append((chroma, rms))

    if not observations:
        return "", 0.0

    max_rms = max(rms for _, rms in observations)
    active = [
        (chroma, rms)
        for chroma, rms in observations
        if rms >= max(1e-5, max_rms * 0.12)
    ]
    if not active:
        active = observations

    aggregate = np.zeros(12, dtype=np.float64)
    for chroma, rms in active:
        # Loud windows matter somewhat more, but do not let one mastered chorus erase the rest
        # of the track's harmony.
        weight = math.sqrt(max(rms, 1e-9) / max(max_rms, 1e-9))
        aggregate += chroma * weight
    total = float(np.sum(aggregate))
    if total <= 1e-9:
        return "", 0.0
    aggregate /= total

    major_profile = np.array(
        [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88],
        dtype=np.float64,
    )
    minor_profile = np.array(
        [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17],
        dtype=np.float64,
    )
    major_profile /= np.linalg.norm(major_profile)
    minor_profile /= np.linalg.norm(minor_profile)

    def profile_scores(chroma: np.ndarray) -> list[tuple[float, int, str]]:
        norm = float(np.linalg.norm(chroma))
        if norm <= 1e-9:
            return []
        unit = chroma / norm
        values: list[tuple[float, int, str]] = []
        for tonic in range(12):
            values.append((float(np.dot(unit, np.roll(major_profile, tonic))), tonic, "major"))
            values.append((float(np.dot(unit, np.roll(minor_profile, tonic))), tonic, "minor"))
        return sorted(values, reverse=True)

    scores = profile_scores(aggregate)
    if len(scores) < 2:
        return "", 0.0
    best, tonic, mode = scores[0]
    second = scores[1][0]
    margin = _clamp((best - second) / max(abs(best), 1e-9), 0.0, 1.0)

    # A stable key should recur across the track. Exact agreement is intentionally strict:
    # modulation lowers confidence, which is safer than authorizing a long harmonic overlap.
    window_winners = []
    for chroma, _ in active:
        local = profile_scores(chroma)
        if local:
            window_winners.append((local[0][1], local[0][2]))
    consistency = (
        sum(1 for local_tonic, local_mode in window_winners if local_tonic == tonic and local_mode == mode)
        / len(window_winners)
        if window_winners else 0.0
    )

    confidence = _clamp(0.70 * min(1.0, margin * 10.0) + 0.30 * consistency, 0.0, 1.0)
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
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

    # Tempo gives a beat grid, not bar/phrase phase. Infer both phases from accents and
    # arrangement changes instead of assuming the first detected beat is beat 1.
    downbeats = _downbeats_from_beats(beats, onset, beat_frame / SAMPLE_RATE) if beats else []
    phrases = _phrase_boundaries_from_structure(
        downbeats,
        times,
        energy,
        low,
        vocal,
    ) if downbeats else []
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
    return {"ok": True, "version": API_VERSION, "analyzer": "orb-remote-dsp-v5"}


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


def _overlay_client_vocal_mask(
    cached: dict[str, Any],
    payload: dict[str, Any],
) -> list[float] | None:
    """Overlay the phone's measured vocal evidence onto the server's full-track mask.

    The Android open-unmix pass measures only the head and tail — exactly the windows used by a
    transition — and fills the unmeasured middle with 0.5. Preserve the server's full-track
    heuristic in that middle instead of replacing it with neutral values. The two analyses can
    have slightly different time grids, so evidence is aligned by timestamp rather than index.
    """
    server_energy = _curve(cached, "energyCurve")
    client_energy = _curve(payload, "energyCurve")
    raw_client = payload.get("vocalActivityMask")
    if not server_energy or not client_energy or not isinstance(raw_client, list):
        return None
    if len(raw_client) != len(client_energy):
        return None

    server_raw = cached.get("vocalActivityMask")
    if isinstance(server_raw, list) and len(server_raw) == len(server_energy):
        merged = [_clamp(_finite(value, 0.5), 0.0, 1.0) for value in server_raw]
    else:
        merged = [0.5] * len(server_energy)

    measured: list[tuple[float, float]] = []
    for (time_s, _), raw_value in zip(client_energy, raw_client, strict=False):
        value = _clamp(_finite(raw_value, 0.5), 0.0, 1.0)
        # TrackAnalyzer deliberately writes exact 0.5 outside the ONNX windows. Do not let those
        # placeholders erase server evidence. A tiny dead-band also avoids treating float noise
        # around the sentinel as measured vocals.
        if abs(value - 0.5) >= 0.02:
            measured.append((time_s, value))
    if not measured:
        return None

    client_gaps = [
        right[0] - left[0]
        for left, right in zip(client_energy, client_energy[1:], strict=False)
        if right[0] > left[0]
    ]
    cadence = float(np.median(client_gaps)) if client_gaps else 0.5
    tolerance = max(0.30, cadence * 0.75)

    measured_index = 0
    for index, (time_s, _) in enumerate(server_energy):
        while (
            measured_index + 1 < len(measured)
            and measured[measured_index + 1][0] <= time_s
        ):
            measured_index += 1
        neighbours = [measured[measured_index]]
        if measured_index + 1 < len(measured):
            neighbours.append(measured[measured_index + 1])
        best_time, best_value = min(neighbours, key=lambda point: abs(point[0] - time_s))
        if abs(best_time - time_s) <= tolerance:
            merged[index] = best_value

    return merged


def _merge_plan_evidence(
    cached: dict[str, Any],
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Fuse full server analysis with stronger transition-window evidence from Android."""
    merged = dict(cached)

    # Client scalars describe the exact rendition/player state and can be newer than the cache.
    structural = {
        "energyCurve",
        "lowEnergyCurve",
        "vocalActivityMask",
        "downbeats",
        "phraseBoundaries",
        "mixInCandidates",
        "mixOutCandidates",
    }
    for key, value in payload.items():
        if value is not None and key not in structural:
            merged[key] = value

    # Beat This! directly predicts downbeats. When its confidence is high enough, those head/tail
    # anchors are more relevant to the transition than the server's autocorrelation bar phase.
    client_downbeats = payload.get("downbeats")
    client_beat_conf = _clamp(_finite(payload.get("beatConfidence")), 0.0, 1.0)
    if (
        isinstance(client_downbeats, list)
        and len(client_downbeats) >= 2
        and client_beat_conf >= 0.55
    ):
        trusted_downbeats = sorted({
            round(max(0.0, _finite(value)), 6)
            for value in client_downbeats
            if math.isfinite(_finite(value, float("nan")))
        })
        if len(trusted_downbeats) >= 2:
            merged["downbeats"] = trusted_downbeats
            merged["beatEvidence"] = "client-model"

    # The phone's full TrackFeatures curve is required only as the time axis for its open-unmix
    # mask. Keep the server's own energy/low curves for energy scoring; overlay model vocal
    # evidence where the phone actually measured it.
    vocal_mask = _overlay_client_vocal_mask(cached, payload)
    if vocal_mask is not None:
        merged["vocalActivityMask"] = vocal_mask
        merged["vocalEvidence"] = "client-model-window"

    # Phrase phase must live on the same bar grid as the selected downbeats. If Beat This!
    # replaced the server's inferred bar phase, recompute four-bar boundaries from the full
    # server energy/low curves plus the fused vocal evidence instead of keeping stale phrases.
    if merged.get("beatEvidence") == "client-model":
        energy_points = _curve(merged, "energyCurve")
        low_points = _curve(merged, "lowEnergyCurve")
        vocal_points = _vocal_curve(merged)
        if energy_points and len(merged.get("downbeats") or []) >= 4:
            times = np.asarray([time_s for time_s, _ in energy_points], dtype=np.float64)
            energy_values = np.asarray([value for _, value in energy_points], dtype=np.float64)

            def values_on_grid(
                points: list[tuple[float, float]],
                default: float,
            ) -> np.ndarray:
                if not points:
                    return np.full(times.size, default, dtype=np.float64)
                point_times = np.asarray([time_s for time_s, _ in points], dtype=np.float64)
                point_values = np.asarray([value for _, value in points], dtype=np.float64)
                indices = np.searchsorted(point_times, times, side="left")
                indices = np.clip(indices, 0, len(point_times) - 1)
                previous = np.clip(indices - 1, 0, len(point_times) - 1)
                choose_previous = (
                    np.abs(point_times[previous] - times)
                    <= np.abs(point_times[indices] - times)
                )
                chosen = np.where(choose_previous, previous, indices)
                return point_values[chosen]

            low_values = values_on_grid(low_points, 0.5)
            vocal_values = values_on_grid(vocal_points, 0.5)
            phrases = _phrase_boundaries_from_structure(
                [float(value) for value in merged["downbeats"]],
                times,
                energy_values,
                low_values,
                vocal_values,
            )
            if phrases:
                merged["phraseBoundaries"] = phrases
                merged["phraseEvidence"] = "server-structure-on-client-grid"

    # If the server lacks structural data entirely, a completed client pass is still better than
    # dropping those fields. Empty/provisional client lists never erase cached full-track data.
    for key in ("energyCurve", "lowEnergyCurve", "phraseBoundaries", "mixInCandidates", "mixOutCandidates"):
        client_value = payload.get(key)
        if (
            (key not in merged or not merged.get(key))
            and isinstance(client_value, list)
            and client_value
        ):
            merged[key] = client_value

    return merged


def _plan_track(payload: dict[str, Any]) -> dict[str, Any]:
    """Prefer cached full-track analysis, but let stronger client model evidence override it."""
    track_id = str(payload.get("trackId") or "").strip()
    cached = _cache_get(track_id) if track_id else None
    if cached is None:
        return dict(payload)
    return _merge_plan_evidence(cached, payload)


def _curve(track: dict[str, Any], name: str) -> list[tuple[float, float]]:
    raw = track.get(name)
    if not isinstance(raw, list):
        return []
    out: list[tuple[float, float]] = []
    for point in raw:
        if not isinstance(point, dict):
            continue
        t = _finite(point.get("time"), float("nan"))
        e = _finite(point.get("energy"), float("nan"))
        if math.isfinite(t) and math.isfinite(e):
            out.append((max(0.0, t), _clamp(e, 0.0, 1.0)))
    return out


def _vocal_curve(track: dict[str, Any]) -> list[tuple[float, float]]:
    energy = _curve(track, "energyCurve")
    raw = track.get("vocalActivityMask")
    if not isinstance(raw, list) or not energy:
        return []
    n = min(len(energy), len(raw))
    return [
        (energy[i][0], _clamp(_finite(raw[i]), 0.0, 1.0))
        for i in range(n)
    ]


def _mean_window(points: list[tuple[float, float]], start: float, end: float, default: float) -> float:
    if end <= start:
        return default
    values = [v for t, v in points if start <= t < end]
    return float(sum(values) / len(values)) if values else default


def _value_near(points: list[tuple[float, float]], time_s: float, default: float = 0.0) -> float:
    if not points:
        return default
    return min(points, key=lambda p: abs(p[0] - time_s))[1]


def _first_sustained_vocal(track: dict[str, Any]) -> float | None:
    points = _vocal_curve(track)
    streak = 0
    for i, (t, value) in enumerate(points):
        if value >= 0.40:
            streak += 1
            if streak >= 3:
                return points[i - 2][0]
        else:
            streak = 0
    return None


def _audible_start(track: dict[str, Any]) -> float:
    explicit = _finite(track.get("audibleStartTime"), -1.0)
    if explicit >= 0.0:
        return explicit
    energy = _curve(track, "energyCurve")
    for t, value in energy:
        if value >= 0.05:
            return t
    return 0.0


def _max_window_mean(
    points: list[tuple[float, float]],
    start: float,
    end: float,
    window: float,
    default: float,
) -> float:
    """Maximum local mean in [start, end], used to reject false release pockets."""
    if not points or end <= start:
        return default
    times = [t for t, _ in points if start <= t <= end]
    if not times:
        return default
    return max(
        _mean_window(points, t, min(end, t + window), default)
        for t in times
    )


def _content_end(track: dict[str, Any]) -> float:
    duration = max(0.0, _finite(track.get("duration")))
    explicit = _finite(track.get("contentEndTime"), 0.0)
    if explicit > 0.0:
        return min(explicit, duration) if duration > 0.0 else explicit
    return duration


def _low_curve(track: dict[str, Any]) -> list[tuple[float, float]]:
    return _curve(track, "lowEnergyCurve")


def _impact_time(track: dict[str, Any]) -> float:
    """Find a useful structural arrival in B without jumping over its opening arrangement."""
    start = _audible_start(track)
    end = _content_end(track)
    energy = _curve(track, "energyCurve")
    low = _low_curve(track)
    if not energy or end <= start:
        candidates = track.get("mixInCandidates") or []
        if isinstance(candidates, list):
            valid = [_finite(x.get("time"), -1.0) for x in candidates if isinstance(x, dict)]
            valid = [x for x in valid if x >= start]
            if valid:
                return min(valid)
        return max(start, _finite(track.get("mixInTime"), start))

    max_search = min(end, start + 95.0)
    structural: list[float] = []
    for key in ("phraseBoundaries", "downbeats"):
        raw = track.get(key)
        if isinstance(raw, list):
            structural.extend(_finite(x, -1.0) for x in raw)
    structural = [x for x in structural if start + 4.0 <= x <= max_search]

    first_vocal = _first_sustained_vocal(track)
    candidates = set(structural)
    if first_vocal is not None and first_vocal <= max_search:
        candidates.add(first_vocal)
    mix_in = _finite(track.get("mixInTime"), -1.0)
    if start + 4.0 <= mix_in <= max_search:
        candidates.add(mix_in)
    for item in track.get("mixInCandidates") or []:
        if isinstance(item, dict):
            t = _finite(item.get("time"), -1.0)
            if start + 4.0 <= t <= max_search:
                candidates.add(t)

    # Energy-rise candidates every ~2 seconds. This catches a drop/first strong section even when
    # the generic mixIn detector nominated a much earlier downbeat.
    for t, value in energy:
        if t < start + 6.0 or t > max_search or value < 0.28:
            continue
        pre = _mean_window(energy, max(start, t - 6.0), t, value)
        post = _mean_window(energy, t, min(max_search, t + 4.0), value)
        low_pre = _mean_window(low, max(start, t - 6.0), t, 0.0)
        low_post = _mean_window(low, t, min(max_search, t + 4.0), 0.0)
        if post - pre >= 0.08 or low_post - low_pre >= 0.10:
            candidates.add(t)

    if not candidates:
        intro_end = _finite(track.get("introEndTime"), 0.0)
        return intro_end if intro_end > start else min(max_search, start + 8.0)

    def score(t: float) -> float:
        pre = _mean_window(energy, max(start, t - 6.0), t, 0.0)
        post = _mean_window(energy, t, min(max_search, t + 4.0), 0.0)
        low_pre = _mean_window(low, max(start, t - 6.0), t, 0.0)
        low_post = _mean_window(low, t, min(max_search, t + 4.0), 0.0)
        vocal = _value_near(_vocal_curve(track), t, 0.0)
        structural_bonus = 0.08 if any(abs(t - s) <= 1.25 for s in structural) else 0.0
        vocal_arrival = 0.08 if first_vocal is not None and abs(t - first_vocal) <= 1.0 else 0.0
        # Prefer meaningful later arrivals over tiny early bumps, but do not chase a chorus deep
        # into the song simply because it is louder.
        timing = _clamp((t - start) / 45.0, 0.0, 1.0)
        late_penalty = _clamp((t - start - 62.0) / 28.0, 0.0, 1.0)
        return (
            0.28 * post +
            0.24 * max(0.0, post - pre) +
            0.18 * max(0.0, low_post - low_pre) +
            0.09 * vocal + structural_bonus + vocal_arrival +
            0.06 * timing - 0.18 * late_penalty
        )

    return max(candidates, key=score)


def _release_landmarks(track: dict[str, Any]) -> tuple[float, float, bool]:
    """Return (foreground release, content end, protected-to-end).

    A release is not one quiet two-second pocket. It is the point after which the final foreground
    phrase does not come back. This specifically prevents a breath/break in a last chorus from
    authorizing B to take over 8-15 seconds before A actually resolves.
    """
    end = _content_end(track)
    if end <= 0.0:
        return 0.0, 0.0, False
    energy = _curve(track, "energyCurve")
    vocal = _vocal_curve(track)
    default_vocal = _clamp(_finite(track.get("vocalProbability"), 0.5), 0.0, 1.0)
    search_start = max(0.0, min(
        _finite(track.get("outroStartTime"), end - 24.0) or end - 24.0,
        end - 8.0,
    ))
    search_start = max(search_start, end - 40.0)

    candidate: float | None = None
    for t, _ in energy:
        if t < search_start or t > end - 0.75:
            continue
        immediate_end = min(end, t + 2.5)
        confirm_end = min(end, t + 6.0)
        e = _mean_window(energy, t, immediate_end, 0.5)
        v = _mean_window(vocal, t, immediate_end, default_vocal)
        if e > 0.54 or v > 0.42:
            continue

        # The pocket must stay released for several seconds, not only one classifier frame.
        confirm_e = _mean_window(energy, t, confirm_end, e)
        confirm_v = _mean_window(vocal, t, confirm_end, v)
        if confirm_e > 0.60 or confirm_v > 0.40:
            continue

        # Most importantly, reject the candidate if the foreground vocal/arrangement comes back
        # later in the ending. A strong instrumental rebound alone may still be a usable tail, but
        # a vocal rebound or a dense+vocal rebound means A has not actually released yet.
        future_vocal = _max_window_mean(vocal, t, end, 2.0, default_vocal)
        future_energy = _max_window_mean(energy, t, end, 2.0, e)
        future_mean_energy = _mean_window(energy, t, end, e)
        if future_vocal > 0.48:
            continue
        # A strong instrumental rebound is also a real continuation of A. Do not call a 2-6 s
        # pocket a release when the arrangement returns and remains dense afterwards.
        if future_energy > 0.76 and future_mean_energy > 0.58:
            continue
        if future_energy > 0.80 and future_vocal > 0.30:
            continue

        candidate = t
        break

    tail_start = max(0.0, end - 12.0)
    tail_energy = _mean_window(energy, tail_start, end, 0.5)
    tail_vocal = _mean_window(vocal, tail_start, end, default_vocal)
    tail_peak_vocal = _max_window_mean(vocal, tail_start, end, 2.0, default_vocal)
    protected = candidate is None and tail_energy >= 0.64 and (tail_vocal >= 0.44 or tail_peak_vocal >= 0.52)
    if candidate is None:
        # Keep the last phrase whole. A sub-second release is enough for the click-safe handoff.
        candidate = max(0.0, end - (0.55 if protected else 1.5))
    return candidate, end, protected


def _tempo_pair(a: dict[str, Any], b: dict[str, Any]) -> tuple[float, float, float]:
    a_bpm = _finite(a.get("bpm"))
    b_bpm = _finite(b.get("bpm"))
    if not (40.0 <= a_bpm <= 220.0 and 40.0 <= b_bpm <= 220.0):
        return a_bpm, b_bpm, 0.0
    aligned = b_bpm
    while aligned / a_bpm > 1.5:
        aligned /= 2.0
    while aligned / a_bpm < 0.67:
        aligned *= 2.0
    compatibility = _clamp(1.0 - abs(aligned / a_bpm - 1.0) / 0.14, 0.0, 1.0)
    return a_bpm, aligned, compatibility


_KEY_INDEX = {
    "C": 0, "C#": 1, "DB": 1, "D": 2, "D#": 3, "EB": 3,
    "E": 4, "F": 5, "F#": 6, "GB": 6, "G": 7, "G#": 8,
    "AB": 8, "A": 9, "A#": 10, "BB": 10, "B": 11,
}


def _trusted_key(track: dict[str, Any]) -> tuple[int, str] | None:
    if _clamp(_finite(track.get("keyConfidence")), 0.0, 1.0) < 0.25:
        return None
    raw = str(track.get("key") or "").strip().replace("♯", "#").replace("♭", "b")
    if not raw:
        return None
    parts = raw.split()
    tonic = parts[0].upper()
    index = _KEY_INDEX.get(tonic)
    if index is None:
        return None
    mode = parts[1].lower() if len(parts) > 1 else ""
    return index, mode


def _key_compatibility(a: dict[str, Any], b: dict[str, Any]) -> float:
    """0..1 harmonic compatibility based on musical relationships, not semitone proximity.

    A key one semitone away is physically close on a keyboard but usually a poor place to leave
    two full arrangements open. Strong scores are reserved for relationships a DJ would actually
    use: same key, relative major/minor, and fourth/fifth in the same mode.
    Unknown/low-confidence keys stay neutral so they cannot manufacture a harmonic advantage.
    """
    left = _trusted_key(a)
    right = _trusted_key(b)
    if left is None or right is None:
        return 0.55

    li, lm = left
    ri, rm = right
    distance = min((li - ri) % 12, (ri - li) % 12)
    same_mode = bool(lm and rm and lm == rm)
    different_mode = bool(lm and rm and lm != rm)

    if same_mode and distance == 0:
        return 1.0
    if different_mode and distance == 3:
        # Relative major/minor.
        return 0.92
    if same_mode and distance == 5:
        # Perfect fourth/fifth relationship.
        return 0.88
    if different_mode and distance == 0:
        # Parallel major/minor: related, but usually not safe for a flat overlap.
        return 0.52

    # Mild credit for incomplete mode labels with the same tonic; otherwise,
    # chromatic proximity is not treated as harmonic compatibility.
    if (not lm or not rm) and distance == 0:
        return 0.72

    return 0.18


def _tempo_bridge_rates(a_bpm: float, b_bpm: float) -> tuple[float, float]:
    """Meet halfway in tempo so neither deck carries the whole correction."""
    if not (40.0 <= a_bpm <= 220.0 and 40.0 <= b_bpm <= 220.0):
        return 1.0, 1.0
    distance = abs(b_bpm / a_bpm - 1.0)
    if distance > 0.08:
        return 1.0, 1.0
    meeting = math.sqrt(a_bpm * b_bpm)
    return (
        _clamp(meeting / a_bpm, 0.96, 1.04),
        _clamp(meeting / b_bpm, 0.96, 1.04),
    )



def _structural_snap(
    track: dict[str, Any],
    desired: float,
    lo: float,
    hi: float,
    beat_seconds: float,
) -> tuple[float, float]:
    """Snap a timing target to a nearby phrase/downbeat without amputating arrangement.

    Returns (time, confidence): phrase boundary=1.0, downbeat=0.78, unsnapped=0.45.
    The search is intentionally local (about two beats either side), so musical alignment
    cannot drag a transition tens of seconds away from the planner's structural intent.
    """
    if hi <= lo:
        return _clamp(desired, lo, max(lo, hi)), 0.45
    radius = max(0.35, 2.0 * beat_seconds)
    search_lo = max(lo, desired - radius)
    search_hi = min(hi, desired + radius)

    def values(name: str) -> list[float]:
        raw = track.get(name)
        if not isinstance(raw, list):
            return []
        out = [_finite(x, -1.0) for x in raw]
        return [x for x in out if search_lo <= x <= search_hi]

    phrases = values("phraseBoundaries")
    if phrases:
        return min(phrases, key=lambda x: abs(x - desired)), 1.0

    downbeats = values("downbeats")
    if downbeats:
        return min(downbeats, key=lambda x: abs(x - desired)), 0.78

    return _clamp(desired, lo, hi), 0.45


def _overlap_pair_metrics(
    a: dict[str, Any],
    b: dict[str, Any],
    a_start: float,
    a_end: float,
    b_start: float,
    incoming_rate: float = 1.0,
) -> tuple[float, float]:
    """Vocal collision and energy continuity for the audio that will actually overlap."""
    duration = max(0.25, a_end - a_start)
    b_content_end = _content_end(b)
    b_window_end = b_start + duration * max(0.5, incoming_rate)
    if b_content_end > 0.0:
        b_window_end = min(b_window_end, b_content_end)

    a_vocal = _mean_window(
        _vocal_curve(a),
        a_start,
        a_end,
        _finite(a.get("vocalProbability"), 0.5),
    )
    b_vocal = _mean_window(
        _vocal_curve(b),
        b_start,
        b_window_end,
        _finite(b.get("vocalProbability"), 0.5),
    )
    vocal_clash = min(a_vocal, b_vocal)

    a_activity = _mean_window(_curve(a, "energyCurve"), a_start, a_end, 0.5)
    b_activity = _mean_window(_curve(b, "energyCurve"), b_start, b_window_end, 0.5)
    energy_fit = _clamp(1.0 - abs(a_activity - b_activity), 0.0, 1.0)
    return vocal_clash, energy_fit


def _timing_candidates(
    track: dict[str, Any],
    desired: float,
    lo: float,
    hi: float,
    beat_seconds: float,
    mix_key: str,
    limit: int = 10,
) -> list[tuple[float, float, str]]:
    """Rank nearby musical anchors without reducing a track to one scalar cue."""
    if hi <= lo:
        return [(_clamp(desired, lo, max(lo, hi)), 0.45, "raw")]

    radius = max(1.0, 4.0 * beat_seconds)
    search_lo = max(lo, desired - radius)
    search_hi = min(hi, desired + radius)
    items: list[tuple[float, float, str]] = []

    raw_mix = track.get(mix_key)
    if isinstance(raw_mix, list):
        for item in raw_mix[:12]:
            if not isinstance(item, dict):
                continue
            time_s = _finite(item.get("time"), -1.0)
            if not (lo <= time_s <= hi):
                continue
            analyzer_score = _clamp(_finite(item.get("score"), 0.5), 0.0, 1.0)
            kind = str(item.get("type") or "mix")
            items.append((time_s, 0.68 + 0.30 * analyzer_score, kind))

    for name, quality, kind in (
        ("phraseBoundaries", 1.0, "phrase"),
        ("downbeats", 0.80, "downbeat"),
    ):
        raw = track.get(name)
        if not isinstance(raw, list):
            continue
        for value in raw:
            time_s = _finite(value, -1.0)
            if search_lo <= time_s <= search_hi:
                items.append((time_s, quality, kind))

    snapped, snap_quality = _structural_snap(track, desired, lo, hi, beat_seconds)
    items.append((snapped, snap_quality, "snap"))
    items.append((_clamp(desired, lo, hi), 0.45, "raw"))

    # Keep the strongest representative for anchors that land on effectively the same beat.
    dedupe_radius = max(0.06, beat_seconds * 0.16)
    unique: list[tuple[float, float, str]] = []
    for time_s, quality, kind in sorted(items, key=lambda x: x[0]):
        if unique and abs(unique[-1][0] - time_s) <= dedupe_radius:
            if quality > unique[-1][1]:
                unique[-1] = (time_s, quality, kind)
            continue
        unique.append((time_s, quality, kind))

    unique.sort(
        key=lambda x: (
            x[1],
            -abs(x[0] - desired) / max(beat_seconds, 0.25),
        ),
        reverse=True,
    )
    return unique[:limit]


def _best_structural_pair(
    a: dict[str, Any],
    b: dict[str, Any],
    a_release: float,
    a_end: float,
    b_start: float,
    b_end: float,
    desired_cue: float,
    target_beats: int,
    outgoing_bpm: float,
    incoming_bpm: float,
    incoming_rate: float,
) -> dict[str, float | str] | None:
    """Search A↔B phrase/downbeat pairs and score the audio that would actually overlap."""
    if a_end <= 0.0 or b_end <= b_start:
        return None
    if outgoing_bpm <= 0.0 or incoming_bpm <= 0.0:
        return None

    out_beat = 60.0 / outgoing_bpm
    in_beat = 60.0 / incoming_bpm
    target_span = target_beats * out_beat
    desired_start = max(0.0, a_end - target_span)

    # Overlap start and authority handoff are different musical events. B may become
    # quietly audible before A releases, provided the pair is rhythmically/harmonically
    # compatible and the actual overlap has low vocal collision. A's release is converted
    # into handoffFraction below instead of being used as a hard start gate.
    a_lo = max(0.0, desired_start - 4.0 * out_beat)
    a_hi = max(a_lo, min(a_end - 0.20, desired_start + 4.0 * out_beat))
    outgoing = _timing_candidates(
        a,
        desired_start,
        a_lo,
        a_hi,
        out_beat,
        "",
        limit=8,
    )

    # B may offer several valid entry phrases, but skipping deeply into the track is expensive.
    b_hi = max(
        b_start,
        min(
            b_end - 0.25,
            max(desired_cue + 8.0 * in_beat, b_start + 32.0),
        ),
    )
    incoming = _timing_candidates(
        b,
        desired_cue,
        b_start,
        b_hi,
        in_beat,
        "mixInCandidates",
        limit=10,
    )

    best: dict[str, float | str] | None = None
    best_score = float("-inf")

    for a_start, a_structure, a_kind in outgoing:
        actual_span = a_end - a_start
        if actual_span <= 0.20:
            continue
        actual_beats = actual_span / out_beat
        span_fit = _clamp(
            1.0 - abs(actual_beats - target_beats) / max(4.0, float(target_beats)),
            0.0,
            1.0,
        )

        for cue, b_structure, b_kind in incoming:
            vocal_clash, energy_fit = _overlap_pair_metrics(
                a,
                b,
                a_start,
                a_end,
                cue,
                incoming_rate,
            )
            skipped = max(0.0, cue - b_start)
            skip_penalty = _clamp((skipped - 8.0) / 24.0, 0.0, 1.0)
            cue_proximity = 1.0 - _clamp(
                abs(cue - desired_cue) / max(8.0, 8.0 * in_beat),
                0.0,
                1.0,
            )
            phrase_pair_bonus = 1.0 if a_kind == "phrase" and b_kind == "phrase" else 0.0
            pair_score = (
                0.18 * a_structure
                + 0.18 * b_structure
                + 0.24 * (1.0 - vocal_clash)
                + 0.14 * energy_fit
                + 0.12 * span_fit
                + 0.07 * cue_proximity
                + 0.07 * phrase_pair_bonus
                - 0.16 * skip_penalty
            )
            if pair_score <= best_score:
                continue

            best_score = pair_score
            release_fraction = _clamp(
                (a_release - a_start) / max(actual_span, 1e-6),
                0.0,
                1.0,
            )
            best = {
                "start": a_start,
                "cue": cue,
                "outgoingStructure": a_structure,
                "incomingStructure": b_structure,
                "phraseAlignment": min(a_structure, b_structure),
                "vocalClash": vocal_clash,
                "energyFit": energy_fit,
                "spanFit": span_fit,
                "pairScore": _clamp(pair_score, 0.0, 1.0),
                "actualBeats": max(1.0, actual_beats),
                "releaseFraction": release_fraction,
                "outgoingAnchor": a_kind,
                "incomingAnchor": b_kind,
            }

    return best


def _candidate_plan(style: str, score: float, reason: str, **kwargs: Any) -> dict[str, Any]:
    plan: dict[str, Any] = {
        "style": style,
        "score": round(_clamp(score, 0.0, 1.0), 4),
        "reason": reason,
    }
    plan.update(kwargs)
    return plan


def _remote_plan(a: dict[str, Any], b: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    a_release, a_end, protected = _release_landmarks(a)
    b_audible_start = _audible_start(b)
    b_start = b_audible_start
    b_end = _content_end(b)
    b_impact = _impact_time(b)
    b_vocal = _vocal_curve(b)
    b_energy = _curve(b, "energyCurve")
    a_vocal_curve = _vocal_curve(a)

    b_open_end = min(b_end if b_end > 0 else b_start + 8.0, b_start + 8.0)
    b_open_vocal = _mean_window(b_vocal, b_start, b_open_end, _finite(b.get("vocalProbability"), 0.5))
    b_open_activity = _mean_window(b_energy, b_start, b_open_end, 0.0)
    a_tail_start = max(0.0, a_end - 10.0)
    a_tail_vocal = _mean_window(a_vocal_curve, a_tail_start, a_end, _finite(a.get("vocalProbability"), 0.5))
    a_bpm, b_bpm, tempo = _tempo_pair(a, b)
    conf = min(_clamp(_finite(a.get("beatConfidence")), 0.0, 1.0), _clamp(_finite(b.get("beatConfidence")), 0.0, 1.0))
    key_fit = _key_compatibility(a, b)
    key_evidence = _trusted_key(a) is not None and _trusted_key(b) is not None
    bridge_out_rate, bridge_in_rate = _tempo_bridge_rates(a_bpm, b_bpm)
    tempo_distance = abs(b_bpm / a_bpm - 1.0) if a_bpm > 0.0 and b_bpm > 0.0 else 1.0
    tempo_bridge_ok = tempo_distance <= 0.08
    vocal_clash = min(a_tail_vocal, b_open_vocal)
    candidates: list[dict[str, Any]] = []

    # 1) Beat/key bridge. The planner now searches *pairs* of structural anchors:
    # A's late phrase/downbeat and B's entry phrase/downbeat are scored together.
    if a_end > 0.0 and 40.0 <= a_bpm <= 220.0 and 40.0 <= b_bpm <= 220.0:
        blend_ok = key_evidence and tempo_bridge_ok and tempo >= 0.62 and conf >= 0.35 and key_fit >= 0.58
        filter_ok = key_evidence and tempo_bridge_ok and tempo >= 0.35 and conf >= 0.28 and key_fit >= 0.35

        requested_style = "DJ_BLEND" if blend_ok else ("DJ_FILTER" if filter_ok else None)
        if requested_style is not None:
            target_beats = 16 if requested_style == "DJ_BLEND" and key_fit >= 0.72 else 8
            desired_cue = max(b_start, _finite(b.get("mixInTime"), b_start))
            pair = _best_structural_pair(
                a,
                b,
                a_release,
                a_end,
                b_start,
                b_end,
                desired_cue,
                target_beats,
                a_bpm,
                b_bpm,
                bridge_in_rate,
            )

            if pair is not None:
                start = float(pair["start"])
                cue = float(pair["cue"])
                phrase_fit = float(pair["phraseAlignment"])
                overlap_vocal_clash = float(pair["vocalClash"])
                energy_fit = float(pair["energyFit"])
                span_fit = float(pair["spanFit"])
                pair_fit = float(pair["pairScore"])
                actual_beats = int(round(float(pair["actualBeats"])))
                release_fraction = float(pair["releaseFraction"])

                style = requested_style
                # A flat blend with real vocal-on-vocal collision is demoted to a filtered
                # bridge only when that bridge is independently allowed. Heavy collision
                # rejects the overlap entirely.
                if style == "DJ_BLEND" and overlap_vocal_clash >= 0.58:
                    style = "DJ_FILTER" if filter_ok and overlap_vocal_clash < 0.74 else None
                elif style == "DJ_FILTER" and overlap_vocal_clash >= 0.74:
                    style = None

                minimum_beats = 8 if style == "DJ_BLEND" else 4
                if style is not None and actual_beats >= minimum_beats and span_fit >= 0.45:
                    score = (
                        0.15 + 0.20 * tempo + 0.12 * conf + 0.18 * key_fit
                        + 0.11 * (1.0 - overlap_vocal_clash)
                        + 0.08 * phrase_fit
                        + 0.06 * energy_fit
                        + 0.10 * pair_fit
                    )
                    if protected and style == "DJ_BLEND":
                        score -= 0.16
                    candidates.append(_candidate_plan(
                        style,
                        score,
                        "server-paired-beat-key-bridge" if style == "DJ_BLEND" else "server-paired-filtered-bridge",
                        transitionStart=round(start, 4),
                        transitionEnd=round(a_end, 4),
                        incomingCueTime=round(cue, 4),
                        incomingHandoffTime=round(cue, 4),
                        outgoingPlaybackRate=round(bridge_out_rate, 5),
                        incomingPlaybackRate=round(bridge_in_rate, 5),
                        transitionBeats=actual_beats,
                        requestedTransitionBeats=target_beats,
                        handoffFraction=round(
                            _clamp(
                                release_fraction,
                                0.52 if style == "DJ_BLEND" else 0.42,
                                0.92 if style == "DJ_BLEND" else 0.84,
                            ),
                            4,
                        ),
                        bassSwap=bool(_low_curve(a) and _low_curve(b)),
                        bassSwapFraction=round(
                            _clamp(
                                release_fraction,
                                0.52 if style == "DJ_BLEND" else 0.46,
                                0.92 if style == "DJ_BLEND" else 0.84,
                            ),
                            4,
                        ),
                        filterSweep=0.0 if style == "DJ_BLEND" else 0.72,
                        keyCompatibility=round(key_fit, 4),
                        tempoCompatibility=round(tempo, 4),
                        phraseAlignment=round(phrase_fit, 4),
                        overlapVocalClash=round(overlap_vocal_clash, 4),
                        energyCompatibility=round(energy_fit, 4),
                        pairCompatibility=round(pair_fit, 4),
                        spanCompatibility=round(span_fit, 4),
                        outgoingAnchor=str(pair["outgoingAnchor"]),
                        incomingAnchor=str(pair["incomingAnchor"]),
                        gainEnvelope=[],
                    ))

    # 2) EQ swap uses the same paired phrase search, but requires low-band evidence on both tracks.
    if (
        a_end > 0.0
        and key_evidence
        and tempo_bridge_ok
        and tempo >= 0.68
        and conf >= 0.38
        and key_fit >= 0.58
        and _low_curve(a)
        and _low_curve(b)
    ):
        desired_cue = max(b_start, _finite(b.get("mixInTime"), b_start))
        pair = _best_structural_pair(
            a,
            b,
            a_release,
            a_end,
            b_start,
            b_end,
            desired_cue,
            16,
            a_bpm,
            b_bpm,
            bridge_in_rate,
        )
        if pair is not None:
            start = float(pair["start"])
            cue = float(pair["cue"])
            phrase_fit = float(pair["phraseAlignment"])
            overlap_vocal_clash = float(pair["vocalClash"])
            energy_fit = float(pair["energyFit"])
            span_fit = float(pair["spanFit"])
            pair_fit = float(pair["pairScore"])
            actual_beats = int(round(float(pair["actualBeats"])))
            release_fraction = float(pair["releaseFraction"])

            eq_score = (
                0.17 + 0.19 * tempo + 0.11 * conf + 0.17 * key_fit
                + 0.11 * (1.0 - overlap_vocal_clash)
                + 0.08 * phrase_fit
                + 0.06 * energy_fit
                + 0.11 * pair_fit
            )
            if protected:
                eq_score -= 0.16

            if overlap_vocal_clash < 0.62 and actual_beats >= 8 and span_fit >= 0.45:
                candidates.append(_candidate_plan(
                    "EQ_SWAP",
                    eq_score,
                    "server-paired-eq-swap",
                    transitionStart=round(start, 4),
                    transitionEnd=round(a_end, 4),
                    incomingCueTime=round(cue, 4),
                    incomingHandoffTime=round(cue, 4),
                    outgoingPlaybackRate=round(bridge_out_rate, 5),
                    incomingPlaybackRate=round(bridge_in_rate, 5),
                    transitionBeats=actual_beats,
                    requestedTransitionBeats=16,
                    handoffFraction=round(_clamp(release_fraction, 0.50, 0.90), 4),
                    bassSwap=True,
                    bassSwapFraction=round(_clamp(release_fraction, 0.50, 0.90), 4),
                    filterSweep=0.0,
                    phraseAlignment=round(phrase_fit, 4),
                    overlapVocalClash=round(overlap_vocal_clash, 4),
                    energyCompatibility=round(energy_fit, 4),
                    pairCompatibility=round(pair_fit, 4),
                    spanCompatibility=round(span_fit, 4),
                    outgoingAnchor=str(pair["outgoingAnchor"]),
                    incomingAnchor=str(pair["incomingAnchor"]),
                    gainEnvelope=[],
                ))

    # 3) Phrase cut: a deliberate phrase/downbeat transfer, not a tiny crossfade.
    # Both sides must expose a usable structural point close to the intended handoff.
    strong_entry = max(b_start, _finite(b.get("mixInTime"), b_impact))
    if b_end > 0.0 and strong_entry < b_end - 0.25 and a_end > 0.0:
        skipped = max(0.0, strong_entry - b_start)
        long_intro_penalty = _clamp((skipped - 8.0) / 24.0, 0.0, 1.0)
        phrase_need = _clamp(0.55 * vocal_clash + 0.45 * (1.0 - tempo), 0.0, 1.0)
        beat = 60.0 / a_bpm if a_bpm > 0.0 else 0.70
        desired_span = _clamp(beat, 0.45, 1.15)
        desired_start = max(0.0, a_end - desired_span)
        phrase_start, outgoing_phrase_fit = _structural_snap(
            a,
            desired_start,
            max(0.0, a_end - 2.5 * beat),
            max(0.0, a_end - 0.10),
            beat,
        )
        phrase_cue, incoming_phrase_fit = _structural_snap(
            b,
            strong_entry,
            b_start,
            max(b_start, b_end - 0.25),
            60.0 / b_bpm if b_bpm > 0.0 else beat,
        )
        phrase_fit = min(outgoing_phrase_fit, incoming_phrase_fit)
        phrase_score = (
            0.18 + 0.24 * phrase_need + 0.10 * b_open_activity +
            0.18 * phrase_fit + 0.08 * key_fit - 0.40 * long_intro_penalty
        )
        if skipped <= 8.0 and phrase_fit >= 0.70 and phrase_score >= 0.34:
            candidates.append(_candidate_plan(
                "PHRASE_CUT", phrase_score, "server-phrase-handoff",
                transitionStart=round(phrase_start, 4), transitionEnd=round(a_end, 4),
                incomingCueTime=round(phrase_cue, 4), incomingHandoffTime=round(phrase_cue, 4),
                outgoingPlaybackRate=1.0, incomingPlaybackRate=1.0,
                transitionBeats=1,
                handoffFraction=0.68, bassSwap=False, bassSwapFraction=0.70,
                filterSweep=0.0,
                keyCompatibility=round(key_fit, 4),
                tempoCompatibility=round(tempo, 4),
                phraseAlignment=round(phrase_fit, 4),
                gainEnvelope=[],
            ))

    # 4) Clean structural handoff. CUT is intentionally conservative: if neither side exposes
    # a nearby phrase/downbeat, natural playback is better than manufacturing a micro-crossfade.
    if a_end > 0.0:
        beat = 60.0 / a_bpm if a_bpm > 0.0 else 0.55
        desired_start = max(0.0, a_end - _clamp(beat, 0.35, 0.90))
        cut_start, outgoing_cut_fit = _structural_snap(
            a,
            desired_start,
            max(0.0, a_end - 2.5 * beat),
            max(0.0, a_end - 0.08),
            beat,
        )
        cut_cue, incoming_cut_fit = _structural_snap(
            b,
            b_start,
            b_start,
            max(b_start, min(b_end - 0.25 if b_end > 0.25 else b_start, b_start + 2.5 * beat)),
            60.0 / b_bpm if b_bpm > 0.0 else beat,
        )
        cut_structure = min(outgoing_cut_fit, incoming_cut_fit)
        cut_score = (
            0.20 + (0.16 if protected else 0.06) +
            0.22 * cut_structure + 0.08 * b_open_vocal + 0.05 * b_open_activity
        )
        if cut_structure >= 0.70:
            candidates.append(_candidate_plan(
                "CUT", cut_score, "server-protected-handoff" if protected else "server-clean-handoff",
                transitionStart=round(cut_start, 4), transitionEnd=round(a_end, 4),
                incomingCueTime=round(cut_cue, 4), incomingHandoffTime=round(cut_cue, 4),
                outgoingPlaybackRate=1.0, incomingPlaybackRate=1.0,
                transitionBeats=1,
                handoffFraction=0.86, bassSwap=False, bassSwapFraction=0.70,
                filterSweep=0.0,
                keyCompatibility=round(key_fit, 4),
                tempoCompatibility=round(tempo, 4),
                phraseAlignment=round(cut_structure, 4),
                gainEnvelope=[],
            ))

    # 5) Doing nothing is a first-class Automix decision. Weak beat evidence, harmonic
    # conflict or a vocal collision can make natural playback better than an artificial blend.
    if a_end > 0.0:
        weak_evidence = 1.0 - _clamp(0.55 * conf + 0.45 * tempo, 0.0, 1.0)
        no_mix_score = (
            0.34 + 0.28 * weak_evidence + 0.18 * (1.0 - key_fit) +
            0.12 * vocal_clash + (0.10 if not key_evidence else 0.0)
        )
        candidates.append(_candidate_plan(
            "NO_TRANSITION", no_mix_score, "server-no-transition",
            transitionStart=round(a_end, 4), transitionEnd=round(a_end, 4),
            incomingCueTime=round(b_start, 4), incomingHandoffTime=round(b_start, 4),
            outgoingPlaybackRate=1.0, incomingPlaybackRate=1.0,
            transitionBeats=0,
            handoffFraction=1.0, bassSwap=False, bassSwapFraction=0.70,
            filterSweep=0.0,
            keyCompatibility=round(key_fit, 4),
            tempoCompatibility=round(tempo, 4),
            gainEnvelope=[],
        ))

    if not candidates:
        return _candidate_plan(
            "NO_TRANSITION", 0.50, "server-minimal-no-transition",
            transitionStart=a_end, transitionEnd=a_end,
            incomingCueTime=b_start, incomingHandoffTime=b_start,
            outgoingPlaybackRate=1.0, incomingPlaybackRate=1.0,
            handoffFraction=0.50, bassSwap=False, bassSwapFraction=0.70,
            filterSweep=0.0, gainEnvelope=[],
        ), []

    candidates.sort(key=lambda x: x["score"], reverse=True)
    best = candidates[0]
    second = candidates[1]["score"] if len(candidates) > 1 else 0.0
    # Confidence describes confidence in the *choice among candidates*, not analysis quality alone.
    confidence = _clamp(0.48 + 0.36 * best["score"] + 0.28 * max(0.0, best["score"] - second), 0.0, 0.99)
    best = dict(best)
    best["confidence"] = round(confidence, 4)
    best["protectedOutgoing"] = protected
    best["outgoingReleaseTime"] = round(a_release, 4)
    best["incomingImpactTime"] = round(b_impact, 4)
    best["incomingAudibleStartTime"] = round(b_audible_start, 4)
    best["keyCompatibility"] = round(key_fit, 4)
    best["tempoCompatibility"] = round(tempo, 4)
    best["planner"] = "orb-server-authoritative-v5"
    best["serverAuthoritative"] = True
    return best, candidates[:5]


@router.post("/plan")
async def plan(request: PlanRequest) -> dict[str, Any]:
    if request.version > API_VERSION:
        raise HTTPException(status_code=409, detail="unsupported Automix protocol version")
    outgoing = _plan_track(request.outgoing)
    incoming = _plan_track(request.incoming)
    plan_result, candidates = _remote_plan(outgoing, incoming)
    # The selected recipe is a command from the musical planner, not a style hint. Even the
    # minimal fallback is selected here on the server; Android may only reject impossible bounds.
    plan_result = dict(plan_result)
    plan_result["serverAuthoritative"] = True
    plan_result["planner"] = "orb-server-authoritative-v5"
    return {
        "version": API_VERSION,
        "authority": "server",
        "style": plan_result.get("style", "NO_TRANSITION"),
        "reason": plan_result.get("reason", "server-plan"),
        "plan": plan_result,
        "candidates": [
            {"style": c.get("style"), "score": c.get("score"), "reason": c.get("reason")}
            for c in candidates
        ],
    }

