import os
import re
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse
from orb_automix_api import router as automix_router
from billing_api import router as billing_router
from orb_owner_zero_auth_api import router as owner_zda_router

app = FastAPI(title="Orb Play Qobuz Module", version="2.0.0")

# Browser/PWA clients need explicit CORS because the Android client is not
# subject to the browser same-origin policy. Configure production origins as
# a comma-separated env var, e.g. https://app.example.com.
ORB_WEB_ORIGINS = [
    value.strip()
    for value in os.getenv("ORB_WEB_ORIGINS", "http://localhost:5173,http://localhost:8080").split(",")
    if value.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=ORB_WEB_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "OPTIONS"],
    allow_headers=["*"],
)
app.include_router(automix_router)
app.include_router(billing_router)
app.include_router(owner_zda_router)

QOBUZ_TOKEN = os.getenv("QOBUZ_TOKEN", "").strip()
QOBUZ_APP_ID = os.getenv("QOBUZ_APP_ID", "243542385").strip()
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://orb-4mrh.onrender.com").rstrip("/")

QOBUZ_API = "https://www.qobuz.com/api.json/0.2"
YT_MUSIC_API = "https://music.youtube.com/youtubei/v1"
YT_MUSIC_ORIGIN = "https://music.youtube.com"
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
                "app_id": QOBUZ_APP_ID,
                "user_auth_token": QOBUZ_TOKEN,
            },
            headers=headers,
        )

    if response.status_code >= 400:
        body = response.text[:1000]
        raise HTTPException(
            status_code=502,
            detail=(
                f"Qobuz search failed: HTTP {response.status_code} - {body}"
            ),
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


def normalize_featured_album(album: dict[str, Any]) -> dict[str, Any]:
    artist = album.get("artist") or album.get("performer") or {}
    image = album.get("image") or {}
    return {
        "id": str(album.get("id") or ""),
        "title": album.get("title") or "",
        "artist": artist.get("name") or album.get("artist_name") or "",
        "album": album.get("title") or "",
        "albumCover": (
            image.get("extralarge")
            or image.get("large")
            or image.get("small")
            or image.get("thumbnail")
        ),
        "releaseDate": (
            album.get("release_date_original")
            or album.get("release_date_stream")
            or album.get("release_date_download")
        ),
        "type": "album",
    }


def qobuz_catalog_headers() -> dict[str, str]:
    """Headers for public/editorial catalogue calls.

    album/getFeatured is an editorial catalogue route. Unlike stream/user-data
    endpoints it does not need the user's Qobuz auth token; some deployments
    reject the extra user token on this route with HTTP 400.
    """
    return {
        "X-App-Id": QOBUZ_APP_ID,
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/141.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json",
    }


def _runs_text(value: Any) -> str:
    if not isinstance(value, dict):
        return ""
    runs = value.get("runs")
    if not isinstance(runs, list):
        return ""
    return "".join(
        str(run.get("text") or "")
        for run in runs
        if isinstance(run, dict)
    ).strip()


def _walk_named_objects(value: Any, name: str):
    if isinstance(value, dict):
        found = value.get(name)
        if isinstance(found, dict):
            yield found
        for child in value.values():
            yield from _walk_named_objects(child, name)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_named_objects(child, name)


def _best_yt_thumbnail(renderer: dict[str, Any]) -> str | None:
    thumb_renderer = (
        ((renderer.get("thumbnailRenderer") or {}).get("musicThumbnailRenderer"))
        if isinstance(renderer.get("thumbnailRenderer"), dict)
        else None
    )
    if not isinstance(thumb_renderer, dict):
        thumb_renderer = next(_walk_named_objects(renderer, "musicThumbnailRenderer"), None)
    if not isinstance(thumb_renderer, dict):
        return None
    thumbnail = thumb_renderer.get("thumbnail") or {}
    thumbnails = thumbnail.get("thumbnails") if isinstance(thumbnail, dict) else None
    if not isinstance(thumbnails, list) or not thumbnails:
        return None
    usable = [item for item in thumbnails if isinstance(item, dict) and item.get("url")]
    if not usable:
        return None
    best = max(
        usable,
        key=lambda item: int(item.get("width") or 0) * int(item.get("height") or 0),
    )
    return str(best.get("url") or "") or None


def _yt_artist_label(subtitle: str, title: str) -> str:
    media_labels = {"album", "álbum", "single", "sencillo", "ep", "music", "música"}
    pieces = [part.strip() for part in re.split(r"[•·]", subtitle) if part.strip()]
    for part in pieces:
        normalized = part.lower()
        if normalized in media_labels:
            continue
        if re.fullmatch(r"\d{4}", normalized):
            continue
        return part
    return subtitle.strip() or title


def _normalize_yt_release(renderer: dict[str, Any]) -> dict[str, Any] | None:
    title = _runs_text(renderer.get("title") or {})
    if not title:
        return None

    endpoint = renderer.get("navigationEndpoint") or {}
    browse_endpoint = endpoint.get("browseEndpoint") if isinstance(endpoint, dict) else None
    if not isinstance(browse_endpoint, dict):
        return None

    browse_id = str(browse_endpoint.get("browseId") or "")
    configs = browse_endpoint.get("browseEndpointContextSupportedConfigs") or {}
    music_config = (
        configs.get("browseEndpointContextMusicConfig")
        if isinstance(configs, dict)
        else {}
    ) or {}
    page_type = str(music_config.get("pageType") or "") if isinstance(music_config, dict) else ""

    # Same rule as Orb Android's WelcomeLoginScreen: albums only.
    if "ALBUM" not in page_type and not browse_id.startswith("MPRE"):
        return None

    artwork = _best_yt_thumbnail(renderer)
    if not artwork:
        return None

    subtitle = _runs_text(renderer.get("subtitle") or {})
    return {
        "id": browse_id or artwork,
        "title": title,
        "artist": _yt_artist_label(subtitle, title),
        "album": title,
        "albumCover": artwork,
        "releaseDate": None,
        "type": "album",
        "browseId": browse_id or None,
    }


async def _youtube_music_welcome_releases(
    client: httpx.AsyncClient,
    limit: int,
) -> tuple[list[dict[str, Any]], str | None]:
    version = f"1.{datetime.now(timezone.utc).strftime('%Y%m%d')}.01.00"
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Origin": YT_MUSIC_ORIGIN,
        "Referer": f"{YT_MUSIC_ORIGIN}/",
        "X-Origin": YT_MUSIC_ORIGIN,
        "X-YouTube-Client-Name": "67",
        "X-YouTube-Client-Version": version,
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/141.0.0.0 Safari/537.36"
        ),
    }
    payload = {
        "context": {
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": version,
                "hl": "en",
                "gl": "US",
            },
            "user": {"lockedSafetyMode": False},
            "request": {"useSsl": True},
        },
        "browseId": "FEmusic_new_releases",
        "contentCheckOk": True,
        "racyCheckOk": True,
    }

    try:
        response = await client.post(
            f"{YT_MUSIC_API}/browse",
            params={"prettyPrint": "false"},
            headers=headers,
            json=payload,
        )
    except httpx.HTTPError as exc:
        return [], f"YouTube Music transport: {exc}"

    if response.status_code >= 400:
        return [], f"YouTube Music HTTP {response.status_code}: {response.text[:240]}"

    try:
        data = response.json()
    except ValueError:
        return [], "YouTube Music returned invalid JSON"

    releases: list[dict[str, Any]] = []
    seen: set[str] = set()
    for renderer in _walk_named_objects(data, "musicTwoRowItemRenderer"):
        item = _normalize_yt_release(renderer)
        if not item:
            continue
        key = str(item.get("browseId") or item.get("albumCover") or "")
        if not key or key in seen:
            continue
        seen.add(key)
        releases.append(item)
        if len(releases) >= limit:
            break

    return releases, None if releases else "YouTube Music returned no album cards"


async def _qobuz_welcome_releases(
    client: httpx.AsyncClient,
    limit: int,
) -> tuple[list[dict[str, Any]], str | None, str | None]:
    last_error = "No featured albums returned"
    headers = qobuz_catalog_headers()

    for featured_type in ("new-releases-full", "recent-releases", "new-releases"):
        try:
            response = await client.get(
                f"{QOBUZ_API}/album/getFeatured",
                params={
                    "type": featured_type,
                    "limit": limit,
                    "offset": 0,
                    "app_id": QOBUZ_APP_ID,
                },
                headers=headers,
            )
        except httpx.HTTPError as exc:
            last_error = str(exc)
            continue

        if response.status_code >= 400:
            last_error = (
                f"HTTP {response.status_code} for type={featured_type}: "
                f"{response.text[:240]}"
            )
            continue

        try:
            data = response.json()
        except ValueError:
            last_error = f"Invalid JSON for type={featured_type}"
            continue

        raw = data.get("albums") or {}
        items = raw.get("items") if isinstance(raw, dict) else raw
        if not isinstance(items, list):
            items = data.get("items") if isinstance(data.get("items"), list) else []

        releases = [
            normalize_featured_album(item)
            for item in items
            if isinstance(item, dict) and item.get("id")
        ]
        releases = [item for item in releases if item.get("albumCover")]
        if releases:
            return releases[:limit], None, featured_type

    return [], last_error, None


@app.get("/api/welcome/releases")
async def welcome_releases(
    limit: int = Query(30, ge=1, le=50),
):
    """Album artwork pool for Orb's signed-out welcome screen.

    Primary source deliberately matches Android: YouTube Music's public
    FEmusic_new_releases browse surface. Qobuz is retained only as a resilient
    catalogue fallback and is queried without a user-auth token because
    album/getFeatured is an editorial/public catalogue route.
    """
    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT, follow_redirects=True) as client:
        yt_releases, yt_error = await _youtube_music_welcome_releases(client, limit)
        if yt_releases:
            return {
                "releases": yt_releases,
                "total": len(yt_releases),
                "source": "youtube-music:FEmusic_new_releases",
            }

        qobuz_releases, qobuz_error, featured_type = await _qobuz_welcome_releases(client, limit)
        if qobuz_releases:
            return {
                "releases": qobuz_releases,
                "total": len(qobuz_releases),
                "source": "qobuz-featured-fallback",
                "featuredType": featured_type,
                "primarySourceError": yt_error,
            }

    raise HTTPException(
        status_code=502,
        detail={
            "message": "Welcome releases unavailable",
            "youtubeMusic": yt_error,
            "qobuz": qobuz_error,
        },
    )


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
                        "app_id": QOBUZ_APP_ID,
                        "user_auth_token": QOBUZ_TOKEN,
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
