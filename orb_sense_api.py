"""Experimental Orb Sense ACRCloud proxy. Mount in the CURRENT Orb FastAPI app.
No credentials in the Android application; no raw sample persisted by this module.
"""
import asyncio
import logging
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
import jwt
from fastapi import APIRouter, HTTPException, Request

router = APIRouter(prefix="/api/sense", tags=["orb-sense"])
MAX_BYTES = 44 + 16000 * 2 * 8
_logger = logging.getLogger("orb.sense")


def acr_credentials():
    host = os.getenv("ACRCLOUD_HOST", "").strip().lower()
    key = os.getenv("ACRCLOUD_ACCESS_KEY", "").strip()
    secret = os.getenv("ACRCLOUD_ACCESS_SECRET", "").strip()
    # Prevent misconfigured host URLs, userinfo, redirects, or non-ACR domains.
    if not re.fullmatch(r"identify-[a-z0-9-]+\.acrcloud\.com", host) or not key or not secret:
        return None
    return host, key, secret


def audio_enabled():
    return os.getenv("ORB_SENSE_ENABLE_AUDIO_BETA", "").lower() == "true"


def owner_only():
    # Fail closed: only an explicit false opens this experimental pilot to other Premium accounts.
    return os.getenv("ORB_SENSE_BETA_OWNER_ONLY", "true").lower() != "false"


def configuration():
    return acr_credentials() if audio_enabled() else None


# SHA-256 of the confirmed developer account's immutable UUID. No secret key.
_OWNER_SUB_HASH = "255dd8ade57c59161eb7e7fb4d4597f674fa819bd6b88bbc80ea81ddeb89ecc0"
_PROJECT_ISSUER = "https://twhmhdqmbvogezofvfqg.supabase.co/auth/v1"
_JWKS_TTL = 300.0
_jwks = {}
_jwks_until = 0.0
_jwks_lock = asyncio.Lock()


def is_pilot_owner(account: str) -> bool:
    return hmac.compare_digest(hashlib.sha256(account.encode()).hexdigest(), _OWNER_SUB_HASH)


async def _signing_key(kid: str):
    global _jwks, _jwks_until
    async with _jwks_lock:
        now = time.monotonic()
        if now >= _jwks_until:
            async with httpx.AsyncClient(timeout=httpx.Timeout(6.0, connect=4.0), follow_redirects=False) as client:
                response = await client.get(_PROJECT_ISSUER + "/.well-known/jwks.json")
                response.raise_for_status()
                keys = response.json()["keys"]
            if not isinstance(keys, list):
                raise ValueError("Invalid JWKS")
            _jwks = {k["kid"]: k for k in keys if isinstance(k, dict) and isinstance(k.get("kid"), str) and k.get("alg") == "ES256" and k.get("kty") == "EC" and k.get("crv") == "P-256" and "verify" in k.get("key_ops", [])}
            _jwks_until = time.monotonic() + _JWKS_TTL
        key = _jwks.get(kid)
        if key is None:
            raise HTTPException(401, "Unknown signing key; refresh session")
        return jwt.PyJWK.from_dict(key, algorithm="ES256").key


async def verify_pilot_identity(bearer: str) -> str:
    token = bearer.split(" ", 1)[1]
    try:
        header = jwt.get_unverified_header(token)
        if header.get("alg") == "HS256":
            # Legacy access tokens still require the official remote Auth check.
            from billing_api import _verified_identity
            identity = await _verified_identity(bearer)
            account = str(uuid.UUID(identity["id"]))
            if identity.get("developer") is not True or not is_pilot_owner(account):
                raise HTTPException(403, "Recognition pilot is restricted")
            return account
        if header.get("alg") != "ES256" or not isinstance(header.get("kid"), str):
            raise HTTPException(401, "Unsupported session signing key")
        key = await _signing_key(header["kid"])
        claims = jwt.decode(token, key, algorithms=["ES256"], audience="authenticated", issuer=_PROJECT_ISSUER,
                            options={"require": ["exp", "iat", "iss", "aud", "sub", "role", "session_id", "is_anonymous"]})
        if claims["role"] != "authenticated" or claims["is_anonymous"] is not False:
            raise HTTPException(403, "Authenticated account required")
        account = str(uuid.UUID(claims["sub"]))
        uuid.UUID(claims["session_id"])
        if not is_pilot_owner(account):
            raise HTTPException(403, "Recognition pilot is restricted")
        return account
    except jwt.InvalidTokenError:
        raise HTTPException(401, "Invalid or expired session") from None


async def premium_account(request: Request) -> str:
    # The app already gates Orb Lock. The paid proxy must independently
    # authenticate its caller; it must not run the whole app bootstrap.
    from billing_api import _verified_identity
    bearer = request.headers.get("Authorization", "")
    if not re.fullmatch(r"Bearer [A-Za-z0-9._-]{20,8192}", bearer):
        raise HTTPException(401, "Authentication required")
    stage = "identity"
    try:
        async with asyncio.timeout(25.0):
            if owner_only():
                stage = "pilot_token"
                return await verify_pilot_identity(bearer)
            identity = await _verified_identity(bearer)
            account = str(uuid.UUID(identity["id"]))

            # Future public Premium mode still enforces the server entitlement.
            from billing_api import subscription_for_identity
            from server_control import _rpc, _resolve_entitlement
            stage = "entitlement"
            override = await _rpc("orb_my_entitlement", authorization=bearer)
            if override is not None and not isinstance(override, dict):
                raise ValueError("Invalid entitlement response")
            stage = "billing"
            subscription = await subscription_for_identity(
                identity, bearer, allow_legacy_recovery=False
            )
            billing = {
                "premium": subscription["premium"],
                "plan": subscription.get("premiumPlan"),
                "updatedAt": subscription.get("updatedAt"),
                "renewsAt": subscription.get("nextRenewalAt"),
            }
            if _resolve_entitlement(billing, override)["premium"] is not True:
                raise HTTPException(403, "Premium required")
            return account
    except HTTPException as exc:
        _logger.info("Orb Sense authorization: stage=%s status=%s", stage, exc.status_code)
        raise
    except (TimeoutError, httpx.HTTPError, jwt.PyJWTError, ValueError, TypeError, AttributeError, KeyError) as exc:
        _logger.warning("Orb Sense authorization unavailable: stage=%s type=%s", stage, type(exc).__name__)
        raise HTTPException(503, "Recognition authorization unavailable") from None


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
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        _logger.warning("Orb Sense quota unavailable: rpc=%s type=%s", function, type(exc).__name__)
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


_quota_startup_ok = False


@router.on_event("startup")
async def check_quota_on_startup():
    global _quota_startup_ok
    _quota_startup_ok = False
    if quota_credentials() is None:
        return
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(5.0, connect=3.0), follow_redirects=False) as client:
            _quota_startup_ok = await quota_rpc(client, "orb_sense_quota_health", {})
    except HTTPException:
        # The rest of Orb must still start. Recognition itself fails closed.
        return


@router.get("/health")
async def sense_health():
    return {
        "ok": True,
        "audioBetaEnabled": audio_enabled(),
        "ownerOnly": owner_only(),
        "authorizationMode": "signed_token_owner_pilot" if owner_only() else "scoped_premium",
        "statusMode": "configuration_only",
        "bootstrapRequired": False,
        "acrCredentialsConfigured": acr_credentials() is not None,
        "quotaBackend": "supabase",
        "quotaCredentialsConfigured": quota_credentials() is not None,
        "quotaStartupVerified": _quota_startup_ok,
    }


@router.get("/status")
async def sense_status():
    # Public configuration readiness, equivalent to the existing public health
    # endpoint. Does not authorize uploads, consume quotas or call ACRCloud.
    # Every identify request must still authenticate and reserve quota.
    configured = configuration() is not None and quota_credentials() is not None
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
