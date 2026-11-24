"""Experimental Orb Sense ACRCloud proxy. Mount in the CURRENT Orb FastAPI app.
No credentials in the Android application; no raw sample persisted by this module.
"""
import base64
import hashlib
import hmac
import io
import os
import re
import time
import uuid
import wave

import httpx
from fastapi import APIRouter, HTTPException, Request

router = APIRouter(prefix="/api/sense", tags=["orb-sense"])
MAX_BYTES = 44 + 16000 * 2 * 8


def configuration():
    host = os.getenv("ACRCLOUD_HOST", "").strip().lower()
    key = os.getenv("ACRCLOUD_ACCESS_KEY", "").strip()
    secret = os.getenv("ACRCLOUD_ACCESS_SECRET", "").strip()
    enabled = os.getenv("ORB_SENSE_ENABLE_AUDIO_BETA", "").lower() == "true"
    # Prevent misconfigured host URLs, userinfo, redirects, or non-ACR domains.
    if not enabled or not re.fullmatch(r"identify-[a-z0-9-]+\.acrcloud\.com", host) or not key or not secret:
        return None
    return host, key, secret


async def premium_account(request: Request) -> str:
    from server_control import bootstrap
    bearer = request.headers.get("Authorization", "")
    if not re.fullmatch(r"Bearer [A-Za-z0-9._-]{20,8192}", bearer):
        raise HTTPException(401, "Authentication required")
    try:
        data = await bootstrap(authorization=bearer)
        if data.get("entitlement", {}).get("premium") is not True:
            raise HTTPException(403, "Premium required")
        return str(uuid.UUID(data["userId"]))
    except HTTPException:
        raise
    except (httpx.HTTPError, ValueError, TypeError, AttributeError, KeyError):
        raise HTTPException(503, "Premium validation unavailable") from None


def validate_wav(sample: bytes):
    if not 44 < len(sample) <= MAX_BYTES:
        raise HTTPException(400, "Invalid sample size")
    try:
        with wave.open(io.BytesIO(sample), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError()
            frames = wav.getnframes()
            if not 16000 * 6 <= frames <= 16000 * 8 or len(wav.readframes(frames)) != frames * 2:
                raise ValueError()
    except (ValueError, wave.Error, EOFError):
        raise HTTPException(400, "Invalid PCM WAV") from None


async def read_sample(request: Request) -> bytes:
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "audio/wav":
        raise HTTPException(415, "Expected audio/wav")
    result = bytearray()
    async for chunk in request.stream():
        if len(result) + len(chunk) > MAX_BYTES:
            raise HTTPException(413, "Sample too large")
        result.extend(chunk)
    sample = bytes(result)
    validate_wav(sample)
    return sample


def quota_credentials():
    key = (os.getenv("ORB_SENSE_SUPABASE_SERVICE_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_SECRET_KEY") or "").strip()
    if not key:
        return None
    url = (os.getenv("ORB_SUPABASE_URL") or os.getenv("SUPABASE_URL") or "https://twhmhdqmbvogezofvfqg.supabase.co").rstrip("/")
    if not re.fullmatch(r"https://[a-z0-9]+\.supabase\.co", url):
        return None
    headers = {"apikey": key, "Accept": "application/json"}
    if not key.startswith("sb_secret_"):
        headers["Authorization"] = f"Bearer {key}"
    return url, headers


async def quota_rpc(client, function, payload):
    credentials = quota_credentials()
    if not credentials:
        raise HTTPException(503, "Quota storage not configured")
    url, headers = credentials
    try:
        result = await client.post(f"{url}/rest/v1/rpc/{function}", headers=headers, json=payload)
        result.raise_for_status()
        value = result.json()
        if type(value) is not bool:
            raise ValueError("Invalid quota response")
        return value
    except (httpx.HTTPError, ValueError, TypeError):
        raise HTTPException(503, "Recognition quota unavailable") from None


async def reserve_quota(client, account):
    try:
        per_user = min(200, max(1, int(os.getenv("ORB_SENSE_DAILY_PER_USER", "30"))))
        global_limit = min(10000, max(1, int(os.getenv("ORB_SENSE_DAILY_GLOBAL", "300"))))
    except ValueError:
        raise HTTPException(503, "Invalid recognition quota limits") from None
    if not await quota_rpc(client, "orb_sense_reserve_quota", {
        "p_account_id": account, "p_user_limit": per_user, "p_global_limit": global_limit,
    }):
        raise HTTPException(429, "Recognition quota reached")


def signature(key: str, secret: str, timestamp: str) -> str:
    payload = "\n".join(("POST", "/v1/identify", key, "audio", "1", timestamp))
    return base64.b64encode(hmac.new(secret.encode(), payload.encode(), hashlib.sha1).digest()).decode()


def parse_match(data: dict):
    code = data.get("status", {}).get("code")
    if code == 1001:
        return None
    if code != 0:
        raise HTTPException(502, "Recognition provider error")
    for item in data.get("metadata", {}).get("music", []):
        if not isinstance(item, dict):
            continue
        title = item.get("title")
        artists = item.get("artists") or []
        artist = ", ".join(a["name"].strip() for a in artists if isinstance(a, dict) and isinstance(a.get("name"), str) and a["name"].strip())
        score = item.get("score")
        if not isinstance(score, (int, float)) or score < 80:
            continue
        if isinstance(title, str) and title.strip() and artist:
            return {"title": title.strip()[:240], "artist": artist[:240], "acrId": str(item.get("acrid") or "")[:128]}
    return None


@router.get("/health")
async def sense_health():
    return {"ok": True, "audioBetaEnabled": os.getenv("ORB_SENSE_ENABLE_AUDIO_BETA", "").lower() == "true", "quotaBackend": "supabase", "quotaCredentialsConfigured": quota_credentials() is not None}


@router.get("/status")
async def sense_status(request: Request):
    await premium_account(request)
    configured = False
    if configuration() is not None and quota_credentials() is not None:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False) as client:
            configured = await quota_rpc(client, "orb_sense_quota_health", {})
    return {"configured": configured, "mode": "audio_beta"}


@router.post("/identify")
async def identify(request: Request):
    config = configuration()
    if not config:
        raise HTTPException(503, "Recognition not configured")
    host, key, secret = config
    async with httpx.AsyncClient(timeout=12, follow_redirects=False) as client:
        account = await premium_account(request)
        sample = await read_sample(request)
        await reserve_quota(client, account)
        ts = str(int(time.time()))
        try:
            res = await client.post(f"https://{host}/v1/identify", data={
                "access_key": key, "sample_bytes": str(len(sample)), "timestamp": ts,
                "signature": signature(key, secret, ts), "data_type": "audio", "signature_version": "1",
            }, files={"sample": ("sense.wav", sample, "audio/wav")})
            res.raise_for_status()
            track = parse_match(res.json())
        except HTTPException:
            raise
        except (httpx.HTTPError, ValueError, TypeError, AttributeError, KeyError):
            raise HTTPException(502, "Recognition provider unavailable") from None
        return {"track": track}
