"""Last.fm scrobbling proxy for Orb. Keep LASTFM_SHARED_SECRET on Render, never in the APK.

Environment: LASTFM_API_KEY, LASTFM_SHARED_SECRET.
"""
from __future__ import annotations

import hashlib
import os
import time
from typing import Optional
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/lastfm", tags=["lastfm"])
API_URL = "https://ws.audioscrobbler.com/2.0/"


class SessionRequest(BaseModel):
    token: str = Field(min_length=1, max_length=256)


class PlayingRequest(BaseModel):
    session_key: str = Field(min_length=1, max_length=256)
    artist: str = Field(min_length=1, max_length=500)
    track: str = Field(min_length=1, max_length=500)
    album: Optional[str] = Field(default=None, max_length=500)
    duration: Optional[int] = Field(default=None, ge=0, le=1000000)


class Listen(BaseModel):
    artist: str = Field(min_length=1, max_length=500)
    track: str = Field(min_length=1, max_length=500)
    album: Optional[str] = Field(default=None, max_length=500)
    duration: Optional[int] = Field(default=None, ge=0, le=1000000)
    timestamp: int = Field(gt=0)


class ScrobbleRequest(BaseModel):
    session_key: str = Field(min_length=1, max_length=256)
    listens: list[Listen] = Field(min_length=1, max_length=50)


def credentials() -> tuple[str, str]:
    key = os.getenv("LASTFM_API_KEY", "").strip()
    secret = os.getenv("LASTFM_SHARED_SECRET", "").strip()
    if not key or not secret:
        raise HTTPException(503, "Last.fm não configurado no servidor Orb")
    return key, secret


def signature(params: dict[str, str], secret: str) -> str:
    raw = "".join(k + v for k, v in sorted(params.items())) + secret
    return hashlib.md5(raw.encode("utf-8")).hexdigest()  # Last.fm protocol requires MD5.


async def lastfm_request(method: str, params: dict[str, str]) -> dict:
    key, secret = credentials()
    form = {"api_key": key, "method": method, **params}
    form["api_sig"] = signature(form, secret)
    form["format"] = "json"  # Excluded from signature, per Last.fm specification.
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(18.0, connect=8.0)) as client:
            response = await client.post(
                API_URL, data=form,
                headers={"User-Agent": "OrbMusic/1.0 (https://github.com/theusalbuquerque/orb)"},
            )
        payload = response.json()
    except (httpx.HTTPError, ValueError) as e:
        raise HTTPException(503, "Serviço Last.fm temporariamente indisponível") from e
    if not isinstance(payload, dict):
        raise HTTPException(502, "Resposta inválida do Last.fm")
    if "error" in payload:
        code = int(payload.get("error", 0))
        status = 401 if code == 9 else 503 if code in (11, 16, 29) else 400
        raise HTTPException(status, f"Last.fm ({code}): {payload.get('message', 'Erro desconhecido')}")
    if response.status_code >= 400:
        raise HTTPException(503, "Last.fm indisponível")
    return payload


@router.get("/health")
def health():
    return {"configured": bool(os.getenv("LASTFM_API_KEY") and os.getenv("LASTFM_SHARED_SECRET"))}


@router.post("/auth/start")
async def auth_start():
    key, _ = credentials()
    data = await lastfm_request("auth.getToken", {})
    token = str(data.get("token") or "")
    if not token:
        raise HTTPException(502, "Last.fm não forneceu token de autorização")
    url = "https://www.last.fm/api/auth/?" + urlencode({"api_key": key, "token": token})
    return {"token": token, "authorize_url": url}


@router.post("/auth/complete")
async def auth_complete(body: SessionRequest):
    data = await lastfm_request("auth.getSession", {"token": body.token})
    session = data.get("session") or {}
    if not session.get("key") or not session.get("name"):
        raise HTTPException(403, "Autorize o Orb na página Last.fm antes de concluir")
    return {"username": session["name"], "session_key": session["key"]}


@router.post("/now-playing")
async def now_playing(body: PlayingRequest):
    params = {"sk": body.session_key, "artist": body.artist, "track": body.track}
    if body.album:
        params["album"] = body.album
    if body.duration:
        params["duration"] = str(body.duration)
    await lastfm_request("track.updateNowPlaying", params)
    return {"ok": True}


@router.post("/scrobble")
async def scrobble(body: ScrobbleRequest):
    now = int(time.time())
    # Do not send timestamps that Last.fm will reject as future/ancient.
    for item in body.listens:
        if item.timestamp > now + 60 or item.timestamp < now - 14 * 86400:
            raise HTTPException(422, "Scrobble fora do intervalo aceito")
    params = {"sk": body.session_key}
    for i, listen in enumerate(body.listens):
        params[f"artist[{i}]"] = listen.artist
        params[f"track[{i}]"] = listen.track
        params[f"timestamp[{i}]"] = str(listen.timestamp)
        if listen.album:
            params[f"album[{i}]"] = listen.album
        if listen.duration:
            params[f"duration[{i}]"] = str(listen.duration)
    data = await lastfm_request("track.scrobble", params)
    group = data.get("scrobbles") or {}
    rows = group.get("scrobble", [])
    if isinstance(rows, dict):
        rows = [rows]
    if len(rows) != len(body.listens):
        raise HTTPException(502, "Last.fm retornou uma contagem inesperada de scrobbles")
    codes = [int((item.get("ignoredMessage") or item.get("ignoredmessage") or {}).get("code", 0)) for item in rows]
    return {"processed": len(rows), "ignored_codes": codes}
