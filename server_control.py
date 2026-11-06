from __future__ import annotations

import asyncio
import hashlib
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException

import billing_api
from billing_api import _verified_identity, subscription_for_identity

router = APIRouter(prefix="/api/control", tags=["server-control"])

SUPABASE_URL = billing_api.SUPABASE_URL
SUPABASE_KEY = billing_api.SUPABASE_KEY

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
            detail="Server control storage unavailable",
        )
    if not response.content:
        return None
    return response.json()


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
    base = {
        "premium": bool(billing.get("premium", False)),
        "plan": billing.get("plan"),
        "source": "billing",
        "expiresAt": None,
        "updatedAt": billing.get("updatedAt"),
    }
    if override is None or override.get("premium_override") is None:
        return base

    premium = bool(override.get("premium_override"))
    expiry = _parse_expiry(override.get("premium_expires_at"))
    if premium and expiry is not None and expiry <= datetime.now(timezone.utc):
        # Expired complimentary access must not revoke an independently paid plan.
        return base

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


BOOTSTRAP_BUDGET_SECONDS = 18.0


@router.get("/bootstrap")
async def bootstrap(authorization: str | None = Header(default=None)):
    try:
        async with asyncio.timeout(BOOTSTRAP_BUDGET_SECONDS):
            identity = await _verified_identity(authorization)
            # Each RPC derives its account from this same verified JWT. Parallel
            # reads avoid stacking several 15-second timeouts before showing the sheet.
            results = await asyncio.gather(
                _rpc("orb_sync_account_hash", {
                    "p_account_hash": hashlib.sha256(identity["email"].encode("utf-8")).hexdigest(),
                }, authorization=authorization),
                _rpc("orb_my_entitlement", authorization=authorization),
                _remote_config(),
                subscription_for_identity(identity, authorization),
                return_exceptions=True,
            )
            for result in results:
                if isinstance(result, BaseException):
                    raise result
            _, override, config, subscription = results
            if override is not None and not isinstance(override, dict):
                raise HTTPException(503, detail="Invalid entitlement response")
            billing = {
                "premium": subscription["premium"],
                "plan": subscription.get("premiumPlan"),
                "updatedAt": subscription.get("updatedAt"),
            }
            return {
                "schemaVersion": 1,
                "userId": identity["id"],
                "entitlement": _resolve_entitlement(billing, override),
                "config": config,
                "serverTime": _now(),
            }
    except (TimeoutError, httpx.HTTPError, ValueError, TypeError) as exc:
        raise HTTPException(503, detail={"code": "CONTROL_BOOTSTRAP_UNAVAILABLE"}) from exc
