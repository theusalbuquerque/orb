from __future__ import annotations

import hashlib
import os
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException

from billing_api import billing_entitlement_for_email

router = APIRouter(prefix="/api/control", tags=["server-control"])

SUPABASE_URL = os.getenv(
    "SUPABASE_URL",
    os.getenv("ORB_SUPABASE_URL", "https://twhmhdqmbvogezofvfqg.supabase.co"),
).rstrip("/")
SUPABASE_KEY = os.getenv(
    "SUPABASE_PUBLISHABLE_KEY",
    os.getenv(
        "ORB_SUPABASE_PUBLISHABLE_KEY",
        os.getenv("SUPABASE_ANON_KEY", ""),
    ),
).strip()

TIMEOUT = httpx.Timeout(15.0, connect=8.0)

DEFAULT_CONFIG: dict[str, Any] = {
    "automix25Enabled": True,
    "orbSwitchEnabled": True,
    "premiumCardEnabled": True,
    "roomsEnabled": True,
    "losslessMobileEnabled": True,
    "refreshIntervalSeconds": 60,
}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _require_supabase() -> None:
    if not SUPABASE_URL or not SUPABASE_KEY:
        raise HTTPException(
            status_code=503,
            detail="Server control requires Supabase URL and publishable key",
        )


def _rpc_headers(authorization: str | None = None) -> dict[str, str]:
    _require_supabase()
    return {
        "apikey": SUPABASE_KEY,
        "Authorization": authorization or f"Bearer {SUPABASE_KEY}",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }


async def _rpc(
    name: str,
    payload: dict[str, Any] | None = None,
    *,
    authorization: str | None = None,
) -> Any:
    _require_supabase()
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        response = await client.post(
            f"{SUPABASE_URL}/rest/v1/rpc/{name}",
            headers=_rpc_headers(authorization),
            json=payload or {},
        )
    if response.status_code >= 400:
        raise HTTPException(
            status_code=503,
            detail=f"Server control storage unavailable: {response.text[:500]}",
        )
    if not response.content:
        return None
    return response.json()


async def _authenticated_user(authorization: str | None) -> dict[str, Any]:
    _require_supabase()
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")

    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        response = await client.get(
            f"{SUPABASE_URL}/auth/v1/user",
            headers={
                "apikey": SUPABASE_KEY,
                "Authorization": authorization,
                "Accept": "application/json",
            },
        )

    if response.status_code != 200:
        raise HTTPException(status_code=401, detail="Invalid Supabase session")

    user = response.json()
    if not user.get("id"):
        raise HTTPException(status_code=401, detail="Invalid Supabase user")
    return user


def _parse_expiry(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def _resolve_entitlement(
    billing: dict[str, Any],
    override: dict[str, Any] | None,
) -> dict[str, Any]:
    if override is None or override.get("premium_override") is None:
        return {
            "premium": bool(billing.get("premium", False)),
            "plan": billing.get("plan"),
            "source": "billing",
            "expiresAt": None,
            "updatedAt": billing.get("updatedAt"),
        }

    premium = bool(override.get("premium_override"))
    expiry = _parse_expiry(override.get("premium_expires_at"))
    if premium and expiry is not None and expiry <= datetime.now(timezone.utc):
        premium = False

    return {
        "premium": premium,
        "plan": override.get("premium_plan") or billing.get("plan"),
        "source": override.get("premium_source") or "manual",
        "expiresAt": override.get("premium_expires_at"),
        "updatedAt": override.get("updated_at"),
    }


async def _remote_config() -> dict[str, Any]:
    config = dict(DEFAULT_CONFIG)
    remote = await _rpc("orb_remote_config")
    if isinstance(remote, dict):
        for key in DEFAULT_CONFIG:
            if key in remote:
                config[key] = remote[key]

    try:
        config["refreshIntervalSeconds"] = max(
            30,
            min(900, int(config.get("refreshIntervalSeconds", 60))),
        )
    except (TypeError, ValueError):
        config["refreshIntervalSeconds"] = 60

    for key in (
        "automix25Enabled",
        "orbSwitchEnabled",
        "premiumCardEnabled",
        "roomsEnabled",
        "losslessMobileEnabled",
    ):
        config[key] = bool(config.get(key, DEFAULT_CONFIG[key]))

    return config


@router.get("/health")
async def server_control_health():
    return {
        "ok": True,
        "supabaseConfigured": bool(SUPABASE_URL and SUPABASE_KEY),
        "schemaVersion": 1,
    }


@router.get("/bootstrap")
async def bootstrap(authorization: str | None = Header(default=None)):
    user = await _authenticated_user(authorization)
    email = str(user.get("email") or "").strip().lower()

    if email:
        account_hash = hashlib.sha256(email.encode("utf-8")).hexdigest()
        await _rpc(
            "orb_sync_account_hash",
            {"p_account_hash": account_hash},
            authorization=authorization,
        )

    override = await _rpc(
        "orb_my_entitlement",
        authorization=authorization,
    )
    if override is not None and not isinstance(override, dict):
        override = None

    billing = billing_entitlement_for_email(email)
    config = await _remote_config()

    return {
        "schemaVersion": 1,
        "userId": str(user["id"]),
        "entitlement": _resolve_entitlement(billing, override),
        "config": config,
        "serverTime": _now(),
    }
