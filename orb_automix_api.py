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

try:
    import librosa
except Exception:  # Optional at runtime: native Orb DSP remains a safe fallback.
    librosa = None

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

from billing_api import premium_entitled_for_account_hash

router = APIRouter(prefix="/api/automix", tags=["automix"])

API_VERSION = 7
# Schema 4 promotes Librosa to the primary rhythm tracker. Orb's autocorrelation
# tracker is now fallback-only, and local tempo is derived from the accepted beat grid.
ANALYSIS_SCHEMA = 4
SAMPLE_RATE = int(os.getenv("AUTOMIX_SAMPLE_RATE", "22050"))
MAX_UPLOAD_BYTES = int(os.getenv("AUTOMIX_MAX_UPLOAD_BYTES", str(24 * 1024 * 1024)))
MAX_DURATION_SECONDS = float(os.getenv("AUTOMIX_MAX_DURATION_SECONDS", "900"))
MAX_CACHE_ENTRIES = int(os.getenv("AUTOMIX_CACHE_ENTRIES", "512"))
MAX_CONCURRENT_ANALYSES = max(1, int(os.getenv("AUTOMIX_MAX_CONCURRENT", "2")))

# Temporary 2.5 beta entitlement. Only hashes are stored in source/config; the
# app never sends the account email to Automix. Future Premium subscriptions
# should extend this check from the billing entitlement store.
_AUTOMIX25_OWNER_HASH = "2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb"
AUTOMIX25_BETA_HASHES = {
    value.strip().lower()
    for value in os.getenv("AUTOMIX25_BETA_HASHES", _AUTOMIX25_OWNER_HASH).split(",")
    if value.strip()
}

_analysis_slots = asyncio.Semaphore(MAX_CONCURRENT_ANALYSES)
_cache_lock = threading.Lock()
_analysis_cache: OrderedDict[str, dict[str, Any]] = OrderedDict()

_plan_cache_lock = threading.Lock()
_plan_cache: OrderedDict[str, tuple[dict[str, Any], list[dict[str, Any]]]] = OrderedDict()
MAX_PLAN_CACHE_ENTRIES = 96


class PlanRequest(BaseModel):
    version: int = API_VERSION
    preview: bool = False
    accountHash: str = ""
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
        if value is not None and int(_finite(value.get("analysisSchema"), 0)) != ANALYSIS_SCHEMA:
            _analysis_cache.pop(track_id, None)
            return None
        if value is not None:
            _analysis_cache.move_to_end(track_id)
        return value


def _cache_put(track_id: str, value: dict[str, Any]) -> None:
    with _cache_lock:
        _analysis_cache[track_id] = value
        _analysis_cache.move_to_end(track_id)
        while len(_analysis_cache) > MAX_CACHE_ENTRIES:
            _analysis_cache.popitem(last=False)


def _plan_cache_key(outgoing: dict[str, Any], incoming: dict[str, Any]) -> str:
    # Full schema-4 analysis is stable for a track/rendition. Include the
    # transition anchors/BPM so a refreshed analysis cannot accidentally reuse
    # an older recipe for the same id.
    def fingerprint(track: dict[str, Any]) -> str:
        return "|".join([
            str(track.get("trackId") or ""),
            str(int(_finite(track.get("analysisSchema"), 0))),
            f"{_finite(track.get('bpm')):.4f}",
            f"{_finite(track.get('mixInTime')):.3f}",
            f"{_finite(track.get('mixOutTime')):.3f}",
            f"{_finite(track.get('contentEndTime')):.3f}",
        ])
    return f"mix-v9::{fingerprint(outgoing)}>>{fingerprint(incoming)}"


def _plan_cache_get(key: str) -> tuple[dict[str, Any], list[dict[str, Any]]] | None:
    with _plan_cache_lock:
        cached = _plan_cache.get(key)
        if cached is not None:
            _plan_cache.move_to_end(key)
            return dict(cached[0]), [dict(item) for item in cached[1]]
        return None


def _plan_cache_put(
    key: str,
    plan: dict[str, Any],
    candidates: list[dict[str, Any]],
) -> None:
    with _plan_cache_lock:
        _plan_cache[key] = (dict(plan), [dict(item) for item in candidates])
        _plan_cache.move_to_end(key)
        while len(_plan_cache) > MAX_PLAN_CACHE_ENTRIES:
            _plan_cache.popitem(last=False)


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


def _spectral_transition_features(
    frames: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """One FFT pass for the time-varying timbral/harmonic curves used at the join.

    Returns raw low/mid/high band power, perceptual brightness 0..1, and a
    frame-by-frame 12-bin pitch-class profile. The curves are intentionally
    transition metadata, not PCM; they are cheap to cache and compare.
    """
    window = np.hanning(frames.shape[1]).astype(np.float32)
    spec = np.abs(np.fft.rfft(frames * window, axis=1)) ** 2
    freqs = np.fft.rfftfreq(frames.shape[1], 1.0 / SAMPLE_RATE)

    def band(lo_hz: float, hi_hz: float) -> np.ndarray:
        mask = (freqs >= lo_hz) & (freqs < hi_hz)
        if not np.any(mask):
            return np.zeros(frames.shape[0], dtype=np.float64)
        return np.mean(spec[:, mask], axis=1)

    low = band(35.0, 250.0)
    mid = band(250.0, 4000.0)
    high = band(4000.0, 9000.0)

    audible = (freqs >= 55.0) & (freqs <= 12000.0)
    audible_spec = spec[:, audible]
    audible_freqs = freqs[audible]
    total = np.sum(audible_spec, axis=1) + 1e-12
    centroid = np.sum(audible_spec * audible_freqs[None, :], axis=1) / total
    # Log-frequency brightness is much closer to perception than raw Hz.
    brightness = np.clip(
        np.log2(np.maximum(centroid, 80.0) / 80.0) / np.log2(12000.0 / 80.0),
        0.0,
        1.0,
    )

    chroma = np.zeros((frames.shape[0], 12), dtype=np.float64)
    tonal_mask = (freqs >= 55.0) & (freqs <= 5000.0)
    tonal_freqs = freqs[tonal_mask]
    tonal_spec = np.sqrt(np.maximum(spec[:, tonal_mask], 0.0))
    if tonal_freqs.size:
        midi = np.rint(69.0 + 12.0 * np.log2(tonal_freqs / 440.0)).astype(np.int32)
        pitch_classes = np.mod(midi, 12)
        for pitch_class in range(12):
            bins = pitch_classes == pitch_class
            if np.any(bins):
                chroma[:, pitch_class] = np.sum(tonal_spec[:, bins], axis=1)
        chroma_sum = np.sum(chroma, axis=1, keepdims=True)
        chroma = np.divide(
            chroma,
            chroma_sum,
            out=np.zeros_like(chroma),
            where=chroma_sum > 1e-9,
        )

    return (
        low.astype(np.float64),
        mid.astype(np.float64),
        high.astype(np.float64),
        brightness.astype(np.float64),
        chroma,
    )


def _tempo_curve(
    onset: np.ndarray,
    hop_seconds: float,
    duration: float,
) -> list[dict[str, float]]:
    """Sliding local-tempo curve so a join follows tempo drift, not one catalog BPM."""
    if duration <= 0.0 or onset.size < 16:
        return []
    window_seconds = 18.0
    step_seconds = 6.0
    result: list[dict[str, float]] = []
    center = min(window_seconds / 2.0, duration / 2.0)
    while center <= duration:
        start = max(0.0, center - window_seconds / 2.0)
        end = min(duration, center + window_seconds / 2.0)
        lo = max(0, int(math.floor(start / hop_seconds)))
        hi = min(onset.size, int(math.ceil(end / hop_seconds)))
        if hi - lo >= 16:
            bpm, confidence, _ = _beat_grid(onset[lo:hi], hop_seconds)
            if 40.0 <= bpm <= 220.0 and confidence >= 0.12:
                result.append({
                    "time": round(center, 3),
                    "bpm": round(bpm, 5),
                    "confidence": round(confidence, 5),
                })
        center += step_seconds
    return result


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



def _beat_grid_support(
    beats: list[float],
    onset: np.ndarray,
    hop_seconds: float,
) -> float:
    """How strongly a proposed beat grid is supported by the actual onset envelope."""
    if len(beats) < 4 or onset.size == 0 or hop_seconds <= 0.0:
        return 0.0
    peak = float(np.percentile(onset, 95)) if onset.size else 0.0
    if peak <= 1e-9:
        return 0.0
    strengths: list[float] = []
    for beat in beats:
        center = int(round(beat / hop_seconds))
        lo = max(0, center - 1)
        hi = min(onset.size, center + 2)
        if hi > lo:
            strengths.append(float(np.max(onset[lo:hi])) / peak)
    if not strengths:
        return 0.0
    intervals = np.diff(np.asarray(beats, dtype=np.float64))
    regularity = 1.0
    if intervals.size >= 3:
        median = float(np.median(intervals))
        if median > 1e-6:
            deviation = float(np.median(np.abs(intervals - median))) / median
            regularity = _clamp(1.0 - deviation / 0.12, 0.0, 1.0)
    return _clamp(0.72 * float(np.mean(np.clip(strengths, 0.0, 1.0))) + 0.28 * regularity, 0.0, 1.0)


def _fold_tempo_and_grid(bpm: float, beats: list[float]) -> tuple[float, list[float]]:
    """Fold half/double-time estimates into Orb's musical range while keeping a usable grid."""
    if bpm <= 0.0 or len(beats) < 2:
        return bpm, beats
    result = list(beats)
    while bpm < 70.0:
        bpm *= 2.0
        # Insert the halfway beat so the grid follows the doubled tempo.
        expanded: list[float] = []
        for left, right in zip(result, result[1:], strict=False):
            expanded.extend([left, (left + right) * 0.5])
        expanded.append(result[-1])
        result = expanded
    while bpm > 190.0:
        bpm /= 2.0
        result = result[::2]
    return bpm, result


def _librosa_beat_grid(
    onset_env: np.ndarray,
    hop_seconds: float,
) -> tuple[float, float, list[float]]:
    """Librosa beat-tracker second opinion over Orb's already-computed onset envelope.

    Recomputing a second full STFT/onset-strength pass over every song roughly doubled
    server analysis time. The expensive acoustic evidence is already present here;
    Librosa now contributes the independent *tracker* over the same envelope.
    """
    if librosa is None or onset_env.size < 16 or hop_seconds <= 0.0:
        return 0.0, 0.0, []
    try:
        hop_length = max(1, int(round(hop_seconds * SAMPLE_RATE)))
        if float(np.max(onset_env)) <= 1e-8:
            return 0.0, 0.0, []
        tempo, beat_frames = librosa.beat.beat_track(
            onset_envelope=np.asarray(onset_env, dtype=np.float32),
            sr=SAMPLE_RATE,
            hop_length=hop_length,
            trim=False,
            sparse=True,
        )
        bpm = float(np.asarray(tempo).reshape(-1)[0]) if np.asarray(tempo).size else 0.0
        beats = [
            float(value)
            for value in librosa.frames_to_time(
                np.asarray(beat_frames, dtype=np.int64),
                sr=SAMPLE_RATE,
                hop_length=hop_length,
            ).tolist()
        ]
        bpm, beats = _fold_tempo_and_grid(bpm, beats)
        if not (40.0 <= bpm <= 220.0) or len(beats) < 4:
            return 0.0, 0.0, []

        # Librosa exposes the grid but not a single confidence scalar. Build one
        # from onset support plus interval regularity, keeping it intentionally
        # bounded so agreement with Orb can increase confidence rather than letting
        # one library become absolute authority.
        peak = float(np.percentile(onset_env, 95)) + 1e-9
        beat_strengths = []
        for frame in np.asarray(beat_frames, dtype=np.int64):
            lo = max(0, int(frame) - 1)
            hi = min(onset_env.size, int(frame) + 2)
            if hi > lo:
                beat_strengths.append(float(np.max(onset_env[lo:hi])) / peak)
        intervals = np.diff(np.asarray(beats, dtype=np.float64))
        regularity = 0.0
        if intervals.size >= 3:
            median = float(np.median(intervals))
            if median > 1e-6:
                mad = float(np.median(np.abs(intervals - median))) / median
                regularity = _clamp(1.0 - mad / 0.12, 0.0, 1.0)
        onset_support = float(np.mean(np.clip(beat_strengths, 0.0, 1.0))) if beat_strengths else 0.0
        confidence = _clamp(0.35 + 0.35 * onset_support + 0.30 * regularity, 0.0, 0.90)
        return bpm, confidence, beats
    except Exception:
        return 0.0, 0.0, []


def _primary_beat_grid(
    onset: np.ndarray,
    hop_seconds: float,
) -> tuple[float, float, list[float], str]:
    """Use Librosa as the primary rhythm tracker; run Orb only as a true fallback.

    The expensive onset evidence is shared. A healthy Librosa result is accepted
    directly and the native autocorrelation tracker is never executed for that
    track. Orb is invoked only when Librosa is absent, fails, or returns a grid
    with too little support in the actual onset envelope.
    """
    lib_bpm, lib_conf, lib_beats = _librosa_beat_grid(onset, hop_seconds)
    lib_valid = 40.0 <= lib_bpm <= 220.0 and len(lib_beats) >= 4
    if lib_valid:
        support = _beat_grid_support(lib_beats, onset, hop_seconds)
        if support >= 0.16:
            confidence = _clamp(0.72 * lib_conf + 0.28 * support, 0.0, 0.95)
            return lib_bpm, confidence, lib_beats, "librosa-primary"

    # Only now pay for the native autocorrelation pass.
    native_bpm, native_conf, native_beats = _beat_grid(onset, hop_seconds)
    native_valid = 40.0 <= native_bpm <= 220.0 and len(native_beats) >= 4
    if native_valid:
        return native_bpm, native_conf, native_beats, "orb-fallback"

    # Preserve the least-bad Librosa evidence for diagnostics, but do not invent
    # a usable rhythm when both trackers failed.
    if lib_valid:
        return lib_bpm, _clamp(lib_conf * 0.70, 0.0, 0.65), lib_beats, "librosa-weak"
    return 0.0, 0.0, [], "none"


def _tempo_from_beats(
    beats: list[float],
    start_s: float,
    end_s: float,
    fallback_bpm: float = 0.0,
    fallback_confidence: float = 0.0,
) -> tuple[float, float]:
    """Derive local BPM from the accepted beat grid without another tracker pass."""
    if end_s <= start_s or len(beats) < 3:
        return fallback_bpm, fallback_confidence

    local = [beat for beat in beats if start_s - 0.10 <= beat <= end_s + 0.10]
    if len(local) < 3:
        return fallback_bpm, fallback_confidence

    intervals = np.diff(np.asarray(local, dtype=np.float64))
    intervals = intervals[(intervals > 0.15) & (intervals < 2.0)]
    if intervals.size < 2:
        return fallback_bpm, fallback_confidence

    median = float(np.median(intervals))
    if median <= 1e-6:
        return fallback_bpm, fallback_confidence

    bpm = 60.0 / median
    while bpm < 70.0:
        bpm *= 2.0
    while bpm > 190.0:
        bpm /= 2.0
    if not 40.0 <= bpm <= 220.0:
        return fallback_bpm, fallback_confidence

    deviation = float(np.median(np.abs(intervals - median))) / median
    regularity = _clamp(1.0 - deviation / 0.10, 0.0, 1.0)
    coverage = _clamp(intervals.size / max(4.0, (end_s - start_s) * bpm / 60.0), 0.0, 1.0)
    confidence = _clamp(0.55 * regularity + 0.45 * coverage, 0.0, 0.98)
    return bpm, confidence


def _tempo_curve_from_beats(
    beats: list[float],
    duration: float,
    fallback_bpm: float,
    fallback_confidence: float,
) -> list[dict[str, float]]:
    """Cheap local-tempo curve derived from Librosa's accepted beat grid."""
    if duration <= 0.0 or len(beats) < 3:
        return []
    window_seconds = 18.0
    step_seconds = 6.0
    result: list[dict[str, float]] = []
    center = min(window_seconds / 2.0, duration / 2.0)
    while center <= duration:
        start = max(0.0, center - window_seconds / 2.0)
        end = min(duration, center + window_seconds / 2.0)
        bpm, confidence = _tempo_from_beats(
            beats,
            start,
            end,
            fallback_bpm=fallback_bpm,
            fallback_confidence=fallback_confidence,
        )
        if 40.0 <= bpm <= 220.0 and confidence >= 0.12:
            result.append({
                "time": round(center, 3),
                "bpm": round(bpm, 5),
                "confidence": round(confidence, 5),
            })
        center += step_seconds
    return result


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
    low_raw, mid_raw, high_raw, brightness, chroma = _spectral_transition_features(frames)
    low = _normalized(low_raw)
    mid = _normalized(mid_raw)
    high = _normalized(high_raw)
    vocal_raw = mid_raw / (mid_raw + 0.7 * high_raw + 1e-9)
    vocal = _normalized(vocal_raw) * np.clip(energy * 1.25, 0.0, 1.0)
    vocal_probability = float(np.clip(np.mean(vocal[energy > 0.12]) if np.any(energy > 0.12) else 0.0, 0.0, 1.0))

    # Finer 100 ms onset envelope for tempo.
    beat_frame = max(512, int(0.10 * SAMPLE_RATE))
    beat_frames = _frame_signal(audio, beat_frame, beat_frame)
    beat_rms = np.sqrt(np.mean(beat_frames.astype(np.float64) ** 2, axis=1) + 1e-12)
    onset = np.maximum(0.0, np.diff(np.log1p(beat_rms * 1000.0), prepend=0.0))
    bpm, beat_conf, beats, beat_source = _primary_beat_grid(
        onset,
        beat_frame / SAMPLE_RATE,
    )
    beat_interval = 60.0 / bpm if bpm > 0 else 0.0
    tempo_curve = _tempo_curve_from_beats(
        beats,
        duration,
        fallback_bpm=bpm,
        fallback_confidence=beat_conf,
    )

    # Coarse transient curve on the same 500 ms timeline as the spectral curves.
    # The beat grid stays at 100 ms; this curve is for A↔B contour matching, not beat detection.
    onset_curve = np.zeros_like(energy, dtype=np.float64)
    fine_hop = beat_frame / SAMPLE_RATE
    for index, time_s in enumerate(times):
        lo = max(0, int(math.floor(time_s / fine_hop)))
        hi = min(onset.size, int(math.ceil((time_s + hop / SAMPLE_RATE) / fine_hop)))
        if hi > lo:
            onset_curve[index] = float(np.max(onset[lo:hi]))
    onset_curve = _normalized(onset_curve)

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

    # Local tempo comes directly from the already accepted Librosa beat grid.
    # No second/third beat-tracking pass is needed for intro and outro.
    tempo_window = min(30.0, max(12.0, duration * 0.22))
    head_bpm, head_beat_conf = _tempo_from_beats(
        beats,
        audible_start,
        min(content_end, audible_start + tempo_window),
        fallback_bpm=bpm,
        fallback_confidence=beat_conf,
    )
    tail_bpm, tail_beat_conf = _tempo_from_beats(
        beats,
        max(audible_start, content_end - tempo_window),
        content_end,
        fallback_bpm=bpm,
        fallback_confidence=beat_conf,
    )

    # Structure anchors from sustained changes in the normalized energy envelope.
    intro_limit = min(len(energy), max(1, int(45.0 / (hop / SAMPLE_RATE))))
    intro_candidates = np.flatnonzero(energy[:intro_limit] >= 0.35)
    intro_end = float(times[intro_candidates[0]]) if intro_candidates.size else min(8.0, duration * 0.1)
    tail_start_idx = max(0, int(len(energy) * 0.55))
    tail = energy[tail_start_idx:]
    quiet_tail = np.flatnonzero(tail <= 0.35)
    outro_start = float(times[tail_start_idx + quiet_tail[0]]) if quiet_tail.size else max(0.0, content_end - min(20.0, duration * 0.12))

    key, key_conf = _key_from_audio(audio)

    # Harmonic compatibility for a transition is local: A's ending meets B's opening.
    # Keep the global key for fallback/catalog display, but measure those two windows separately.
    key_window_seconds = min(24.0, max(8.0, duration * 0.18))
    key_window_samples = max(SAMPLE_RATE, int(round(key_window_seconds * SAMPLE_RATE)))
    head_start_sample = max(0, int(round(audible_start * SAMPLE_RATE)))
    head_end_sample = min(audio.size, head_start_sample + key_window_samples)
    tail_end_sample = min(audio.size, max(head_end_sample, int(round(content_end * SAMPLE_RATE))))
    tail_start_sample = max(0, tail_end_sample - key_window_samples)

    head_key, head_key_conf = _key_from_audio(audio[head_start_sample:head_end_sample])
    tail_key, tail_key_conf = _key_from_audio(audio[tail_start_sample:tail_end_sample])

    mix_in_candidates, mix_out_candidates = _candidate_points(times, energy, vocal, downbeats, duration)
    mix_in = float(mix_in_candidates[0]["time"]) if mix_in_candidates else intro_end
    mix_out = float(mix_out_candidates[0]["time"]) if mix_out_candidates else content_end

    # Smooth pitch-class vectors over ~2 seconds and emit them every 1 second.
    # This is stable enough to follow chord/key motion while staying compact in JSON.
    chroma_curve: list[dict[str, Any]] = []
    if chroma.size:
        radius = 2
        for index in range(0, chroma.shape[0], 2):
            lo = max(0, index - radius)
            hi = min(chroma.shape[0], index + radius + 1)
            vector = np.mean(chroma[lo:hi], axis=0)
            total = float(np.sum(vector))
            if total > 1e-9:
                vector = vector / total
            chroma_curve.append({
                "time": round(float(times[index]), 3),
                "chroma": [round(float(value), 5) for value in vector],
            })

    return {
        "analysisSchema": ANALYSIS_SCHEMA,
        "duration": round(duration, 4),
        "bpm": round(bpm, 5),
        "beatInterval": round(beat_interval, 6),
        "beatConfidence": round(beat_conf, 5),
        "beatEvidence": beat_source,
        "headBpm": round(head_bpm, 5),
        "headBeatConfidence": round(head_beat_conf, 5),
        "tailBpm": round(tail_bpm, 5),
        "tailBeatConfidence": round(tail_beat_conf, 5),
        "downbeats": [round(float(x), 4) for x in downbeats[:1024]],
        "phraseBoundaries": [round(float(x), 4) for x in phrases[:256]],
        "firstBeat": round(first_beat, 4),
        "key": key,
        "keyConfidence": round(key_conf, 5),
        "headKey": head_key,
        "headKeyConfidence": round(head_key_conf, 5),
        "tailKey": tail_key,
        "tailKeyConfidence": round(tail_key_conf, 5),
        "audibleStartTime": round(audible_start, 4),
        "pickupTime": round(first_beat or audible_start, 4),
        "introEndTime": round(intro_end, 4),
        "contentEndTime": round(content_end, 4),
        "outroStartTime": round(outro_start, 4),
        "mixInTime": round(mix_in, 4),
        "mixOutTime": round(mix_out, 4),
        "mixInCandidates": mix_in_candidates,
        "mixOutCandidates": mix_out_candidates,
        "beats": [round(float(x), 4) for x in beats[:4096]],
        "tempoCurve": tempo_curve,
        "energyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, energy, strict=False)
        ],
        "lowEnergyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, low, strict=False)
        ],
        "midEnergyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, mid, strict=False)
        ],
        "highEnergyCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, high, strict=False)
        ],
        "brightnessCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, brightness, strict=False)
        ],
        "onsetCurve": [
            {"time": round(float(t), 3), "energy": round(float(e), 5)}
            for t, e in zip(times, onset_curve, strict=False)
        ],
        "chromaCurve": chroma_curve,
        "vocalActivityMask": [round(float(x), 5) for x in vocal],
        "vocalProbability": round(vocal_probability, 5),
        "trackId": track_id,
    }


@router.get("/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "version": API_VERSION,
        "automixVersion": "2.5",
        "analyzer": "orb-remote-dsp-v8",
        "plannerRevision": "mix-v9",
        "analysisSchema": ANALYSIS_SCHEMA,
    }


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
        "midEnergyCurve",
        "highEnergyCurve",
        "brightnessCurve",
        "onsetCurve",
        "chromaCurve",
        "tempoCurve",
        "beats",
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
    for key in (
        "energyCurve",
        "lowEnergyCurve",
        "midEnergyCurve",
        "highEnergyCurve",
        "brightnessCurve",
        "onsetCurve",
        "chromaCurve",
        "tempoCurve",
        "beats",
        "phraseBoundaries",
        "mixInCandidates",
        "mixOutCandidates",
    ):
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


def _tempo_points(track: dict[str, Any]) -> list[tuple[float, float, float]]:
    raw = track.get("tempoCurve")
    if not isinstance(raw, list):
        return []
    points: list[tuple[float, float, float]] = []
    for point in raw:
        if not isinstance(point, dict):
            continue
        time_s = _finite(point.get("time"), float("nan"))
        bpm = _finite(point.get("bpm"), float("nan"))
        confidence = _clamp(_finite(point.get("confidence"), 0.0), 0.0, 1.0)
        if math.isfinite(time_s) and 40.0 <= bpm <= 220.0:
            points.append((max(0.0, time_s), bpm, confidence))
    return points


def _tempo_near(track: dict[str, Any], time_s: float, fallback: float) -> tuple[float, float]:
    points = _tempo_points(track)
    if not points:
        return fallback, 0.0
    point = min(points, key=lambda item: abs(item[0] - time_s))
    # A local estimate more than 12 s away is not really local to the join.
    if abs(point[0] - time_s) > 12.0 or point[2] < 0.18:
        return fallback, point[2]
    return point[1], point[2]


def _chroma_points(track: dict[str, Any]) -> list[tuple[float, np.ndarray]]:
    raw = track.get("chromaCurve")
    if not isinstance(raw, list):
        return []
    points: list[tuple[float, np.ndarray]] = []
    for point in raw:
        if not isinstance(point, dict):
            continue
        time_s = _finite(point.get("time"), float("nan"))
        values = point.get("chroma")
        if not math.isfinite(time_s) or not isinstance(values, list) or len(values) != 12:
            continue
        vector = np.asarray([max(0.0, _finite(value)) for value in values], dtype=np.float64)
        total = float(np.sum(vector))
        if total > 1e-9:
            vector /= total
        points.append((max(0.0, time_s), vector))
    return points


def _chroma_near(points: list[tuple[float, np.ndarray]], time_s: float) -> np.ndarray | None:
    if not points:
        return None
    return min(points, key=lambda item: abs(item[0] - time_s))[1]


def _curve_values_at(
    points: list[tuple[float, float]],
    times: np.ndarray,
    default: float = 0.5,
) -> np.ndarray:
    if not points or not times.size:
        return np.full(times.size, default, dtype=np.float64)
    point_times = np.asarray([time_s for time_s, _ in points], dtype=np.float64)
    values = np.asarray([value for _, value in points], dtype=np.float64)
    indices = np.searchsorted(point_times, times, side="left")
    indices = np.clip(indices, 0, len(point_times) - 1)
    previous = np.clip(indices - 1, 0, len(point_times) - 1)
    choose_previous = (
        np.abs(point_times[previous] - times)
        <= np.abs(point_times[indices] - times)
    )
    return values[np.where(choose_previous, previous, indices)]


def _shape_similarity(left: np.ndarray, right: np.ndarray) -> float:
    if left.size < 3 or right.size != left.size:
        return 0.5
    left_delta = np.diff(left)
    right_delta = np.diff(right)
    left_norm = float(np.linalg.norm(left_delta))
    right_norm = float(np.linalg.norm(right_delta))
    if left_norm <= 1e-9 or right_norm <= 1e-9:
        return _clamp(1.0 - float(np.mean(np.abs(left - right))), 0.0, 1.0)
    cosine = float(np.dot(left_delta, right_delta) / (left_norm * right_norm))
    return _clamp((cosine + 1.0) * 0.5, 0.0, 1.0)


def _transition_curve_metrics(
    a: dict[str, Any],
    b: dict[str, Any],
    a_start: float,
    a_end: float,
    b_start: float,
    outgoing_rate: float,
    incoming_rate: float,
) -> dict[str, float | bool]:
    """Compare the actual time-varying audio contours that would overlap.

    The score deliberately separates three questions:
    - rhythm: do transient/onset contours land together?
    - harmony: do local pitch-class trajectories agree through the overlap?
    - spectrum/energy: will the combined low/mid/high balance stay controlled?
    Missing curve evidence is neutral and never fabricates confidence.
    """
    if a_end <= a_start:
        return {"evidence": False, "compatibility": 0.5}

    wall_duration = (a_end - a_start) / max(outgoing_rate, 1e-6)
    samples = 24
    progress = np.linspace(0.0, 1.0, samples, dtype=np.float64)
    a_times = a_start + progress * (a_end - a_start)
    b_times = b_start + progress * wall_duration * max(incoming_rate, 1e-6)

    a_energy_points = _curve(a, "energyCurve")
    b_energy_points = _curve(b, "energyCurve")
    spectral_names = (
        "lowEnergyCurve",
        "midEnergyCurve",
        "highEnergyCurve",
        "brightnessCurve",
    )
    has_spectral = all(_curve(a, name) and _curve(b, name) for name in spectral_names)
    has_onset = bool(_curve(a, "onsetCurve") and _curve(b, "onsetCurve"))
    a_chroma = _chroma_points(a)
    b_chroma = _chroma_points(b)
    has_chroma = bool(a_chroma and b_chroma)
    evidence = bool((a_energy_points and b_energy_points) and (has_spectral or has_onset or has_chroma))
    if not evidence:
        return {"evidence": False, "compatibility": 0.5}

    a_energy = _curve_values_at(a_energy_points, a_times)
    b_energy = _curve_values_at(b_energy_points, b_times)
    energy_shape = _shape_similarity(a_energy, b_energy)
    # Continuity at the authority handoff is more important than identical average loudness.
    handoff_index = samples // 2
    energy_continuity = _clamp(
        1.0 - abs(float(a_energy[handoff_index]) - float(b_energy[handoff_index])),
        0.0,
        1.0,
    )

    spectral_fit = 0.5
    low_collision = 0.0
    brightness_gap = 0.0
    if has_spectral:
        fits: list[float] = []
        for name in spectral_names:
            left = _curve_values_at(_curve(a, name), a_times)
            right = _curve_values_at(_curve(b, name), b_times)
            fits.append(_shape_similarity(left, right))
            if name == "lowEnergyCurve":
                low_collision = float(np.mean(np.minimum(left, right)))
            if name == "brightnessCurve":
                brightness_gap = float(np.mean(np.abs(left - right)))
        # Shape similarity plus a penalty when both kick/bass regions are simultaneously dense.
        spectral_fit = _clamp(
            0.78 * float(np.mean(fits))
            + 0.22 * (1.0 - _clamp(low_collision, 0.0, 1.0)),
            0.0,
            1.0,
        )

    onset_fit = 0.5
    if has_onset:
        a_onset = _curve_values_at(_curve(a, "onsetCurve"), a_times, 0.0)
        b_onset = _curve_values_at(_curve(b, "onsetCurve"), b_times, 0.0)
        a_norm = float(np.linalg.norm(a_onset))
        b_norm = float(np.linalg.norm(b_onset))
        if a_norm > 1e-9 and b_norm > 1e-9:
            onset_fit = _clamp(float(np.dot(a_onset, b_onset) / (a_norm * b_norm)), 0.0, 1.0)

    harmonic_fit = 0.5
    if has_chroma:
        similarities: list[float] = []
        for a_time, b_time in zip(a_times, b_times, strict=False):
            left = _chroma_near(a_chroma, float(a_time))
            right = _chroma_near(b_chroma, float(b_time))
            if left is None or right is None:
                continue
            denom = float(np.linalg.norm(left) * np.linalg.norm(right))
            if denom > 1e-9:
                similarities.append(_clamp(float(np.dot(left, right) / denom), 0.0, 1.0))
        if similarities:
            harmonic_fit = float(np.mean(similarities))

    compatibility = _clamp(
        0.24 * onset_fit
        + 0.24 * harmonic_fit
        + 0.20 * spectral_fit
        + 0.17 * energy_shape
        + 0.15 * energy_continuity,
        0.0,
        1.0,
    )
    return {
        "evidence": True,
        "compatibility": compatibility,
        "onsetFit": onset_fit,
        "harmonicFit": harmonic_fit,
        "spectralFit": spectral_fit,
        "energyShapeFit": energy_shape,
        "energyContinuity": energy_continuity,
        "lowCollision": _clamp(low_collision, 0.0, 1.0),
        "brightnessGap": _clamp(brightness_gap, 0.0, 1.0),
    }


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
    # Exclude the exact right edge. A sample at t=end has a zero-width
    # look-ahead window; feeding [default] back from that window can falsely
    # resurrect foreground vocals/energy after a genuine sustained release.
    # That bug made _release_landmarks() collapse to end-1.5 s on tracks whose
    # global vocalProbability was >= 0.48.
    times = [t for t, _ in points if start <= t < end]
    if not times:
        return default
    means = [
        _mean_window(points, t, min(end, t + window), default)
        for t in times
        if min(end, t + window) > t
    ]
    return max(means) if means else default


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


def _transition_bpm(track: dict[str, Any], side: str) -> float:
    if side == "outgoing":
        local_bpm = _finite(track.get("tailBpm"))
        local_conf = _clamp(_finite(track.get("tailBeatConfidence")), 0.0, 1.0)
    else:
        local_bpm = _finite(track.get("headBpm"))
        local_conf = _clamp(_finite(track.get("headBeatConfidence")), 0.0, 1.0)

    if 40.0 <= local_bpm <= 220.0 and local_conf >= 0.30:
        return local_bpm
    return _finite(track.get("bpm"))


def _tempo_pair(a: dict[str, Any], b: dict[str, Any]) -> tuple[float, float, float]:
    a_bpm = _transition_bpm(a, "outgoing")
    b_bpm = _transition_bpm(b, "incoming")
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


def _parse_trusted_key(
    raw_value: Any,
    confidence_value: Any,
) -> tuple[int, str] | None:
    if _clamp(_finite(confidence_value), 0.0, 1.0) < 0.25:
        return None
    raw = str(raw_value or "").strip().replace("♯", "#").replace("♭", "b")
    if not raw:
        return None
    parts = raw.split()
    tonic = parts[0].upper()
    index = _KEY_INDEX.get(tonic)
    if index is None:
        return None
    mode = parts[1].lower() if len(parts) > 1 else ""
    return index, mode


def _trusted_key(track: dict[str, Any]) -> tuple[int, str] | None:
    return _parse_trusted_key(track.get("key"), track.get("keyConfidence"))


def _transition_key(track: dict[str, Any], side: str) -> tuple[int, str] | None:
    if side == "outgoing":
        local = _parse_trusted_key(track.get("tailKey"), track.get("tailKeyConfidence"))
    else:
        local = _parse_trusted_key(track.get("headKey"), track.get("headKeyConfidence"))
    return local or _trusted_key(track)


def _key_compatibility(a: dict[str, Any], b: dict[str, Any]) -> float:
    """0..1 harmonic compatibility based on musical relationships, not semitone proximity.

    A key one semitone away is physically close on a keyboard but usually a poor place to leave
    two full arrangements open. Strong scores are reserved for relationships a DJ would actually
    use: same key, relative major/minor, and fourth/fifth in the same mode.
    Unknown/low-confidence keys stay neutral so they cannot manufacture a harmonic advantage.
    """
    left = _transition_key(a, "outgoing")
    right = _transition_key(b, "incoming")
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
        ("downbeats", 0.82, "downbeat"),
        # Full beat grid is lower-confidence than a phrase/downbeat, but gives the
        # planner sub-bar phase precision so kick/snare transients do not flam.
        ("beats", 0.66, "beat"),
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


def _beat_phase_metrics(
    a: dict[str, Any],
    b: dict[str, Any],
    a_time: float,
    b_time: float,
    a_bpm: float,
    b_bpm: float,
    a_rate: float,
    b_rate: float,
) -> tuple[float, float]:
    """Return beat-phase compatibility and absolute wall-clock phase error in ms."""
    def grid(track: dict[str, Any]) -> list[float]:
        raw = track.get("beats")
        if isinstance(raw, list) and raw:
            return [max(0.0, _finite(value)) for value in raw]
        raw = track.get("downbeats")
        return [max(0.0, _finite(value)) for value in raw] if isinstance(raw, list) else []

    left = grid(a)
    right = grid(b)
    if not left or not right or a_bpm <= 0.0 or b_bpm <= 0.0:
        return 0.5, 0.0

    a_anchor = min(left, key=lambda value: abs(value - a_time))
    b_anchor = min(right, key=lambda value: abs(value - b_time))
    a_error = (a_time - a_anchor) / max(a_rate, 1e-6)
    b_error = (b_time - b_anchor) / max(b_rate, 1e-6)
    error_seconds = abs(a_error - b_error)
    beat_seconds = min(
        60.0 / max(a_bpm * a_rate, 1e-6),
        60.0 / max(b_bpm * b_rate, 1e-6),
    )
    tolerance = max(0.025, 0.22 * beat_seconds)
    fit = _clamp(1.0 - error_seconds / tolerance, 0.0, 1.0)
    return fit, error_seconds * 1000.0



def _tempo_envelope_for_overlap(
    a: dict[str, Any],
    b: dict[str, Any],
    a_start: float,
    a_end: float,
    b_start: float,
    fallback_out_rate: float,
    fallback_in_rate: float,
) -> list[dict[str, float]]:
    """Build a progress-indexed meeting-tempo curve for the exact overlap.

    A/B are sampled on their own local tempo curves. The B probe advances in
    media time using the previous segment's rates, so the envelope follows
    drift/live edits instead of pretending the whole overlap has one BPM.
    """
    if a_end <= a_start:
        return []

    span = a_end - a_start
    b_end = _content_end(b)
    points: list[dict[str, float]] = []
    b_time = max(0.0, b_start)
    previous_a = a_start
    previous_out = _clamp(fallback_out_rate, 0.94, 1.06)
    previous_in = _clamp(fallback_in_rate, 0.94, 1.06)

    for progress in np.linspace(0.0, 1.0, 7, dtype=np.float64):
        a_time = a_start + float(progress) * span
        if points:
            delta_a = max(0.0, a_time - previous_a)
            wall_seconds = delta_a / max(previous_out, 1e-6)
            b_time += wall_seconds * previous_in
        if b_end > b_start:
            b_time = _clamp(b_time, b_start, max(b_start, b_end - 0.05))

        a_bpm, a_conf = _tempo_near(a, a_time, _finite(a.get("bpm"), 0.0))
        b_bpm, b_conf = _tempo_near(b, b_time, _finite(b.get("bpm"), 0.0))
        if a_bpm > 0.0 and b_bpm > 0.0:
            while b_bpm / a_bpm > 1.5:
                b_bpm /= 2.0
            while b_bpm / a_bpm < 0.67:
                b_bpm *= 2.0

        out_rate, in_rate = _tempo_bridge_rates(a_bpm, b_bpm)
        if (
            out_rate == 1.0
            and in_rate == 1.0
            and (abs(fallback_out_rate - 1.0) > 1e-4 or abs(fallback_in_rate - 1.0) > 1e-4)
            and min(a_conf, b_conf) < 0.20
        ):
            out_rate = fallback_out_rate
            in_rate = fallback_in_rate

        out_rate = _clamp(out_rate, 0.94, 1.06)
        in_rate = _clamp(in_rate, 0.94, 1.06)
        points.append({
            "progress": round(float(progress), 4),
            "outgoingRate": round(out_rate, 6),
            "incomingRate": round(in_rate, 6),
            "outgoingBpm": round(a_bpm, 5),
            "incomingBpm": round(b_bpm, 5),
            "confidence": round(_clamp(min(a_conf, b_conf), 0.0, 1.0), 4),
        })
        previous_a = a_time
        previous_out = out_rate
        previous_in = in_rate

    return points


def _harmonic_lock_shift(
    a: dict[str, Any],
    b: dict[str, Any],
    a_start: float,
    a_end: float,
    b_start: float,
    outgoing_rate: float,
    incoming_rate: float,
    vocal_clash: float = 0.0,
) -> tuple[float, float, float]:
    """Return (incoming semitone shift, locked fit, unshifted fit).

    Only -1/0/+1 semitone is considered. A pitch move is accepted only when
    the time-varying chroma evidence improves materially; otherwise selection
    remains the harmonic control and playback pitch stays untouched.
    """
    a_chroma = _chroma_points(a)
    b_chroma = _chroma_points(b)
    if not a_chroma or not b_chroma or a_end <= a_start:
        return 0.0, 0.5, 0.5

    wall_duration = (a_end - a_start) / max(outgoing_rate, 1e-6)
    progress = np.linspace(0.0, 1.0, 24, dtype=np.float64)
    a_times = a_start + progress * (a_end - a_start)
    b_times = b_start + progress * wall_duration * max(incoming_rate, 1e-6)

    fits: dict[int, float] = {}
    for shift in (-1, 0, 1):
        similarities: list[float] = []
        for a_time, b_time in zip(a_times, b_times, strict=False):
            left = _chroma_near(a_chroma, float(a_time))
            right = _chroma_near(b_chroma, float(b_time))
            if left is None or right is None:
                continue
            shifted = np.roll(right, shift)
            denom = float(np.linalg.norm(left) * np.linalg.norm(shifted))
            if denom > 1e-9:
                similarities.append(
                    _clamp(float(np.dot(left, shifted) / denom), 0.0, 1.0)
                )
        if similarities:
            fits[shift] = float(np.mean(similarities))

    if 0 not in fits:
        return 0.0, 0.5, 0.5

    base = fits[0]
    best_shift = max(fits, key=lambda shift: fits[shift] - 0.035 * abs(shift))
    best_fit = fits[best_shift]
    required_gain = 0.10 + 0.08 * _clamp(vocal_clash, 0.0, 1.0)

    if (
        best_shift != 0
        and best_fit >= 0.68
        and best_fit - base >= required_gain
    ):
        return float(best_shift), best_fit, base
    return 0.0, base, base


def _curve_lock_recipe(
    a: dict[str, Any],
    b: dict[str, Any],
    plan: dict[str, Any],
) -> dict[str, Any]:
    """Turn analysis curves into executable tempo/phase/harmonic lock data."""
    style = str(plan.get("style") or "")
    if style not in {"RUNWAY_BLEND", "PHRASE_TAKEOVER", "DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}:
        return {
            "tempoEnvelope": [],
            "incomingPitchSemitones": 0.0,
            "harmonicLockScore": 0.0,
        }

    a_start = _finite(plan.get("transitionStart"), 0.0)
    a_end = _finite(plan.get("transitionEnd"), a_start)
    b_start = _finite(plan.get("incomingCueTime"), 0.0)
    out_rate = _finite(plan.get("outgoingPlaybackRate"), 1.0)
    in_rate = _finite(plan.get("incomingPlaybackRate"), 1.0)

    envelope = _tempo_envelope_for_overlap(
        a,
        b,
        a_start,
        a_end,
        b_start,
        out_rate,
        in_rate,
    )
    first = envelope[0] if envelope else None
    lock_out_rate = _finite(first.get("outgoingRate"), out_rate) if first else out_rate
    lock_in_rate = _finite(first.get("incomingRate"), in_rate) if first else in_rate
    semitones, locked_fit, base_fit = _harmonic_lock_shift(
        a,
        b,
        a_start,
        a_end,
        b_start,
        lock_out_rate,
        lock_in_rate,
        _finite(plan.get("overlapVocalClash"), 0.0),
    )

    return {
        "tempoEnvelope": envelope,
        "incomingPitchSemitones": round(_clamp(semitones, -1.0, 1.0), 4),
        "harmonicLockScore": round(_clamp(locked_fit, 0.0, 1.0), 4),
        "harmonicUnshiftedScore": round(_clamp(base_fit, 0.0, 1.0), 4),
    }


def _refine_curve_aligned_cue(
    a: dict[str, Any],
    b: dict[str, Any],
    a_start: float,
    a_end: float,
    initial_cue: float,
    b_start: float,
    b_end: float,
    a_bpm: float,
    b_bpm: float,
    a_rate: float,
    b_rate: float,
) -> dict[str, float | bool]:
    """Fine-align B around a structural cue using the actual transition curves.

    The structural planner gets us onto the correct phrase/downbeat. This second
    pass searches a small neighbourhood around that musical anchor so imperfect
    beat/downbeat estimates do not leave kick/snare attacks, chroma motion or
    spectral changes audibly offset. The search never jumps to a different
    phrase: it stays within roughly half a beat either side of the chosen cue.
    """
    if a_end <= a_start or b_end <= b_start:
        return {"cue": initial_cue, "score": 0.0, "shiftMs": 0.0, "evidence": False}

    beat_seconds = 60.0 / b_bpm if b_bpm > 0.0 else 0.5
    radius = min(0.40, max(0.12, beat_seconds * 0.48))
    step = min(0.025, max(0.0125, beat_seconds / 24.0))

    candidates: set[float] = {initial_cue}
    steps = int(math.ceil(radius / step))
    for index in range(-steps, steps + 1):
        candidates.add(initial_cue + index * step)

    # Real beat anchors get explicit consideration in addition to the fine search.
    for name in ("beats", "downbeats"):
        raw = b.get(name)
        if not isinstance(raw, list):
            continue
        for value in raw:
            time_s = _finite(value, -1.0)
            if initial_cue - radius <= time_s <= initial_cue + radius:
                candidates.add(time_s)

    best_cue = initial_cue
    best_score = float("-inf")
    best_curve = 0.5
    best_phase = 0.5
    best_phase_error = 0.0
    best_harmonic = 0.5
    best_onset = 0.5
    best_spectral = 0.5
    best_energy = 0.5
    evidence_seen = False

    for raw_cue in candidates:
        cue = _clamp(raw_cue, b_start, max(b_start, b_end - 0.25))
        curve = _transition_curve_metrics(
            a,
            b,
            a_start,
            a_end,
            cue,
            a_rate,
            b_rate,
        )
        phase_fit, phase_error_ms = _beat_phase_metrics(
            a,
            b,
            a_start,
            cue,
            a_bpm,
            b_bpm,
            a_rate,
            b_rate,
        )
        has_curves = bool(curve.get("evidence"))
        evidence_seen = evidence_seen or has_curves
        curve_fit = float(curve.get("compatibility", 0.5))
        onset_fit = float(curve.get("onsetFit", 0.5))
        harmonic_fit = float(curve.get("harmonicFit", 0.5))
        spectral_fit = float(curve.get("spectralFit", 0.5))
        energy_fit = float(curve.get("energyContinuity", 0.5))
        proximity = 1.0 - _clamp(abs(cue - initial_cue) / max(radius, 1e-6), 0.0, 1.0)

        # Rhythm leads the fine alignment; harmony and spectrum decide between
        # phase-equivalent placements. Proximity keeps the pass from wandering
        # away from the phrase/downbeat selected by the structural planner.
        if has_curves:
            score = (
                0.26 * phase_fit
                + 0.22 * onset_fit
                + 0.19 * harmonic_fit
                + 0.13 * spectral_fit
                + 0.10 * energy_fit
                + 0.06 * curve_fit
                + 0.04 * proximity
            )
        else:
            score = 0.80 * phase_fit + 0.20 * proximity

        if score > best_score:
            best_score = score
            best_cue = cue
            best_curve = curve_fit
            best_phase = phase_fit
            best_phase_error = phase_error_ms
            best_harmonic = harmonic_fit
            best_onset = onset_fit
            best_spectral = spectral_fit
            best_energy = energy_fit

    return {
        "cue": best_cue,
        "score": _clamp(best_score, 0.0, 1.0),
        "shiftMs": (best_cue - initial_cue) * 1000.0,
        "evidence": evidence_seen,
        "curveCompatibility": best_curve,
        "beatPhaseFit": best_phase,
        "beatPhaseErrorMs": best_phase_error,
        "harmonicCurveFit": best_harmonic,
        "onsetCurveFit": best_onset,
        "spectralCurveFit": best_spectral,
        "energyContinuity": best_energy,
    }


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
            # Tempo is evaluated at the *actual overlap*, not only at the track head/tail.
            # This follows drift/live edits and chooses a meeting rate from the local curves.
            a_local_bpm, a_local_conf = _tempo_near(
                a,
                a_start + actual_span * 0.50,
                outgoing_bpm,
            )
            b_probe_time = cue + min(actual_span * 0.50, max(0.0, b_end - cue))
            b_local_bpm, b_local_conf = _tempo_near(b, b_probe_time, incoming_bpm)
            if a_local_bpm > 0.0 and b_local_bpm > 0.0:
                while b_local_bpm / a_local_bpm > 1.5:
                    b_local_bpm /= 2.0
                while b_local_bpm / a_local_bpm < 0.67:
                    b_local_bpm *= 2.0
            local_tempo_fit = (
                _clamp(1.0 - abs(b_local_bpm / a_local_bpm - 1.0) / 0.14, 0.0, 1.0)
                if a_local_bpm > 0.0 and b_local_bpm > 0.0
                else 0.0
            )
            local_out_rate, local_in_rate = _tempo_bridge_rates(a_local_bpm, b_local_bpm)
            if local_out_rate == 1.0 and local_in_rate == 1.0 and incoming_rate != 1.0:
                # No trustworthy local curve: retain the transition-level bridge.
                local_in_rate = incoming_rate

            aligned = _refine_curve_aligned_cue(
                a,
                b,
                a_start,
                a_end,
                cue,
                b_start,
                b_end,
                a_local_bpm,
                b_local_bpm,
                local_out_rate,
                local_in_rate,
            )
            structural_cue = cue
            cue = float(aligned.get("cue", cue))

            # Re-sample the local incoming tempo after fine cue alignment. A small
            # media-time shift can cross a local tempo boundary in live edits.
            b_probe_time = cue + min(actual_span * 0.50, max(0.0, b_end - cue))
            b_local_bpm, b_local_conf = _tempo_near(b, b_probe_time, incoming_bpm)
            if a_local_bpm > 0.0 and b_local_bpm > 0.0:
                while b_local_bpm / a_local_bpm > 1.5:
                    b_local_bpm /= 2.0
                while b_local_bpm / a_local_bpm < 0.67:
                    b_local_bpm *= 2.0
            local_tempo_fit = (
                _clamp(1.0 - abs(b_local_bpm / a_local_bpm - 1.0) / 0.14, 0.0, 1.0)
                if a_local_bpm > 0.0 and b_local_bpm > 0.0
                else 0.0
            )
            local_out_rate, local_in_rate = _tempo_bridge_rates(a_local_bpm, b_local_bpm)
            if local_out_rate == 1.0 and local_in_rate == 1.0 and incoming_rate != 1.0:
                local_in_rate = incoming_rate

            vocal_clash, energy_fit = _overlap_pair_metrics(
                a,
                b,
                a_start,
                a_end,
                cue,
                local_in_rate,
            )
            curve_metrics = _transition_curve_metrics(
                a,
                b,
                a_start,
                a_end,
                cue,
                local_out_rate,
                local_in_rate,
            )
            phase_fit, phase_error_ms = _beat_phase_metrics(
                a,
                b,
                a_start,
                cue,
                a_local_bpm,
                b_local_bpm,
                local_out_rate,
                local_in_rate,
            )

            skipped = max(0.0, cue - b_start)
            skip_penalty = _clamp((skipped - 8.0) / 24.0, 0.0, 1.0)
            cue_proximity = 1.0 - _clamp(
                abs(cue - desired_cue) / max(8.0, 8.0 * in_beat),
                0.0,
                1.0,
            )
            phrase_pair_bonus = 1.0 if a_kind == "phrase" and b_kind == "phrase" else 0.0
            base_pair_score = (
                0.18 * a_structure
                + 0.18 * b_structure
                + 0.24 * (1.0 - vocal_clash)
                + 0.14 * energy_fit
                + 0.12 * span_fit
                + 0.07 * cue_proximity
                + 0.07 * phrase_pair_bonus
                - 0.16 * skip_penalty
            )
            pair_score = base_pair_score
            if bool(curve_metrics.get("evidence")):
                pair_score = (
                    0.68 * base_pair_score
                    + 0.20 * float(curve_metrics.get("compatibility", 0.5))
                    + 0.07 * phase_fit
                    + 0.05 * local_tempo_fit
                )
            elif isinstance(a.get("beats"), list) and isinstance(b.get("beats"), list):
                pair_score = 0.90 * base_pair_score + 0.10 * phase_fit

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
                "outgoingBpm": a_local_bpm,
                "incomingBpm": b_local_bpm,
                "outgoingRate": local_out_rate,
                "incomingRate": local_in_rate,
                "localTempoFit": local_tempo_fit,
                "tempoCurveConfidence": min(a_local_conf, b_local_conf),
                "beatPhaseFit": phase_fit,
                "beatPhaseErrorMs": phase_error_ms,
                "curveCompatibility": float(curve_metrics.get("compatibility", 0.5)),
                "curveEvidence": bool(curve_metrics.get("evidence")),
                "onsetCurveFit": float(curve_metrics.get("onsetFit", 0.5)),
                "harmonicCurveFit": float(curve_metrics.get("harmonicFit", 0.5)),
                "spectralCurveFit": float(curve_metrics.get("spectralFit", 0.5)),
                "energyShapeFit": float(curve_metrics.get("energyShapeFit", 0.5)),
                "lowBandCollision": float(curve_metrics.get("lowCollision", 0.0)),
                "brightnessGap": float(curve_metrics.get("brightnessGap", 0.0)),
                "curveAlignedCue": cue,
                "curveCueShiftMs": float(aligned.get("shiftMs", 0.0)),
                "curveAlignmentScore": float(aligned.get("score", 0.0)),
                "structuralCue": structural_cue,
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


def _candidate_selection_score(candidate: dict[str, Any]) -> float:
    """Compare transition recipes on musical evidence, never on family name.

    Each generator still has family-specific rules for constructing something
    executable, but once a candidate exists every style competes on the same
    dimensions. This prevents RUNWAY/TAKEOVER/BLEND/CUT from having an implicit
    hard-coded priority just because of an if/elif ordering.
    """
    raw = _clamp(_finite(candidate.get("score"), 0.0), 0.0, 1.0)
    pair = _clamp(_finite(candidate.get("pairCompatibility"), raw), 0.0, 1.0)
    curve = _clamp(_finite(candidate.get("curveCompatibility"), 0.5), 0.0, 1.0)
    phrase = _clamp(_finite(candidate.get("phraseAlignment"), 0.5), 0.0, 1.0)
    tempo = _clamp(_finite(candidate.get("tempoCompatibility"), 0.5), 0.0, 1.0)
    key = _clamp(_finite(candidate.get("keyCompatibility"), 0.5), 0.0, 1.0)
    vocal_safety = 1.0 - _clamp(_finite(candidate.get("overlapVocalClash"), 0.25), 0.0, 1.0)
    energy = _clamp(_finite(candidate.get("energyCompatibility"), 0.5), 0.0, 1.0)
    span = _clamp(_finite(candidate.get("spanCompatibility"), 0.5), 0.0, 1.0)

    return _clamp(
        0.26 * raw
        + 0.17 * pair
        + 0.12 * curve
        + 0.11 * phrase
        + 0.10 * tempo
        + 0.07 * key
        + 0.09 * vocal_safety
        + 0.05 * energy
        + 0.03 * span,
        0.0,
        1.0,
    )


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
    key_evidence = (
        _transition_key(a, "outgoing") is not None
        and _transition_key(b, "incoming") is not None
    )
    bridge_out_rate, bridge_in_rate = _tempo_bridge_rates(a_bpm, b_bpm)
    tempo_distance = abs(b_bpm / a_bpm - 1.0) if a_bpm > 0.0 and b_bpm > 0.0 else 1.0
    tempo_bridge_ok = tempo_distance <= 0.08
    vocal_clash = min(a_tail_vocal, b_open_vocal)
    candidates: list[dict[str, Any]] = []

    # 0) Long runway blend. The references supplied for 2.5 show a recurring pattern:
    # B can already be 13-30+ seconds into its opening when ownership changes, while A still
    # has a meaningful tail left. This is not a conventional crossfade. B starts at its earliest
    # safe audible point, stays subordinate, and its first major structural arrival is aimed at
    # A's measured foreground release. The same prepared B deck then keeps running through the
    # handoff; there is no restart at release.
    runway_markers = [b_impact]
    for raw_marker in (b.get("mixInTime"), b.get("introEndTime")):
        marker = _finite(raw_marker, -1.0)
        if b_start + 8.0 <= marker <= min(b_end, b_start + 72.0):
            runway_markers.append(marker)
    runway_impact = max(runway_markers)
    runway = max(0.0, runway_impact - b_start)

    runway_a_bpm, runway_a_conf = _tempo_near(a, a_release, a_bpm)
    runway_b_bpm, runway_b_conf = _tempo_near(b, runway_impact, b_bpm)
    if runway_a_bpm > 0.0 and runway_b_bpm > 0.0:
        while runway_b_bpm / runway_a_bpm > 1.5:
            runway_b_bpm /= 2.0
        while runway_b_bpm / runway_a_bpm < 0.67:
            runway_b_bpm *= 2.0
    runway_tempo_fit = (
        _clamp(1.0 - abs(runway_b_bpm / runway_a_bpm - 1.0) / 0.14, 0.0, 1.0)
        if runway_a_bpm > 0.0 and runway_b_bpm > 0.0
        else 0.0
    )
    runway_out_rate, runway_in_rate = _tempo_bridge_rates(runway_a_bpm, runway_b_bpm)

    if 8.0 <= runway <= 72.0 and a_release > 0.0 and a_end > a_release:
        # Align B's structural impact to A's measured release in wall time. When both
        # decks are time-stretched toward a meeting tempo, media seconds are not wall seconds.
        desired_start = a_release - runway * runway_out_rate / max(runway_in_rate, 1e-6)
        if desired_start >= 0.0 and desired_start < a_release - 3.0:
            intro_vocal = _mean_window(
                b_vocal,
                b_start,
                b_impact,
                _finite(b.get("vocalProbability"), 0.5),
            )
            a_runway_vocal = _mean_window(
                a_vocal_curve,
                desired_start,
                a_release,
                _finite(a.get("vocalProbability"), 0.5),
            )
            runway_vocal_clash = min(a_runway_vocal, intro_vocal)
            known_key_conflict = key_evidence and key_fit < 0.35

            pre_impact_energy = _mean_window(
                b_energy,
                max(b_start, runway_impact - 6.0),
                runway_impact,
                0.0,
            )
            post_impact_energy = _mean_window(
                b_energy,
                runway_impact,
                min(b_end, runway_impact + 4.0),
                pre_impact_energy,
            )
            impact_rise = _clamp(
                (post_impact_energy - pre_impact_energy + 0.08) / 0.30,
                0.0,
                1.0,
            )
            span = a_end - desired_start
            runway_curve = _transition_curve_metrics(
                a,
                b,
                desired_start,
                a_end,
                b_start,
                runway_out_rate,
                runway_in_rate,
            )
            runway_curve_fit = float(runway_curve.get("compatibility", 0.5))
            runway_curve_evidence = bool(runway_curve.get("evidence", False))
            runway_harmonic_fit = float(runway_curve.get("harmonicFit", 0.5))
            runway_spectral_fit = float(runway_curve.get("spectralFit", 0.5))
            runway_onset_fit = float(runway_curve.get("onsetFit", 0.5))
            runway_low_collision = float(runway_curve.get("lowCollision", 0.0))
            runway_phase_fit, runway_phase_error_ms = _beat_phase_metrics(
                a,
                b,
                a_release,
                runway_impact,
                runway_a_bpm,
                runway_b_bpm,
                runway_out_rate,
                runway_in_rate,
            )
            handoff_fraction = _clamp(
                (a_release - desired_start) / max(span, 1e-6),
                0.38,
                0.90,
            )
            # Long overlaps need a very clean B runway. A may still be vocal-heavy because B is
            # intentionally underneath it, but a vocal B intro would become a duet and is rejected.
            if (
                not known_key_conflict
                and intro_vocal < 0.38
                and runway_vocal_clash < 0.42
                and impact_rise >= 0.30
                and span <= 82.0
                and (min(runway_a_conf, runway_b_conf) < 0.18 or runway_tempo_fit >= 0.42)
                and (not runway_curve_evidence or runway_harmonic_fit >= 0.20)
            ):
                runway_score = (
                    0.22
                    + 0.14 * _clamp(runway / 32.0, 0.0, 1.0)
                    + 0.14 * (1.0 - intro_vocal)
                    + 0.11 * impact_rise
                    + 0.08 * key_fit
                    + 0.09 * runway_tempo_fit
                    + 0.07 * runway_phase_fit
                    + 0.07 * _clamp((a_end - a_release) / 16.0, 0.0, 1.0)
                    + (0.08 * runway_curve_fit if runway_curve_evidence else 0.0)
                )
                pre_handoff = max(0.08, handoff_fraction - 0.16)
                post_handoff = min(0.98, handoff_fraction + 0.12)
                candidates.append(_candidate_plan(
                    "RUNWAY_BLEND",
                    runway_score,
                    "server-long-runway-impact",
                    transitionStart=round(desired_start, 4),
                    transitionEnd=round(a_end, 4),
                    incomingCueTime=round(b_start, 4),
                    incomingHandoffTime=round(runway_impact, 4),
                    outgoingPlaybackRate=round(runway_out_rate, 5),
                    incomingPlaybackRate=round(runway_in_rate, 5),
                    transitionBeats=0,
                    requestedTransitionBeats=0,
                    handoffFraction=round(handoff_fraction, 4),
                    bassSwap=bool(key_fit >= 0.58 and _low_curve(a) and _low_curve(b)),
                    bassSwapFraction=round(handoff_fraction, 4),
                    filterSweep=round(_clamp(
                        (0.24 if key_fit >= 0.58 else 0.54)
                        + 0.20 * (1.0 - runway_spectral_fit)
                        + 0.16 * runway_low_collision,
                        0.20,
                        0.92,
                    ), 4),
                    keyCompatibility=round(key_fit, 4),
                    tempoCompatibility=round(tempo, 4),
                    phraseAlignment=0.0,
                    overlapVocalClash=round(runway_vocal_clash, 4),
                    energyCompatibility=round(
                        _clamp(1.0 - abs(
                            _mean_window(_curve(a, "energyCurve"), desired_start, a_release, 0.5)
                            - pre_impact_energy
                        ), 0.0, 1.0),
                        4,
                    ),
                    pairCompatibility=round(
                        _clamp(0.55 * (1.0 - intro_vocal) + 0.45 * impact_rise, 0.0, 1.0),
                        4,
                    ),
                    spanCompatibility=1.0,
                    outgoingAnchor="release",
                    incomingAnchor="impact",
                    curveCompatibility=round(runway_curve_fit, 4),
                    onsetCurveFit=round(runway_onset_fit, 4),
                    harmonicCurveFit=round(runway_harmonic_fit, 4),
                    spectralCurveFit=round(runway_spectral_fit, 4),
                    beatPhaseFit=round(runway_phase_fit, 4),
                    beatPhaseErrorMs=round(runway_phase_error_ms, 2),
                    localTempoCompatibility=round(runway_tempo_fit, 4),
                    outgoingLocalBpm=round(runway_a_bpm, 4),
                    incomingLocalBpm=round(runway_b_bpm, 4),
                    gainEnvelope=[
                        {"progress": 0.0, "incomingGain": 0.0, "outgoingGain": 1.0},
                        {
                            "progress": round(min(0.14, handoff_fraction * 0.28), 4),
                            "incomingGain": 0.12,
                            "outgoingGain": 1.0,
                        },
                        {
                            "progress": round(pre_handoff, 4),
                            "incomingGain": 0.34,
                            "outgoingGain": 0.99,
                        },
                        {
                            "progress": round(handoff_fraction, 4),
                            "incomingGain": 0.62,
                            "outgoingGain": 0.94,
                        },
                        {
                            "progress": round(post_handoff, 4),
                            "incomingGain": 0.92,
                            "outgoingGain": 0.38,
                        },
                        {"progress": 1.0, "incomingGain": 1.0, "outgoingGain": 0.0},
                    ],
                ))

    # 1) Beat/key bridge. The planner now searches *pairs* of structural anchors:
    # A's late phrase/downbeat and B's entry phrase/downbeat are scored together.
    if a_end > 0.0 and 40.0 <= a_bpm <= 220.0 and 40.0 <= b_bpm <= 220.0:
        blend_ok = key_evidence and tempo_bridge_ok and tempo >= 0.62 and conf >= 0.35 and key_fit >= 0.58
        # A missing/low-confidence key must not kill Automix by itself when the rhythmic grid is
        # strong and the actual overlap is sparse in vocals. In that case only the filtered,
        # shorter family is allowed; an open DJ_BLEND/EQ_SWAP still requires trusted harmony.
        filter_ok = tempo_bridge_ok and tempo >= 0.48 and conf >= 0.35 and (
            (key_evidence and key_fit >= 0.35)
            or (not key_evidence and vocal_clash < 0.34)
        )

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
                local_tempo_fit = float(pair.get("localTempoFit", tempo))
                curve_fit = float(pair.get("curveCompatibility", 0.5))
                curve_evidence = bool(pair.get("curveEvidence", False))
                onset_curve_fit = float(pair.get("onsetCurveFit", 0.5))
                harmonic_curve_fit = float(pair.get("harmonicCurveFit", 0.5))
                spectral_curve_fit = float(pair.get("spectralCurveFit", 0.5))
                phase_fit = float(pair.get("beatPhaseFit", 0.5))
                phase_error_ms = float(pair.get("beatPhaseErrorMs", 0.0))
                local_out_rate = float(pair.get("outgoingRate", bridge_out_rate))
                local_in_rate = float(pair.get("incomingRate", bridge_in_rate))
                low_collision = float(pair.get("lowBandCollision", 0.0))

                style = requested_style
                # A flat blend with real vocal-on-vocal collision is demoted to a filtered
                # bridge only when that bridge is independently allowed. Heavy collision
                # rejects the overlap entirely.
                if style == "DJ_BLEND" and overlap_vocal_clash >= 0.58:
                    style = "DJ_FILTER" if filter_ok and overlap_vocal_clash < 0.74 else None
                elif style == "DJ_FILTER" and overlap_vocal_clash >= 0.74:
                    style = None

                # Global key labels can look compatible while the actual chords at the join are
                # not. Curve-aware 2.5 therefore demotes an open blend when the local chroma
                # trajectory disagrees, and rejects a beatmatched overlap when its transient
                # contours cannot be phase-locked.
                if curve_evidence and style == "DJ_BLEND" and harmonic_curve_fit < 0.42:
                    style = "DJ_FILTER" if filter_ok and harmonic_curve_fit >= 0.24 else None
                if curve_evidence and style is not None and onset_curve_fit < 0.30 and phase_fit < 0.45:
                    style = None

                minimum_beats = 8 if style == "DJ_BLEND" else 4
                minimum_structure = 0.55 if style == "DJ_BLEND" else 0.48
                if (
                    style is not None
                    and actual_beats >= minimum_beats
                    and span_fit >= 0.35
                    and phrase_fit >= minimum_structure
                ):
                    score = (
                        0.12 + 0.14 * local_tempo_fit + 0.09 * conf + 0.14 * key_fit
                        + 0.10 * (1.0 - overlap_vocal_clash)
                        + 0.07 * phrase_fit
                        + 0.05 * energy_fit
                        + 0.10 * pair_fit
                        + 0.08 * phase_fit
                        + (0.11 * curve_fit if curve_evidence else 0.0)
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
                        outgoingPlaybackRate=round(local_out_rate, 5),
                        incomingPlaybackRate=round(local_in_rate, 5),
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
                        filterSweep=(
                            0.0
                            if style == "DJ_BLEND"
                            else round(_clamp(
                                0.58
                                + 0.24 * (1.0 - spectral_curve_fit)
                                + 0.18 * low_collision,
                                0.58,
                                0.96,
                            ), 4)
                        ),
                        keyCompatibility=round(key_fit, 4),
                        tempoCompatibility=round(tempo, 4),
                        phraseAlignment=round(phrase_fit, 4),
                        overlapVocalClash=round(overlap_vocal_clash, 4),
                        energyCompatibility=round(energy_fit, 4),
                        pairCompatibility=round(pair_fit, 4),
                        spanCompatibility=round(span_fit, 4),
                        outgoingAnchor=str(pair["outgoingAnchor"]),
                        incomingAnchor=str(pair["incomingAnchor"]),
                        curveCompatibility=round(curve_fit, 4),
                        onsetCurveFit=round(onset_curve_fit, 4),
                        harmonicCurveFit=round(harmonic_curve_fit, 4),
                        spectralCurveFit=round(spectral_curve_fit, 4),
                        beatPhaseFit=round(phase_fit, 4),
                        beatPhaseErrorMs=round(phase_error_ms, 2),
                        localTempoCompatibility=round(local_tempo_fit, 4),
                        outgoingLocalBpm=round(float(pair.get("outgoingBpm", a_bpm)), 4),
                        incomingLocalBpm=round(float(pair.get("incomingBpm", b_bpm)), 4),
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
            eq_curve_fit = float(pair.get("curveCompatibility", 0.5))
            eq_curve_evidence = bool(pair.get("curveEvidence", False))
            eq_onset_fit = float(pair.get("onsetCurveFit", 0.5))
            eq_harmonic_fit = float(pair.get("harmonicCurveFit", 0.5))
            eq_spectral_fit = float(pair.get("spectralCurveFit", 0.5))
            eq_phase_fit = float(pair.get("beatPhaseFit", 0.5))
            eq_phase_error_ms = float(pair.get("beatPhaseErrorMs", 0.0))
            eq_local_tempo = float(pair.get("localTempoFit", tempo))
            eq_out_rate = float(pair.get("outgoingRate", bridge_out_rate))
            eq_in_rate = float(pair.get("incomingRate", bridge_in_rate))
            eq_low_collision = float(pair.get("lowBandCollision", 0.0))

            eq_score = (
                0.13 + 0.13 * eq_local_tempo + 0.08 * conf + 0.13 * key_fit
                + 0.10 * (1.0 - overlap_vocal_clash)
                + 0.07 * phrase_fit
                + 0.05 * energy_fit
                + 0.10 * pair_fit
                + 0.08 * eq_phase_fit
                + (0.13 * eq_curve_fit if eq_curve_evidence else 0.0)
            )
            if protected:
                eq_score -= 0.16

            if (
                overlap_vocal_clash < 0.62
                and actual_beats >= 8
                and span_fit >= 0.35
                and phrase_fit >= 0.55
                and (not eq_curve_evidence or eq_harmonic_fit >= 0.42)
                and (not eq_curve_evidence or eq_onset_fit >= 0.30 or eq_phase_fit >= 0.55)
                and eq_low_collision <= 0.82
            ):
                candidates.append(_candidate_plan(
                    "EQ_SWAP",
                    eq_score,
                    "server-paired-eq-swap",
                    transitionStart=round(start, 4),
                    transitionEnd=round(a_end, 4),
                    incomingCueTime=round(cue, 4),
                    incomingHandoffTime=round(cue, 4),
                    outgoingPlaybackRate=round(eq_out_rate, 5),
                    incomingPlaybackRate=round(eq_in_rate, 5),
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
                    curveCompatibility=round(eq_curve_fit, 4),
                    onsetCurveFit=round(eq_onset_fit, 4),
                    harmonicCurveFit=round(eq_harmonic_fit, 4),
                    spectralCurveFit=round(eq_spectral_fit, 4),
                    beatPhaseFit=round(eq_phase_fit, 4),
                    beatPhaseErrorMs=round(eq_phase_error_ms, 2),
                    localTempoCompatibility=round(eq_local_tempo, 4),
                    outgoingLocalBpm=round(float(pair.get("outgoingBpm", a_bpm)), 4),
                    incomingLocalBpm=round(float(pair.get("incomingBpm", b_bpm)), 4),
                    gainEnvelope=[],
                ))

    # 2.5) Rhythmic filtered bridge. This is the practical middle ground used when
    # tempo/downbeat evidence is good but harmony/phrase confidence is not strong enough
    # for an open DJ blend. It is still a real overlap: B becomes audible several beats
    # before A ends, both decks share a meeting tempo, and the filter/EQ gesture masks
    # weaker harmonic evidence. CUT remains the final conservative handoff after richer candidates.
    if (
        a_end > 0.0
        and 40.0 <= a_bpm <= 220.0
        and 40.0 <= b_bpm <= 220.0
        and tempo_distance <= 0.10
        and tempo >= 0.38
        and conf >= 0.30
    ):
        # Unknown key may use a filtered bridge; a *known* incompatible key may not.
        # Filtering can mask uncertain harmony, but it should not deliberately layer
        # two keys the analyzer has positively identified as conflicting.
        known_key_conflict = key_evidence and key_fit < 0.35
        target_beats = 8
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
            rhythmic_curve_fit = float(pair.get("curveCompatibility", 0.5))
            rhythmic_curve_evidence = bool(pair.get("curveEvidence", False))
            rhythmic_onset_fit = float(pair.get("onsetCurveFit", 0.5))
            rhythmic_harmonic_fit = float(pair.get("harmonicCurveFit", 0.5))
            rhythmic_spectral_fit = float(pair.get("spectralCurveFit", 0.5))
            rhythmic_phase_fit = float(pair.get("beatPhaseFit", 0.5))
            rhythmic_phase_error_ms = float(pair.get("beatPhaseErrorMs", 0.0))
            rhythmic_local_tempo = float(pair.get("localTempoFit", tempo))
            rhythmic_out_rate = float(pair.get("outgoingRate", bridge_out_rate))
            rhythmic_in_rate = float(pair.get("incomingRate", bridge_in_rate))
            rhythmic_low_collision = float(pair.get("lowBandCollision", 0.0))

            has_outgoing_structure = bool(a.get("downbeats") or a.get("phraseBoundaries"))
            has_incoming_structure = bool(b.get("downbeats") or b.get("phraseBoundaries"))
            vocal_limit = 0.68
            structure_floor = 0.42
            minimum_beats = 4
            if (
                not known_key_conflict
                and has_outgoing_structure
                and has_incoming_structure
                and actual_beats >= minimum_beats
                and span_fit >= 0.24
                and phrase_fit >= structure_floor
                and overlap_vocal_clash < vocal_limit
                and (not rhythmic_curve_evidence or rhythmic_onset_fit >= 0.24 or rhythmic_phase_fit >= 0.50)
            ):
                rhythmic_score = (
                    0.16
                    + 0.16 * rhythmic_local_tempo
                    + 0.10 * conf
                    + 0.13 * (1.0 - overlap_vocal_clash)
                    + 0.08 * energy_fit
                    + 0.07 * phrase_fit
                    + 0.10 * pair_fit
                    + 0.08 * rhythmic_phase_fit
                    + (0.12 * rhythmic_curve_fit if rhythmic_curve_evidence else 0.0)
                )
                if key_evidence:
                    rhythmic_score += 0.05 * key_fit
                candidates.append(_candidate_plan(
                    "DJ_FILTER",
                    rhythmic_score,
                    "server-rhythmic-filter-bridge",
                    transitionStart=round(start, 4),
                    transitionEnd=round(a_end, 4),
                    incomingCueTime=round(cue, 4),
                    incomingHandoffTime=round(cue, 4),
                    outgoingPlaybackRate=round(rhythmic_out_rate, 5),
                    incomingPlaybackRate=round(rhythmic_in_rate, 5),
                    transitionBeats=actual_beats,
                    requestedTransitionBeats=target_beats,
                    handoffFraction=round(
                        _clamp(
                            release_fraction,
                            0.86 if protected else 0.48,
                            0.94 if protected else 0.82,
                        ),
                        4,
                    ),
                    bassSwap=bool(
                        key_evidence
                        and key_fit >= 0.58
                        and _low_curve(a)
                        and _low_curve(b)
                    ),
                    bassSwapFraction=round(_clamp(release_fraction, 0.50, 0.82), 4),
                    filterSweep=round(_clamp(
                        0.68
                        + 0.18 * (1.0 - rhythmic_spectral_fit)
                        + 0.14 * rhythmic_low_collision
                        + 0.08 * (1.0 - rhythmic_harmonic_fit),
                        0.68,
                        0.98,
                    ), 4),
                    keyCompatibility=round(key_fit, 4),
                    tempoCompatibility=round(tempo, 4),
                    phraseAlignment=round(phrase_fit, 4),
                    overlapVocalClash=round(overlap_vocal_clash, 4),
                    energyCompatibility=round(energy_fit, 4),
                    pairCompatibility=round(pair_fit, 4),
                    spanCompatibility=round(span_fit, 4),
                    outgoingAnchor=str(pair["outgoingAnchor"]),
                    incomingAnchor=str(pair["incomingAnchor"]),
                    curveCompatibility=round(rhythmic_curve_fit, 4),
                    onsetCurveFit=round(rhythmic_onset_fit, 4),
                    harmonicCurveFit=round(rhythmic_harmonic_fit, 4),
                    spectralCurveFit=round(rhythmic_spectral_fit, 4),
                    beatPhaseFit=round(rhythmic_phase_fit, 4),
                    beatPhaseErrorMs=round(rhythmic_phase_error_ms, 2),
                    localTempoCompatibility=round(rhythmic_local_tempo, 4),
                    outgoingLocalBpm=round(float(pair.get("outgoingBpm", a_bpm)), 4),
                    incomingLocalBpm=round(float(pair.get("incomingBpm", b_bpm)), 4),
                    gainEnvelope=[],
                ))

    # 2.6) Quiet-tail takeover. A file may keep running for several seconds after its
    # meaningful musical content has already released. Natural playback in that situation creates
    # exactly the dead-air failure Automix exists to avoid. Once A's post-content tail is both
    # low-energy and vocal-free, harmonic/tempo mismatch stops being a hard veto: there is little
    # or no foreground material left to clash with B. Start B around A's measured release, let its
    # opening occupy the dying tail, and hand ownership over before the silent file tail finishes.
    a_file_end = max(a_end, _finite(a.get("duration"), a_end))
    dead_tail = max(0.0, a_file_end - a_end)
    post_content_energy = _mean_window(
        _curve(a, "energyCurve"),
        a_end,
        a_file_end,
        0.0,
    )
    post_content_vocal = _mean_window(
        a_vocal_curve,
        a_end,
        a_file_end,
        0.0,
    )
    quiet_tail = (
        dead_tail >= 2.0
        and post_content_energy <= 0.24
        and post_content_vocal <= 0.24
        and b_end > b_start + 1.0
    )
    if quiet_tail:
        # Begin slightly before contentEnd so a low-level outro and B's opening can actually
        # overlap instead of producing a disguised gapless skip. Longer dead tails allow a
        # slightly earlier, more leisurely entrance but never steal a large part of A.
        pre_roll = _clamp(1.25 + dead_tail * 0.18, 1.25, 3.75)
        post_roll = _clamp(0.90 + dead_tail * 0.12, 0.90, 2.75)
        quiet_start = max(0.0, min(a_release, a_end) - pre_roll)
        quiet_end = min(a_file_end, max(a_end + post_roll, quiet_start + 2.0))

        quiet_curve = _transition_curve_metrics(
            a,
            b,
            quiet_start,
            min(a_end, quiet_end),
            b_start,
            1.0,
            1.0,
        )
        quiet_curve_evidence = bool(quiet_curve.get("evidence", False))
        quiet_curve_fit = float(quiet_curve.get("compatibility", 0.5))
        quiet_onset_fit = float(quiet_curve.get("onsetFit", 0.5))
        quiet_harmonic_fit = float(quiet_curve.get("harmonicFit", 0.5))
        quiet_spectral_fit = float(quiet_curve.get("spectralFit", 0.5))
        quiet_low_collision = float(quiet_curve.get("lowCollision", 0.0))

        handoff_fraction = _clamp(
            (a_end - quiet_start) / max(quiet_end - quiet_start, 1e-6),
            0.38,
            0.82,
        )
        # Dead-air avoidance is intrinsically high-confidence once the tail is measured quiet.
        # Curve similarity is a bonus that lets musically similar outro/intro pairs outrank merely
        # safe ones; it is never a veto here.
        quiet_score = (
            0.66
            + 0.12 * _clamp(dead_tail / 8.0, 0.0, 1.0)
            + 0.08 * (1.0 - post_content_energy)
            + 0.06 * (1.0 - post_content_vocal)
            + (0.08 * quiet_curve_fit if quiet_curve_evidence else 0.0)
        )
        before = max(0.08, handoff_fraction - 0.20)
        after = min(0.96, handoff_fraction + 0.16)
        candidates.append(_candidate_plan(
            "PHRASE_TAKEOVER",
            quiet_score,
            "server-quiet-tail-takeover",
            transitionStart=round(quiet_start, 4),
            transitionEnd=round(quiet_end, 4),
            incomingCueTime=round(b_start, 4),
            incomingHandoffTime=round(b_start, 4),
            outgoingPlaybackRate=1.0,
            incomingPlaybackRate=1.0,
            transitionBeats=0,
            requestedTransitionBeats=0,
            handoffFraction=round(handoff_fraction, 4),
            bassSwap=False,
            bassSwapFraction=round(handoff_fraction, 4),
            filterSweep=round(_clamp(
                0.14
                + 0.18 * (1.0 - quiet_spectral_fit)
                + 0.10 * quiet_low_collision,
                0.08,
                0.48,
            ), 4),
            keyCompatibility=round(key_fit, 4),
            tempoCompatibility=round(tempo, 4),
            phraseAlignment=1.0,
            overlapVocalClash=round(min(post_content_vocal, b_open_vocal), 4),
            energyCompatibility=round(1.0 - post_content_energy, 4),
            pairCompatibility=round(_clamp(quiet_score, 0.0, 1.0), 4),
            spanCompatibility=1.0,
            outgoingAnchor="quiet-tail-release",
            incomingAnchor="opening",
            curveCompatibility=round(quiet_curve_fit, 4),
            onsetCurveFit=round(quiet_onset_fit, 4),
            harmonicCurveFit=round(quiet_harmonic_fit, 4),
            spectralCurveFit=round(quiet_spectral_fit, 4),
            beatPhaseFit=0.0,
            beatPhaseErrorMs=0.0,
            localTempoCompatibility=round(tempo, 4),
            outgoingLocalBpm=round(a_bpm, 4),
            incomingLocalBpm=round(b_bpm, 4),
            gainEnvelope=[
                {"progress": 0.0, "incomingGain": 0.0, "outgoingGain": 1.0},
                {
                    "progress": round(before, 4),
                    "incomingGain": 0.18,
                    "outgoingGain": 1.0,
                },
                {
                    "progress": round(handoff_fraction, 4),
                    "incomingGain": 0.72,
                    "outgoingGain": 0.72,
                },
                {
                    "progress": round(after, 4),
                    "incomingGain": 1.0,
                    "outgoingGain": 0.20,
                },
                {"progress": 1.0, "incomingGain": 1.0, "outgoingGain": 0.0},
            ],
        ))

    # 2.75) Phrase takeover. Some reference transitions deliberately hand ownership to B
    # before A's file reaches its natural end, but only after A has released its foreground phrase.
    # B starts at (or very near) its opening instead of being cued into a chorus. A gets a short
    # musical tail after the handoff and is then retired; this is intentionally different from CUT.
    release_tail = max(0.0, a_end - a_release)
    immediate_entry = max(b_start, min(b_impact, _finite(b.get("mixInTime"), b_impact)))
    entry_delay = max(0.0, immediate_entry - b_start)
    release_energy_before = _mean_window(
        _curve(a, "energyCurve"),
        max(0.0, a_release - 6.0),
        a_release,
        0.5,
    )
    release_energy_after = _mean_window(
        _curve(a, "energyCurve"),
        a_release,
        min(a_end, a_release + 4.0),
        release_energy_before,
    )
    release_vocal_before = _mean_window(
        a_vocal_curve,
        max(0.0, a_release - 6.0),
        a_release,
        _finite(a.get("vocalProbability"), 0.5),
    )
    release_vocal_after = _mean_window(
        a_vocal_curve,
        a_release,
        min(a_end, a_release + 4.0),
        release_vocal_before,
    )
    real_release_drop = (
        release_energy_before - release_energy_after >= 0.12
        or release_vocal_before - release_vocal_after >= 0.15
    )
    if (
        real_release_drop
        and 5.0 <= release_tail <= 24.0
        and entry_delay <= 4.5
        and a_end > 0.0
        and b_end > b_start + 2.0
    ):
        out_beat = 60.0 / a_bpm if a_bpm > 0.0 else 0.55
        in_beat = 60.0 / b_bpm if b_bpm > 0.0 else out_beat
        takeover_start, outgoing_takeover_fit = _structural_snap(
            a,
            max(0.0, a_release - 4.0 * out_beat),
            max(0.0, a_release - 8.0 * out_beat),
            min(a_end - 0.20, a_release),
            out_beat,
        )
        takeover_cue, incoming_takeover_fit = _structural_snap(
            b,
            b_start,
            b_start,
            min(b_end - 0.25, b_start + 4.0 * in_beat),
            in_beat,
        )
        takeover_end = min(a_end, a_release + max(1.8, 4.0 * out_beat))
        if takeover_end > takeover_start + 1.0:
            takeover_a_bpm, takeover_a_conf = _tempo_near(
                a,
                takeover_start + (takeover_end - takeover_start) * 0.5,
                a_bpm,
            )
            takeover_b_bpm, takeover_b_conf = _tempo_near(
                b,
                takeover_cue + (takeover_end - takeover_start) * 0.5,
                b_bpm,
            )
            if takeover_a_bpm > 0.0 and takeover_b_bpm > 0.0:
                while takeover_b_bpm / takeover_a_bpm > 1.5:
                    takeover_b_bpm /= 2.0
                while takeover_b_bpm / takeover_a_bpm < 0.67:
                    takeover_b_bpm *= 2.0
            takeover_tempo_fit = (
                _clamp(1.0 - abs(takeover_b_bpm / takeover_a_bpm - 1.0) / 0.14, 0.0, 1.0)
                if takeover_a_bpm > 0.0 and takeover_b_bpm > 0.0
                else 0.0
            )
            takeover_out_rate, takeover_in_rate = _tempo_bridge_rates(
                takeover_a_bpm,
                takeover_b_bpm,
            )
            takeover_vocal_clash, takeover_energy_fit = _overlap_pair_metrics(
                a,
                b,
                takeover_start,
                takeover_end,
                takeover_cue,
                takeover_in_rate,
            )
            takeover_curve = _transition_curve_metrics(
                a,
                b,
                takeover_start,
                takeover_end,
                takeover_cue,
                takeover_out_rate,
                takeover_in_rate,
            )
            takeover_curve_fit = float(takeover_curve.get("compatibility", 0.5))
            takeover_curve_evidence = bool(takeover_curve.get("evidence", False))
            takeover_onset_fit = float(takeover_curve.get("onsetFit", 0.5))
            takeover_harmonic_fit = float(takeover_curve.get("harmonicFit", 0.5))
            takeover_spectral_fit = float(takeover_curve.get("spectralFit", 0.5))
            takeover_low_collision = float(takeover_curve.get("lowCollision", 0.0))
            takeover_phase_fit, takeover_phase_error_ms = _beat_phase_metrics(
                a,
                b,
                takeover_start,
                takeover_cue,
                takeover_a_bpm,
                takeover_b_bpm,
                takeover_out_rate,
                takeover_in_rate,
            )
            takeover_structure = min(outgoing_takeover_fit, incoming_takeover_fit)
            known_key_conflict = key_evidence and key_fit < 0.18
            if (
                not known_key_conflict
                and takeover_structure >= 0.52
                and takeover_vocal_clash < 0.72
                and (min(takeover_a_conf, takeover_b_conf) < 0.18 or takeover_tempo_fit >= 0.35)
                and (not takeover_curve_evidence or takeover_harmonic_fit >= 0.18)
                and (not takeover_curve_evidence or takeover_onset_fit >= 0.22 or takeover_phase_fit >= 0.48)
            ):
                handoff_fraction = _clamp(
                    (a_release - takeover_start) / max(takeover_end - takeover_start, 1e-6),
                    0.45,
                    0.82,
                )
                takeover_score = (
                    0.24
                    + 0.16 * takeover_structure
                    + 0.13 * (1.0 - takeover_vocal_clash)
                    + 0.09 * takeover_energy_fit
                    + 0.08 * key_fit
                    + 0.08 * takeover_tempo_fit
                    + 0.06 * takeover_phase_fit
                    + 0.06 * _clamp(release_tail / 12.0, 0.0, 1.0)
                    + (0.10 * takeover_curve_fit if takeover_curve_evidence else 0.0)
                )
                before = max(0.05, handoff_fraction - 0.16)
                after = min(0.96, handoff_fraction + 0.10)
                candidates.append(_candidate_plan(
                    "PHRASE_TAKEOVER",
                    takeover_score,
                    "server-phrase-takeover",
                    transitionStart=round(takeover_start, 4),
                    transitionEnd=round(takeover_end, 4),
                    incomingCueTime=round(takeover_cue, 4),
                    incomingHandoffTime=round(takeover_cue, 4),
                    outgoingPlaybackRate=round(takeover_out_rate, 5),
                    incomingPlaybackRate=round(takeover_in_rate, 5),
                    transitionBeats=max(1, int(round((takeover_end - takeover_start) / out_beat))),
                    requestedTransitionBeats=4,
                    handoffFraction=round(handoff_fraction, 4),
                    bassSwap=bool(key_fit >= 0.58 and _low_curve(a) and _low_curve(b)),
                    bassSwapFraction=round(handoff_fraction, 4),
                    filterSweep=round(_clamp(
                        (0.34 if key_fit < 0.58 else 0.0)
                        + 0.22 * (1.0 - takeover_spectral_fit)
                        + 0.16 * takeover_low_collision,
                        0.0,
                        0.86,
                    ), 4),
                    keyCompatibility=round(key_fit, 4),
                    tempoCompatibility=round(tempo, 4),
                    phraseAlignment=round(takeover_structure, 4),
                    overlapVocalClash=round(takeover_vocal_clash, 4),
                    energyCompatibility=round(takeover_energy_fit, 4),
                    pairCompatibility=round(
                        _clamp(
                            0.45 * takeover_structure
                            + 0.35 * (1.0 - takeover_vocal_clash)
                            + 0.20 * takeover_energy_fit,
                            0.0,
                            1.0,
                        ),
                        4,
                    ),
                    spanCompatibility=1.0,
                    outgoingAnchor="release",
                    incomingAnchor="opening-phrase",
                    curveCompatibility=round(takeover_curve_fit, 4),
                    onsetCurveFit=round(takeover_onset_fit, 4),
                    harmonicCurveFit=round(takeover_harmonic_fit, 4),
                    spectralCurveFit=round(takeover_spectral_fit, 4),
                    beatPhaseFit=round(takeover_phase_fit, 4),
                    beatPhaseErrorMs=round(takeover_phase_error_ms, 2),
                    localTempoCompatibility=round(takeover_tempo_fit, 4),
                    outgoingLocalBpm=round(takeover_a_bpm, 4),
                    incomingLocalBpm=round(takeover_b_bpm, 4),
                    gainEnvelope=[
                        {"progress": 0.0, "incomingGain": 0.0, "outgoingGain": 1.0},
                        {
                            "progress": round(before, 4),
                            "incomingGain": 0.16,
                            "outgoingGain": 1.0,
                        },
                        {
                            "progress": round(handoff_fraction, 4),
                            "incomingGain": 0.76,
                            "outgoingGain": 0.90,
                        },
                        {
                            "progress": round(after, 4),
                            "incomingGain": 1.0,
                            "outgoingGain": 0.24,
                        },
                        {"progress": 1.0, "incomingGain": 1.0, "outgoingGain": 0.0},
                    ],
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
        if skipped <= 8.0 and phrase_fit >= 0.52 and phrase_score >= 0.30:
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
        if cut_structure >= 0.52:
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

    # 5) Automix 2.5 always creates a transition in normal playback.
    # "No transition" is reserved for album-original-order playback, which is
    # intercepted on Android before analysis/planning reaches this endpoint.
    #
    # If all richer generators declined the pair, build one conservative but
    # still intentional fallback from the actual vocal/tempo evidence instead of
    # silently giving up. High vocal collision gets a phrase-style handoff;
    # otherwise a short filtered bridge masks weak harmonic/tempo evidence.
    if not candidates and a_end > 0.0 and b_end > b_start:
        beat = 60.0 / a_bpm if a_bpm > 0.0 else 0.55
        # When no richer candidate survived its own musical-safety rules, never
        # manufacture an overlap merely to avoid NO_TRANSITION. Create a deliberate
        # handoff instead: phrase-like when there is enough room, otherwise a clean cut.
        fallback_span = _clamp(
            beat * (1.5 if vocal_clash >= 0.45 or b_open_vocal >= 0.50 else 1.0),
            0.45,
            1.40,
        )
        fallback_start = max(0.0, a_end - fallback_span)
        fallback_style = "PHRASE_CUT" if fallback_span >= 0.70 else "CUT"
        fallback_reason = (
            "server-adaptive-phrase-fallback"
            if fallback_style == "PHRASE_CUT"
            else "server-adaptive-clean-fallback"
        )
        candidates.append(_candidate_plan(
            fallback_style,
            0.40 + 0.12 * (1.0 - vocal_clash) + 0.06 * tempo,
            fallback_reason,
            transitionStart=round(fallback_start, 4),
            transitionEnd=round(a_end, 4),
            incomingCueTime=round(b_start, 4),
            incomingHandoffTime=round(b_start, 4),
            outgoingPlaybackRate=1.0,
            incomingPlaybackRate=1.0,
            transitionBeats=1,
            requestedTransitionBeats=1,
            handoffFraction=0.78 if fallback_style == "PHRASE_CUT" else 0.88,
            bassSwap=False,
            bassSwapFraction=0.78,
            filterSweep=0.0,
            keyCompatibility=round(key_fit, 4),
            tempoCompatibility=round(tempo, 4),
            phraseAlignment=0.40,
            overlapVocalClash=round(vocal_clash, 4),
            energyCompatibility=0.50,
            pairCompatibility=round(
                _clamp(0.50 * (1.0 - vocal_clash) + 0.30 * tempo + 0.20 * key_fit, 0.0, 1.0),
                4,
            ),
            spanCompatibility=1.0,
            gainEnvelope=[],
        ))

    if not candidates:
        # Degenerate metadata should still not surface NO_TRANSITION in normal
        # Automix playback. Use a minimal clean handoff rather than inventing silence.
        end = max(0.0, a_end)
        start = max(0.0, end - 0.65)
        candidates.append(_candidate_plan(
            "CUT",
            0.30,
            "server-minimal-handoff",
            transitionStart=round(start, 4),
            transitionEnd=round(end, 4),
            incomingCueTime=round(max(0.0, b_start), 4),
            incomingHandoffTime=round(max(0.0, b_start), 4),
            outgoingPlaybackRate=1.0,
            incomingPlaybackRate=1.0,
            transitionBeats=1,
            requestedTransitionBeats=1,
            handoffFraction=0.86,
            bassSwap=False,
            bassSwapFraction=0.70,
            filterSweep=0.0,
            keyCompatibility=round(key_fit, 4),
            tempoCompatibility=round(tempo, 4),
            phraseAlignment=0.25,
            overlapVocalClash=round(vocal_clash, 4),
            energyCompatibility=0.40,
            pairCompatibility=0.30,
            spanCompatibility=1.0,
            gainEnvelope=[],
        ))

    # No transition family has priority. All executable recipes are normalized
    # through the same musical-quality score and compete directly.
    for candidate in candidates:
        candidate["selectionScore"] = round(_candidate_selection_score(candidate), 4)

    candidates.sort(
        key=lambda candidate: (
            candidate["selectionScore"],
            candidate.get("score", 0.0),
        ),
        reverse=True,
    )
    best = candidates[0]
    second = candidates[1]["selectionScore"] if len(candidates) > 1 else 0.0
    best_selection = best["selectionScore"]

    # Confidence describes confidence in the choice among candidates, not a
    # preference for any particular transition family.
    confidence = _clamp(
        0.44 + 0.40 * best_selection + 0.28 * max(0.0, best_selection - second),
        0.0,
        0.99,
    )
    best = dict(best)
    best["confidence"] = round(confidence, 4)
    best["protectedOutgoing"] = protected
    best["outgoingReleaseTime"] = round(a_release, 4)
    best["incomingImpactTime"] = round(b_impact, 4)
    best["incomingAudibleStartTime"] = round(b_audible_start, 4)
    best["keyCompatibility"] = round(key_fit, 4)
    best["outgoingTransitionKey"] = str(a.get("tailKey") or a.get("key") or "")
    best["incomingTransitionKey"] = str(b.get("headKey") or b.get("key") or "")
    best["outgoingTransitionBpm"] = round(a_bpm, 4)
    best["incomingTransitionBpm"] = round(b_bpm, 4)
    best["tempoCompatibility"] = round(tempo, 4)

    curve_lock = _curve_lock_recipe(a, b, best)
    best.update(curve_lock)
    tempo_envelope = curve_lock.get("tempoEnvelope") or []
    if tempo_envelope:
        first_lock = tempo_envelope[0]
        best["outgoingPlaybackRate"] = round(
            _clamp(_finite(first_lock.get("outgoingRate"), best.get("outgoingPlaybackRate", 1.0)), 0.94, 1.06),
            6,
        )
        best["incomingPlaybackRate"] = round(
            _clamp(_finite(first_lock.get("incomingRate"), best.get("incomingPlaybackRate", 1.0)), 0.94, 1.06),
            6,
        )
        best["outgoingLocalBpm"] = round(
            _finite(first_lock.get("outgoingBpm"), best.get("outgoingLocalBpm", a_bpm)),
            4,
        )
        best["incomingLocalBpm"] = round(
            _finite(first_lock.get("incomingBpm"), best.get("incomingLocalBpm", b_bpm)),
            4,
        )

    best["planner"] = "orb-automix-2.5-mix-v9"
    best["serverAuthoritative"] = True
    return best, candidates[:5]


@router.post("/plan")
async def plan(request: PlanRequest) -> dict[str, Any]:
    if request.version > API_VERSION:
        raise HTTPException(status_code=409, detail="unsupported Automix protocol version")
    account_hash = request.accountHash.strip().lower()
    entitled = (
        account_hash in AUTOMIX25_BETA_HASHES
        or premium_entitled_for_account_hash(account_hash)
    )
    if not request.preview or not entitled:
        raise HTTPException(status_code=403, detail="Automix 2.5 entitlement required")
    outgoing = _plan_track(request.outgoing)
    incoming = _plan_track(request.incoming)
    cache_key = _plan_cache_key(outgoing, incoming)
    cached_plan = _plan_cache_get(cache_key)
    if cached_plan is not None:
        plan_result, candidates = cached_plan
    else:
        # Candidate search is CPU work. Keep FastAPI's event loop responsive so a
        # plan request is never queued behind another client's Python planning pass.
        plan_result, candidates = await asyncio.to_thread(_remote_plan, outgoing, incoming)
        _plan_cache_put(cache_key, plan_result, candidates)
    # The selected recipe is a command from the musical planner, not a style hint. Even the
    # minimal fallback is selected here on the server; Android may only reject impossible bounds.
    plan_result = dict(plan_result)
    plan_result["serverAuthoritative"] = True
    plan_result["planner"] = "orb-automix-2.5-mix-v9"
    return {
        "version": API_VERSION,
        "automixVersion": "2.5",
        "authority": "server",
        "style": plan_result.get("style", "NO_TRANSITION"),
        "reason": plan_result.get("reason", "server-plan"),
        "plan": plan_result,
        "candidates": [
            {"style": c.get("style"), "score": c.get("score"), "reason": c.get("reason")}
            for c in candidates
        ],
    }

