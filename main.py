import os
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import PlainTextResponse

app = FastAPI(title="Orb Play Qobuz Module", version="2.0.0")

QOBUZ_TOKEN = os.getenv("QOBUZ_TOKEN", "").strip()
QOBUZ_APP_ID = os.getenv("QOBUZ_APP_ID", "243542385").strip()
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://orb-4mrh.onrender.com").rstrip("/")

QOBUZ_API = "https://www.qobuz.com/api.json/0.2"
REQUEST_TIMEOUT = httpx.Timeout(20.0, connect=10.0)

# Qobuz format ids commonly used by the public web API.
# We try the best lossless tier first and progressively fall back.
LOSSLESS_FORMATS = [27, 7, 6]
HIGH_FORMATS = [5, 6]


def qobuz_headers() -> dict[str, str]:
    if not QOBUZ_TOKEN:
        raise HTTPException(
            status_code=503,
            detail="QOBUZ_TOKEN is not configured on the server",
        )

    return {
        "X-User-Auth-Token": QOBUZ_TOKEN,
        "X-App-Id": QOBUZ_APP_ID,
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/130.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json",
    }


def safe_int(value: Any) -> int | None:
    try:
        if value is None:
            return None
        return int(float(value))
    except (TypeError, ValueError):
        return None


def safe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def artist_name(track: dict[str, Any]) -> str:
    performer = track.get("performer") or {}
    artist = track.get("artist") or {}
    return (
        performer.get("name")
        or artist.get("name")
        or track.get("performer_name")
        or ""
    )


def album_cover(album: dict[str, Any]) -> str | None:
    image = album.get("image") or {}
    return (
        image.get("large")
        or image.get("extralarge")
        or image.get("small")
        or None
    )


def quality_label(track: dict[str, Any]) -> str:
    bit_depth = safe_int(track.get("maximum_bit_depth") or track.get("bit_depth"))
    sample_rate = safe_float(
        track.get("maximum_sampling_rate")
        or track.get("sampling_rate")
        or track.get("sample_rate")
    )

    parts = ["FLAC"]
    if bit_depth:
        parts.append(f"{bit_depth}-bit")
    if sample_rate:
        parts.append(f"{sample_rate:g}kHz")
    return " / ".join(parts)


def normalize_search_track(track: dict[str, Any]) -> dict[str, Any]:
    album = track.get("album") or {}
    track_id = str(track.get("id") or "")

    return {
        "id": track_id,
        "title": track.get("title") or "",
        "artist": artist_name(track),
        "artistId": str((track.get("performer") or {}).get("id") or "") or None,
        "album": album.get("title") or "",
        "albumId": str(album.get("id") or "") or None,
        "albumCover": album_cover(album),
        "duration": safe_int(track.get("duration")) or 0,
        "trackNumber": safe_int(track.get("track_number")) or 0,
        "audioQuality": quality_label(track),
        "format": "flac",
        "availableQualities": ["LOSSLESS", "HIGH"],
    }


def infer_stream_metadata(data: dict[str, Any], format_id: int) -> dict[str, Any]:
    bit_depth = safe_int(
        data.get("bit_depth")
        or data.get("bitDepth")
        or data.get("maximum_bit_depth")
    )

    sample_rate_khz = safe_float(
        data.get("sampling_rate")
        or data.get("sample_rate")
        or data.get("sampleRate")
    )

    # ModuleSource expects sampleRate in Hz, not kHz.
    sample_rate_hz: float | None = None
    if sample_rate_khz:
        sample_rate_hz = (
            sample_rate_khz * 1000.0
            if sample_rate_khz < 1000
            else sample_rate_khz
        )

    mime_type = (
        data.get("mime_type")
        or data.get("mimeType")
        or ("audio/mpeg" if format_id == 5 else "audio/flac")
    )

    if format_id == 5:
        quality = "HIGH 320kbps"
    else:
        quality_parts = ["LOSSLESS"]
        if bit_depth:
            quality_parts.append(f"{bit_depth}-bit")
        if sample_rate_hz:
            quality_parts.append(f"{sample_rate_hz / 1000.0:g}kHz")
        quality = " ".join(quality_parts)

    return {
        "audioQuality": quality,
        "mimeType": mime_type,
        "bitDepth": bit_depth,
        "sampleRate": sample_rate_hz,
        "audioModes": None,
    }


@app.get("/")
async def module_index():
    """Convx-compatible module index consumed by ModuleIndex.kt."""
    return {
        "category:music": [
            {
                "id": "orb-qobuz-lossless",
                "name": "Orb Qobuz Lossless",
                "author": "Orb",
                "version": "2.0.0",
                "code": 2,
                "type": "MODULE",
                "description": "Qobuz FLAC/Lossless source for Orb Play",
                "tags": ["MUSIC", "LOSSLESS", "FLAC", "HI-RES"],
                "size": 0,
                "sizeLabel": "",
                "download": f"{PUBLIC_BASE_URL}/qobuz.js",
                "trusted": True,
                "featured": True,
                "nsfw": False,
                "sources": [
                    {
                        "name": "Qobuz",
                        "lang": "all",
                        "id": "qobuz",
                        "baseUrl": "https://www.qobuz.com",
                    }
                ],
            }
        ]
    }


@app.get("/qobuz.js", response_class=PlainTextResponse)
async def qobuz_module_js():
    """Serve the JS plugin downloaded and executed by QuickJsExecutor."""
    try:
        with open("qobuz.js", "r", encoding="utf-8") as file:
            return PlainTextResponse(
                file.read(),
                media_type="application/javascript; charset=utf-8",
            )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="qobuz.js not found") from exc


@app.get("/api/search")
async def search_tracks(
    query: str = Query(..., min_length=1),
    limit: int = Query(15, ge=1, le=50),
):
    headers = qobuz_headers()

    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT, follow_redirects=True) as client:
        response = await client.get(
            f"{QOBUZ_API}/catalog/search",
            params={
                "query": query,
                "type": "tracks",
                "limit": limit,
            },
            headers=headers,
        )

    if response.status_code >= 400:
        raise HTTPException(
            status_code=502,
            detail=f"Qobuz search failed: HTTP {response.status_code}",
        )

    try:
        data = response.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="Qobuz returned invalid JSON") from exc

    items = (data.get("tracks") or {}).get("items") or []
    tracks = [normalize_search_track(item) for item in items if item.get("id")]

    return {
        "tracks": tracks,
        "total": len(tracks),
    }


@app.get("/api/stream")
async def stream_track(
    track_id: str = Query(..., min_length=1),
    quality: str = Query("LOSSLESS"),
):
    headers = qobuz_headers()
    requested = quality.upper().strip()
    format_ids = LOSSLESS_FORMATS if requested == "LOSSLESS" else HIGH_FORMATS

    last_error = "No stream returned"

    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT, follow_redirects=True) as client:
        for format_id in format_ids:
            try:
                response = await client.get(
                    f"{QOBUZ_API}/track/getFileUrl",
                    params={
                        "track_id": track_id,
                        "format_id": format_id,
                    },
                    headers=headers,
                )
            except httpx.HTTPError as exc:
                last_error = str(exc)
                continue

            if response.status_code >= 400:
                last_error = f"HTTP {response.status_code} for format_id={format_id}"
                continue

            try:
                data = response.json()
            except ValueError:
                last_error = f"Invalid JSON for format_id={format_id}"
                continue

            url = data.get("url")
            if not url:
                last_error = (
                    data.get("message")
                    or data.get("error")
                    or f"Empty URL for format_id={format_id}"
                )
                continue

            meta = infer_stream_metadata(data, format_id)

            # A strict LOSSLESS request must never silently return MP3.
            if requested == "LOSSLESS" and not str(meta["mimeType"]).lower().endswith("flac"):
                last_error = f"format_id={format_id} returned non-FLAC audio"
                continue

            return {
                "streamUrl": url,
                "track": {
                    "id": str(track_id),
                    **meta,
                },
            }

    # Return the exact ModuleStreamResponse shape with an empty URL.
    # ModuleSource treats this as "this source cannot serve the track" and
    # SourceResolver can continue to another source / YouTube fallback.
    return {
        "streamUrl": "",
        "track": {
            "id": str(track_id),
            "audioQuality": requested,
            "mimeType": None,
            "bitDepth": None,
            "sampleRate": None,
            "audioModes": None,
        },
        "error": last_error,
    }


@app.get("/health")
async def health():
    return {
        "ok": True,
        "module": "orb-qobuz-lossless",
        "qobuzTokenConfigured": bool(QOBUZ_TOKEN),
        "appIdConfigured": bool(QOBUZ_APP_ID),
    }
