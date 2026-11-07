from __future__ import annotations

import asyncio
import hashlib
import hmac
import math
import os
import sqlite3
import uuid
from weakref import WeakValueDictionary
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

import httpx
from fastapi import APIRouter, Header, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

router = APIRouter(prefix="/api/billing", tags=["billing"])

MP_API = "https://api.mercadopago.com"
ASAAS_API = os.getenv("ASAAS_API_BASE_URL", "https://api.asaas.com/v3").rstrip("/")
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://orb-4mrh.onrender.com").rstrip("/")
REQUEST_TIMEOUT = httpx.Timeout(25.0, connect=10.0)

MP_ACCESS_TOKEN = os.getenv("MERCADO_PAGO_ACCESS_TOKEN", "").strip()
MP_WEBHOOK_SECRET = os.getenv("MERCADO_PAGO_WEBHOOK_SECRET", "").strip()
ASAAS_API_KEY = os.getenv("ASAAS_API_KEY", "").strip()
ASAAS_WEBHOOK_TOKEN = os.getenv("ASAAS_WEBHOOK_TOKEN", "").strip()

# The exception is server-controlled and requires a confirmed Auth identity.
DEVELOPER_EMAIL_HASH = "2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb"
SUPABASE_URL = os.getenv("ORB_SUPABASE_URL", os.getenv("SUPABASE_URL", "https://twhmhdqmbvogezofvfqg.supabase.co")).rstrip("/")
SUPABASE_KEY = os.getenv("ORB_SUPABASE_PUBLISHABLE_KEY", os.getenv("SUPABASE_PUBLISHABLE_KEY", os.getenv("SUPABASE_ANON_KEY", ""))).strip()

DB_PATH = os.getenv("ORB_BILLING_DB_PATH", "orb_billing.sqlite3").strip()


def _payment_email(value: str | None) -> str | None:
    if value is None:
        return None
    import re
    value = value.strip().lower()
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", value) or len(value) > 254:
        raise ValueError("invalid payment email")
    return value


class CheckoutRequest(BaseModel):
    customer_ref: str = Field(min_length=8, max_length=200)
    email: str = Field(min_length=5, max_length=254)
    name: str | None = Field(default=None, max_length=200)
    plan: Literal["monthly", "yearly"]
    payer_email: str | None = Field(default=None, min_length=5, max_length=254)

    @field_validator("payer_email")
    @classmethod
    def validate_payer_email(cls, value: str | None) -> str | None:
        return _payment_email(value)

    @field_validator("email")
    @classmethod
    def validate_email(cls, value: str) -> str:
        email = value.strip()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("invalid email")
        return email


def _price(plan: str) -> float:
    key = "ORB_PREMIUM_MONTHLY_PRICE" if plan == "monthly" else "ORB_PREMIUM_YEARLY_PRICE"
    raw = os.getenv(key, "").strip()
    try:
        value = round(float(raw), 2)
    except (TypeError, ValueError):
        value = 0.0
    if not math.isfinite(value) or value <= 0:
        raise HTTPException(status_code=503, detail=f"{key} is not configured")
    return value


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _db() -> sqlite3.Connection:
    if DB_PATH != ":memory:":
        Path(DB_PATH).expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS billing_subscriptions (
            checkout_ref TEXT PRIMARY KEY,
            customer_ref TEXT NOT NULL,
            provider TEXT NOT NULL,
            provider_subscription_id TEXT,
            checkout_id TEXT,
            email TEXT NOT NULL,
            plan TEXT NOT NULL,
            amount REAL NOT NULL,
            currency TEXT NOT NULL DEFAULT 'BRL',
            status TEXT NOT NULL,
            checkout_url TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_billing_provider_subscription "
        "ON billing_subscriptions(provider, provider_subscription_id)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_billing_checkout_id "
        "ON billing_subscriptions(provider, checkout_id)"
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS billing_events (
            provider TEXT NOT NULL,
            event_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(provider, event_id)
        )
        """
    )
    conn.commit()
    return conn


def _save_checkout(
    *,
    checkout_ref: str,
    customer_ref: str,
    provider: str,
    provider_subscription_id: str | None,
    checkout_id: str | None,
    email: str,
    plan: str,
    amount: float,
    status: str,
    checkout_url: str | None,
) -> None:
    now = _now()
    with _db() as conn:
        conn.execute(
            """
            INSERT INTO billing_subscriptions (
                checkout_ref, customer_ref, provider, provider_subscription_id,
                checkout_id, email, plan, amount, currency, status,
                checkout_url, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BRL', ?, ?, ?, ?)
            ON CONFLICT(checkout_ref) DO UPDATE SET
                provider_subscription_id=excluded.provider_subscription_id,
                checkout_id=excluded.checkout_id,
                status=excluded.status,
                checkout_url=excluded.checkout_url,
                updated_at=excluded.updated_at
            """,
            (
                checkout_ref,
                customer_ref,
                provider,
                provider_subscription_id,
                checkout_id,
                email,
                plan,
                amount,
                status,
                checkout_url,
                now,
                now,
            ),
        )
        conn.commit()


def _update_by_checkout_ref(
    checkout_ref: str,
    *,
    status: str | None = None,
    provider_subscription_id: str | None = None,
) -> None:
    sets: list[str] = ["updated_at=?"]
    values: list[Any] = [_now()]
    if status is not None:
        sets.append("status=?")
        values.append(status)
    if provider_subscription_id is not None:
        sets.append("provider_subscription_id=?")
        values.append(provider_subscription_id)
    values.append(checkout_ref)
    with _db() as conn:
        conn.execute(
            f"UPDATE billing_subscriptions SET {', '.join(sets)} WHERE checkout_ref=? AND status!='canceled'",
            values,
        )
        conn.commit()


def _update_by_provider_id(
    provider: str,
    provider_subscription_id: str,
    *,
    status: str,
) -> None:
    with _db() as conn:
        conn.execute(
            """
            UPDATE billing_subscriptions
            SET status=?, updated_at=?
            WHERE provider=? AND provider_subscription_id=? AND status!='canceled'
            """,
            (status, _now(), provider, provider_subscription_id),
        )
        conn.commit()


def _update_by_checkout_id(provider: str, checkout_id: str, *, status: str) -> None:
    with _db() as conn:
        conn.execute(
            """
            UPDATE billing_subscriptions
            SET status=?, updated_at=?
            WHERE provider=? AND checkout_id=? AND status!='canceled'
            """,
            (status, _now(), provider, checkout_id),
        )
        conn.commit()


def _event_once(provider: str, event_id: str) -> bool:
    try:
        with _db() as conn:
            conn.execute(
                "INSERT INTO billing_events(provider, event_id, created_at) VALUES (?, ?, ?)",
                (provider, event_id, _now()),
            )
            conn.commit()
        return True
    except sqlite3.IntegrityError:
        return False


def _normalize_mp_status(value: str | None) -> str:
    return {
        "authorized": "active",
        "pending": "pending",
        "paused": "paused",
        "cancelled": "canceled",
        "canceled": "canceled",
    }.get((value or "").lower(), "pending")


def _premium(status: str) -> bool:
    return status == "active"


def billing_entitlement_for_email(email: str | None) -> dict[str, Any]:
    """Return the current billing-derived Premium entitlement for an account."""
    wanted = (email or "").strip().lower()
    if not wanted:
        return {"premium": False, "plan": None, "status": "none", "updatedAt": None}
    try:
        with _db() as conn:
            active = conn.execute(
                """
                SELECT plan, status, updated_at
                FROM billing_subscriptions
                WHERE lower(email)=? AND status='active'
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                (wanted,),
            ).fetchone()
            row = active or conn.execute(
                """
                SELECT plan, status, updated_at
                FROM billing_subscriptions
                WHERE lower(email)=?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                (wanted,),
            ).fetchone()
    except sqlite3.Error:
        row = None

    if row is None:
        return {"premium": False, "plan": None, "status": "none", "updatedAt": None}

    data = dict(row)
    return {
        "premium": _premium(str(data.get("status") or "")),
        "plan": data.get("plan"),
        "status": data.get("status"),
        "updatedAt": data.get("updated_at"),
    }


def _server_premium_override_for_account_hash(account_hash: str) -> bool | None:
    supabase_url = os.getenv(
        "SUPABASE_URL",
        os.getenv("ORB_SUPABASE_URL", "https://twhmhdqmbvogezofvfqg.supabase.co"),
    ).rstrip("/")
    supabase_key = os.getenv(
        "SUPABASE_PUBLISHABLE_KEY",
        os.getenv(
            "ORB_SUPABASE_PUBLISHABLE_KEY",
            os.getenv("SUPABASE_ANON_KEY", ""),
        ),
    ).strip()
    if not supabase_url or not supabase_key:
        return None

    try:
        response = httpx.post(
            f"{supabase_url}/rest/v1/rpc/orb_premium_override_for_hash",
            headers={
                "apikey": supabase_key,
                "Authorization": f"Bearer {supabase_key}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
            json={"p_account_hash": account_hash},
            timeout=8.0,
        )
        if response.status_code >= 400:
            return None
        value = response.json()
        if value is None:
            return None
        return bool(value)
    except Exception:
        return None


def premium_entitled_for_account_hash(account_hash: str) -> bool:
    """Server-side Premium entitlement lookup without exposing account email.

    Automix receives only a SHA-256 account hash. Billing keeps the checkout
    email locally; compare hashes here so the playback service never needs the
    raw address and a modified client cannot grant itself Premium merely by
    toggling a local flag.
    """
    wanted = account_hash.strip().lower()
    if len(wanted) != 64:
        return False

    manual_override = _server_premium_override_for_account_hash(wanted)
    if manual_override is not None:
        return manual_override

    try:
        with _db() as conn:
            rows = conn.execute(
                """
                SELECT email
                FROM billing_subscriptions
                WHERE status='active'
                """
            ).fetchall()
    except sqlite3.Error:
        return False

    for row in rows:
        email = str(row["email"] or "").strip().lower()
        if not email:
            continue
        digest = hashlib.sha256(email.encode("utf-8")).hexdigest()
        if hmac.compare_digest(digest, wanted):
            return True
    return False


def _configured_price(plan: Literal["monthly", "yearly"]) -> float | None:
    """Return the positive Premium price currently loaded in the process.

    The provider endpoint must expose the same values used by the checkout
    functions. Prices are read from the runtime environment on every request,
    so a redeployed/restarted Render service immediately reflects the current
    ORB_PREMIUM_*_PRICE variables. No price is hardcoded in the API.
    """
    key = (
        "ORB_PREMIUM_MONTHLY_PRICE"
        if plan == "monthly"
        else "ORB_PREMIUM_YEARLY_PRICE"
    )
    raw = os.getenv(key, "").strip()

    try:
        value = round(float(raw), 2)
    except (TypeError, ValueError):
        return None

    return value if math.isfinite(value) and value > 0 else None


class SubscriptionCheckoutRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    plan: Literal["monthly", "yearly"] = "monthly"
    payer_email: str | None = Field(default=None, min_length=5, max_length=254)

    @field_validator("payer_email")
    @classmethod
    def validate_payer_email(cls, value: str | None) -> str | None:
        return _payment_email(value)


class SubscriptionConfirmRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    checkout_ref: str = Field(min_length=32, max_length=64)


async def _verified_identity(authorization: str | None) -> dict[str, Any]:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Sign in to Orb first")
    if not SUPABASE_KEY:
        raise HTTPException(status_code=503, detail="Billing Auth is not configured")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            response = await client.get(
                f"{SUPABASE_URL}/auth/v1/user",
                headers={"Authorization": authorization, "apikey": SUPABASE_KEY},
            )
    except httpx.HTTPError as error:
        raise HTTPException(status_code=503, detail="Billing Auth is unavailable") from error
    if response.status_code in (401, 403):
        raise HTTPException(status_code=401, detail="Orb session expired")
    if response.status_code != 200:
        raise HTTPException(status_code=503, detail="Billing Auth is unavailable")
    user = response.json()
    email = str(user.get("email") or "").strip().lower()
    if not user.get("id") or not email or not user.get("email_confirmed_at"):
        raise HTTPException(status_code=403, detail="A confirmed Orb account is required")
    # Never authorize from request email, account hashes or user_metadata.
    return {"id": str(user["id"]), "email": email,
            "developer": hmac.compare_digest(hashlib.sha256(email.encode()).hexdigest(), DEVELOPER_EMAIL_HASH)}


def _account_rows(identity: dict[str, Any]) -> list[sqlite3.Row]:
    with _db() as conn:
        return conn.execute(
            "SELECT * FROM billing_subscriptions WHERE customer_ref=? ORDER BY created_at DESC, rowid DESC",
            (identity["id"],),
        ).fetchall()


async def _verified_courtesy_entitlement(authorization: str | None) -> dict[str, Any]:
    """Fetch only the authenticated Orb user's unexpired complimentary Premium grant.

    Supabase derives user_id from the signed user JWT, never from a client
    argument or a payer's email. A lookup failure must not produce a payable
    checkout when the user might already have Premium.
    """
    if not SUPABASE_KEY or not authorization:
        raise HTTPException(status_code=503, detail="Premium entitlement verification unavailable")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            response = await client.post(
                f"{SUPABASE_URL}/rest/v1/rpc/orb_current_courtesy_entitlement",
                headers={
                    "apikey": SUPABASE_KEY,
                    "Authorization": authorization,
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
                json={},
            )
        if response.status_code != 200:
            import logging
            logging.getLogger("orb.billing").warning("Courtesy RPC unavailable: http_status=%s", response.status_code)
            raise HTTPException(status_code=503, detail={"code": "COURTESY_LOOKUP_UNAVAILABLE"})
        value = response.json()
        if not isinstance(value, dict) or not isinstance(value.get("premium"), bool):
            raise HTTPException(status_code=503, detail="Premium entitlement verification unavailable")
        return value
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        raise HTTPException(status_code=503, detail="Premium entitlement verification unavailable") from exc


def _subscription_state(
    identity: dict[str, Any], courtesy: dict[str, Any] | None = None
) -> dict[str, Any]:
    rows = _account_rows(identity)
    active = next((row for row in rows if row["status"] == "active"), None)
    # Keep the early-access starting state only until the first explicit action.
    preview = identity["developer"] and not rows
    complimentary = bool(courtesy and courtesy.get("premium") is True)
    current = active or (rows[0] if rows else None)
    is_premium = active is not None or preview or complimentary
    state = {
        "userId": identity["id"],
        "premium": is_premium,
        "status": "active" if is_premium else (current["status"] if current else "inactive"),
        "developer": identity["developer"],
        "amount": 0.0 if identity["developer"] or complimentary else _configured_price("monthly"),
        "currency": "BRL",
        "checkoutRef": current["checkout_ref"] if current else None,
        "premiumPlan": current["plan"] if current and is_premium else None,
        "billingActive": active is not None and active["provider"] == "mercadopago",
        "premiumSource": "billing" if active else ("developer" if identity["developer"] else None),
        "nextRenewalAt": None,
    }
    if complimentary and active is None:
        state["premiumSource"] = "courtesy"
        state["premiumPlan"] = courtesy.get("plan")
        state["premiumExpiresAt"] = courtesy.get("expiresAt")
    return state


def beta_preview_allowed(account_hash: str) -> bool:
    """An owner cancellation must also disable the old Automix beta bypass."""
    if not hmac.compare_digest(account_hash.strip().lower(), DEVELOPER_EMAIL_HASH):
        return True
    with _db() as conn:
        rows = conn.execute("SELECT email FROM billing_subscriptions WHERE provider='developer'").fetchall()
    return not any(hmac.compare_digest(hashlib.sha256(str(row["email"]).strip().lower().encode()).hexdigest(), DEVELOPER_EMAIL_HASH) for row in rows)



def _orb_signed_checkout_ref(user_id: str) -> str:
    """Provider reference is bound to the initiating Orb identity, not payer email."""
    if not MP_ACCESS_TOKEN:
        raise HTTPException(status_code=503, detail="Mercado Pago is not configured")
    user_hex = uuid.UUID(user_id).hex
    nonce = uuid.uuid4().hex
    body = f"orb2_{user_hex}_{nonce}"
    signature = hmac.new(
        MP_ACCESS_TOKEN.encode("utf-8"),
        ("orb-checkout-v2:" + body).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:32]
    return f"{body}_{signature}"


def _orb_signed_ref_owner(reference: str) -> str | None:
    import re
    if not MP_ACCESS_TOKEN or not re.fullmatch(r"orb2_[0-9a-f]{32}_[0-9a-f]{32}_[0-9a-f]{32}", reference):
        return None
    unsigned, actual = reference.rsplit("_", 1)
    expected = hmac.new(
        MP_ACCESS_TOKEN.encode("utf-8"),
        ("orb-checkout-v2:" + unsigned).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:32]
    if not hmac.compare_digest(expected, actual):
        return None
    try:
        return str(uuid.UUID(hex=reference.split("_")[1]))
    except (ValueError, IndexError):
        return None


async def _persist_checkout_for_account(
    authorization: str | None, checkout_ref: str, provider_id: str, plan: str
) -> None:
    """Store provider linkage in persistent Supabase before exposing the pay URL."""
    if not authorization or not SUPABASE_KEY:
        raise HTTPException(status_code=503, detail="Checkout persistence unavailable")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            response = await client.post(
                f"{SUPABASE_URL}/rest/v1/rpc/orb_billing_store_checkout_link",
                headers={
                    "apikey": SUPABASE_KEY,
                    "Authorization": authorization,
                    "Content-Type": "application/json",
                },
                json={
                    "p_checkout_ref": checkout_ref,
                    "p_provider_subscription_id": provider_id,
                    "p_plan": plan,
                },
            )
        if response.status_code != 200 or response.json() is not True:
            raise HTTPException(status_code=503, detail="Checkout persistence unavailable")
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Checkout persistence unavailable") from exc


async def _recover_persisted_mp_link(
    identity: dict[str, Any], authorization: str | None
) -> None:
    """Restore an Orb user's paid Premium from immutable Supabase linkage.

    Each persisted entry is independently checked against Mercado Pago's
    authoritative subscription status and its signed external reference.
    """
    if not MP_ACCESS_TOKEN or not SUPABASE_KEY or not authorization:
        return
    if _subscription_state(identity)["premium"]:
        return
    import logging
    logger = logging.getLogger("orb.billing")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            links_response = await client.get(
                f"{SUPABASE_URL}/rest/v1/orb_billing_checkout_links",
                headers={"apikey": SUPABASE_KEY, "Authorization": authorization},
                params={
                    "select": "checkout_ref,provider_subscription_id,plan",
                    "user_id": f"eq.{identity['id']}",
                    "order": "created_at.desc",
                    "limit": "10",
                },
            )
            if links_response.status_code != 200:
                logger.warning("Premium persistent-link lookup failed: %s", links_response.status_code)
                raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"})
            links = links_response.json()
            if not isinstance(links, list):
                return
            for link in links:
                if not isinstance(link, dict):
                    continue
                checkout_ref = str(link.get("checkout_ref") or "")
                provider_id = str(link.get("provider_subscription_id") or "")
                if _orb_signed_ref_owner(checkout_ref) != identity["id"] or not provider_id:
                    continue
                response = await client.get(
                    f"{MP_API}/preapproval/{provider_id}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
                )
                if response.status_code != 200:
                    raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"})
                payment = response.json()
                if not isinstance(payment, dict):
                    continue
                if (str(payment.get("id") or "") != provider_id
                    or str(payment.get("external_reference") or "") != checkout_ref
                    or str(payment.get("reason") or "").strip() != "Orb Premium"
                    or payment.get("status") != "authorized"):
                    continue
                terms = payment.get("auto_recurring") or {}
                if not isinstance(terms, dict) or terms.get("currency_id") != "BRL":
                    continue
                plan = link.get("plan")
                if plan not in ("monthly", "yearly"):
                    continue
                if terms.get("frequency_type") != "months" or terms.get("frequency") != (1 if plan == "monthly" else 12):
                    continue
                try:
                    amount = float(terms.get("transaction_amount"))
                except (TypeError, ValueError):
                    continue
                if not math.isfinite(amount) or amount <= 0:
                    continue
                _save_checkout(
                    checkout_ref=checkout_ref, customer_ref=identity["id"],
                    provider="mercadopago", provider_subscription_id=provider_id,
                    checkout_id=None, email=identity["email"], plan=plan,
                    amount=amount, status="active", checkout_url=None,
                )
                return
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        logger.warning("Premium persistent-link recovery unavailable: %s", type(exc).__name__)
        raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"}) from exc


async def _provider_active_orb_subscriptions() -> list[dict[str, str]]:
    """Read active subscriptions from Mercado Pago, not Render's ephemeral DB."""
    if not MP_ACCESS_TOKEN:
        raise HTTPException(status_code=503, detail="Mercado Pago is not configured")
    accepted: dict[str, dict[str, str]] = {}
    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
        for offset in range(0, 1000, 100):
            try:
                response = await client.get(
                    f"{MP_API}/preapproval/search",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
                    params={"limit": 100, "offset": offset},
                )
                if response.status_code != 200:
                    raise HTTPException(status_code=503, detail="Premium count unavailable from provider")
                data = response.json()
                entries = data.get("results") if isinstance(data, dict) else None
                if not isinstance(entries, list):
                    raise HTTPException(status_code=503, detail="Premium count unavailable from provider")
                for sub in entries:
                    if not isinstance(sub, dict):
                        continue
                    if sub.get("status") != "authorized" or str(sub.get("reason") or "").strip() != "Orb Premium":
                        continue
                    terms = sub.get("auto_recurring") or {}
                    if not isinstance(terms, dict) or terms.get("currency_id") != "BRL":
                        continue
                    if terms.get("frequency_type") != "months" or terms.get("frequency") not in (1, 12):
                        continue
                    provider_id = str(sub.get("id") or "").strip()
                    if not provider_id:
                        continue
                    try:
                        amount = float(terms.get("transaction_amount"))
                    except (TypeError, ValueError):
                        continue
                    if math.isfinite(amount) and amount > 0:
                        accepted[provider_id] = {
                            "id": provider_id,
                            "external_reference": str(sub.get("external_reference") or ""),
                        }
                paging = data.get("paging") or {}
                total = paging.get("total") if isinstance(paging, dict) else None
                if len(entries) < 100 or (isinstance(total, int) and offset + len(entries) >= total):
                    return list(accepted.values())
            except (httpx.HTTPError, ValueError, TypeError) as exc:
                raise HTTPException(status_code=503, detail="Premium count unavailable from provider") from exc
    raise HTTPException(status_code=503, detail="Premium count temporarily exceeds scan capacity")


@router.get("/owner/active-premium-count")
async def owner_active_premium_count(
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """Count provider-confirmed paying users plus active complimentary grants."""
    identity = await _verified_identity(authorization)
    if not identity["developer"]:
        raise HTTPException(status_code=403, detail="Owner access required")
    if not SUPABASE_KEY or not authorization:
        raise HTTPException(status_code=503, detail="Premium count temporarily unavailable")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            responses = await __import__("asyncio").gather(
                client.post(
                    f"{SUPABASE_URL}/rest/v1/rpc/orb_owner_active_courtesy_user_ids",
                    headers={"apikey": SUPABASE_KEY, "Authorization": authorization},
                    json={},
                ),
                client.post(
                    f"{SUPABASE_URL}/rest/v1/rpc/orb_owner_billing_link_map",
                    headers={"apikey": SUPABASE_KEY, "Authorization": authorization},
                    json={},
                ),
            )
        if any(resp.status_code != 200 for resp in responses):
            raise HTTPException(status_code=503, detail="Premium count temporarily unavailable")
        courtesy_ids, persisted_links = [resp.json() for resp in responses]
        if not isinstance(courtesy_ids, list) or not isinstance(persisted_links, list):
            raise HTTPException(status_code=503, detail="Premium count temporarily unavailable")
        if not all(isinstance(uid, str) for uid in courtesy_ids):
            raise HTTPException(status_code=503, detail="Premium count temporarily unavailable")
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        raise HTTPException(status_code=503, detail="Premium count temporarily unavailable") from exc

    link_owners = {
        str(item["provider_subscription_id"]): str(item["user_id"])
        for item in persisted_links
        if isinstance(item, dict) and item.get("provider_subscription_id") and item.get("user_id")
    }
    with _db() as conn:
        local_owners = {
            str(row["provider_subscription_id"]): str(row["customer_ref"])
            for row in conn.execute(
                "SELECT provider_subscription_id, customer_ref FROM billing_subscriptions "
                "WHERE provider='mercadopago' AND provider_subscription_id IS NOT NULL"
            ).fetchall()
        }
    provider_subscriptions = await _provider_active_orb_subscriptions()
    paid_owners: set[str] = set()
    unlinked_count = 0
    for subscription in provider_subscriptions:
        provider_id = subscription["id"]
        signed_owner = _orb_signed_ref_owner(subscription["external_reference"])
        # A stored link cannot override the owner cryptographically bound to
        # the provider reference. Legacy payments remain unlinked if unknown.
        owner = signed_owner or local_owners.get(provider_id)
        if not owner:
            # A legacy UUID-only reference is not sufficient to identify an
            # Orb account. Count the verified distinct provider subscription
            # without silently inventing a user ID or granting access.
            owner = f"legacy-mp:{provider_id}"
            unlinked_count += 1
        paid_owners.add(owner)
    return {
        "userId": identity["id"],
        "activePremiumUsers": len(set(courtesy_ids) | paid_owners),
        "paidPremiumUsers": len(paid_owners),
        "courtesyPremiumUsers": len(set(courtesy_ids)),
        "unlinkedPaidSubscriptions": unlinked_count,
    }


async def _reconcile_mp_account(identity: dict[str, Any]) -> None:
    """Reconcile account-owned pending Mercado Pago preapprovals.

    Client-supplied payment status is never trusted; only the provider API
    can authorize a subscription, bound to an existing account checkout.
    """
    if not MP_ACCESS_TOKEN:
        return
    rows = [
        row for row in _account_rows(identity)
        if row["provider"] == "mercadopago"
        and row["status"] in ("pending", "paused", "past_due")
        and row["provider_subscription_id"]
    ][:5]
    if not rows:
        return
    import logging
    logger = logging.getLogger("orb.billing")
    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
        for row in rows:
            try:
                response = await client.get(
                    f"{MP_API}/preapproval/{row['provider_subscription_id']}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
                )
                if response.status_code != 200:
                    logger.warning("Premium reconciliation provider_status=%s", response.status_code)
                    continue
                provider = response.json()
                if not isinstance(provider, dict):
                    continue
                if (str(provider.get("id") or "") != row["provider_subscription_id"]
                        or str(provider.get("external_reference") or "") != row["checkout_ref"]):
                    logger.warning("Premium reconciliation reference mismatch")
                    continue
                # The Orb account that created the checkout owns the subscription.
                # Payment-provider email can legitimately differ from Orb email.
                status = _normalize_mp_status(provider.get("status"))
                if status == "active":
                    _update_by_checkout_ref(row["checkout_ref"], status="active")
                elif status in ("paused", "canceled"):
                    _update_by_checkout_ref(row["checkout_ref"], status=status)
            except (httpx.HTTPError, ValueError, TypeError):
                logger.warning("Premium reconciliation temporary failure")
                continue


async def _recover_mp_subscription_by_email(identity: dict[str, Any]) -> None:
    """Recover an authorized Orb subscription whose local checkout row was lost.

    Query Mercado Pago with the authenticated account email; verify the specific
    subscription with the provider and cross-check product, recurring terms,
    external reference and ownership before creating an idempotent local record.
    Never create a new charge or trust a browser-return status.
    """
    if not MP_ACCESS_TOKEN or _subscription_state(identity)["premium"]:
        return
    import logging
    logger = logging.getLogger("orb.billing")
    headers = {"Authorization": f"Bearer {MP_ACCESS_TOKEN}"}
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            response = await client.get(
                f"{MP_API}/preapproval/search",
                headers=headers,
                params={"payer_email": identity["email"], "limit": 20, "offset": 0},
            )
            if response.status_code != 200:
                logger.warning("Premium recovery search provider_status=%s", response.status_code)
                raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"})
            result = response.json()
            items = result.get("results") if isinstance(result, dict) else None
            if not isinstance(items, list):
                return
            for item in items[:20]:
                if not isinstance(item, dict) or item.get("status") != "authorized":
                    continue
                provider_id = str(item.get("id") or "").strip()
                if not provider_id:
                    continue
                detail_response = await client.get(
                    f"{MP_API}/preapproval/{provider_id}", headers=headers
                )
                if detail_response.status_code != 200:
                    raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"})
                sub = detail_response.json()
                if not isinstance(sub, dict):
                    continue
                if str(sub.get("id") or "") != provider_id or sub.get("status") != "authorized":
                    continue
                if str(sub.get("reason") or "").strip() != "Orb Premium":
                    continue
                payer = str(sub.get("payer_email") or item.get("payer_email") or "").strip().lower()
                if payer and payer != identity["email"]:
                    continue
                checkout_ref = str(sub.get("external_reference") or "").strip()
                try:
                    if str(uuid.UUID(checkout_ref)) != checkout_ref.lower():
                        continue
                except (ValueError, AttributeError):
                    continue
                recurring = sub.get("auto_recurring") or {}
                if not isinstance(recurring, dict) or recurring.get("currency_id") != "BRL":
                    continue
                if recurring.get("frequency_type") != "months":
                    continue
                frequency = recurring.get("frequency")
                if frequency not in (1, 12):
                    continue
                plan = "monthly" if frequency == 1 else "yearly"
                try:
                    amount = round(float(recurring.get("transaction_amount")), 2)
                except (ValueError, TypeError):
                    continue
                expected = _configured_price(plan)
                if expected is None or amount != round(float(expected), 2):
                    continue
                # Never overwrite a subscription linked to another Orb account.
                with _db() as conn:
                    owner = conn.execute(
                        "SELECT customer_ref FROM billing_subscriptions "
                        "WHERE checkout_ref=? OR (provider='mercadopago' AND provider_subscription_id=?) "
                        "LIMIT 1", (checkout_ref, provider_id)
                    ).fetchone()
                if owner and owner["customer_ref"] != identity["id"]:
                    logger.warning("Premium recovery account ownership conflict")
                    continue
                if not owner:
                    _save_checkout(
                        checkout_ref=checkout_ref,
                        customer_ref=identity["id"],
                        provider="mercadopago",
                        provider_subscription_id=provider_id,
                        checkout_id=None,
                        email=identity["email"],
                        plan=plan,
                        amount=amount,
                        status="active",
                        checkout_url=None,
                    )
                else:
                    _update_by_checkout_ref(checkout_ref, status="active", provider_subscription_id=provider_id)
                logger.info("Premium subscription recovered from provider for verified account")
                return
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        logger.warning("Premium recovery temporarily unavailable: %s", type(exc).__name__)
        raise HTTPException(503, detail={"code": "BILLING_RECOVERY_UNAVAILABLE"}) from exc


async def _next_renewal_for_account(identity: dict[str, Any]) -> str | None:
    """Display-only provider metadata. A missing date cannot block checkout/status."""
    row = next((r for r in _account_rows(identity)
                if r["status"] == "active" and r["provider"] == "mercadopago"
                and r["provider_subscription_id"]), None)
    if row is None or not MP_ACCESS_TOKEN:
        return None
    try:
        async with asyncio.timeout(3.0):
            async with httpx.AsyncClient(timeout=httpx.Timeout(2.5, connect=1.5)) as client:
                response = await client.get(f"{MP_API}/preapproval/{row['provider_subscription_id']}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"})
            if response.status_code != 200:
                return None
            data = response.json()
            if (not isinstance(data, dict) or data.get("id") != row["provider_subscription_id"]
                    or data.get("external_reference") != row["checkout_ref"]
                    or data.get("status") != "authorized"):
                return None
            value = data.get("next_payment_date")
            if not isinstance(value, str):
                return None
            date = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if date.tzinfo is None:
                return None
            return date.astimezone(timezone.utc).isoformat()
    except (TimeoutError, httpx.HTTPError, ValueError, TypeError):
        return None


async def subscription_for_identity(
    identity: dict[str, Any],
    authorization: str | None,
    *,
    allow_legacy_recovery: bool = True,
) -> dict[str, Any]:
    """Resolve verified account access; keep slower legacy recovery off app startup."""
    courtesy = await _verified_courtesy_entitlement(authorization)

    # A valid, unexpired courtesy grant is already verified by Supabase.
    # Never make its recognition depend on Mercado Pago availability.
    if courtesy["premium"]:
        return _subscription_state(identity, courtesy)

    await _reconcile_mp_account(identity)
    if not _subscription_state(identity)["premium"]:
        await _recover_persisted_mp_link(identity, authorization)
    if allow_legacy_recovery and not _subscription_state(identity)["premium"]:
        await _recover_mp_subscription_by_email(identity)

    state = _subscription_state(identity, courtesy)
    state["nextRenewalAt"] = await _next_renewal_for_account(identity)
    return state


STATUS_BUDGET_SECONDS = 16.0
CHECKOUT_BUDGET_SECONDS = 35.0
_checkout_locks: WeakValueDictionary[str, asyncio.Lock] = WeakValueDictionary()


@router.get("/subscription")
async def subscription(authorization: str | None = Header(default=None)):
    try:
        async with asyncio.timeout(STATUS_BUDGET_SECONDS):
            identity = await _verified_identity(authorization)
            return await subscription_for_identity(identity, authorization)
    except TimeoutError as exc:
        raise HTTPException(503, detail={"code": "BILLING_STATUS_TIMEOUT"}) from exc


@router.post("/subscription/checkout")
async def subscription_checkout(body: SubscriptionCheckoutRequest, authorization: str | None = Header(default=None)):
    try:
        async with asyncio.timeout(CHECKOUT_BUDGET_SECONDS):
            identity = await _verified_identity(authorization)
            # Serialize pending-checkout creation within this single-worker service.
            # Values are weak references so idle account locks do not accumulate.
            lock = _checkout_locks.setdefault(identity["id"], asyncio.Lock())
            async with lock:
                return await _checkout_for_identity(body, identity, authorization)
    except TimeoutError as exc:
        raise HTTPException(503, detail={"code": "BILLING_CHECKOUT_TIMEOUT"}) from exc


async def _checkout_for_identity(body: SubscriptionCheckoutRequest, identity: dict[str, Any], authorization: str | None):
    courtesy = await _verified_courtesy_entitlement(authorization)
    if courtesy["premium"] and not identity["developer"]:
        raise HTTPException(status_code=409, detail="Orb Premium is already active for this account")
    # Keep checkout responsive: account recovery via Mercado Pago search can
    # involve multiple provider requests (up to 25s each) and belongs on GET
    # /subscription, not the interactive button that opens the payment page.
    # The authenticated account already has its own checkout rows below.
    if any(r["status"] == "active" and r["provider"] == "mercadopago" for r in _account_rows(identity)):
        raise HTTPException(status_code=409, detail="Cancel the active subscription before subscribing again")
    # Reuse pending checkouts to avoid duplicate subscriptions on retry.
    pending = next((r for r in _account_rows(identity) if r["status"] == "pending" and r["plan"] == body.plan and r["provider"] == "mercadopago"), None)
    if pending:
        # A previous request may have timed out after the provider was created
        # but before durable linkage completed. Persist it again before exposing a URL.
        if pending["provider"] == "mercadopago":
            await _persist_checkout_for_account(
                authorization, pending["checkout_ref"], pending["provider_subscription_id"], body.plan
            )
        return {"userId": identity["id"], "checkoutRef": pending["checkout_ref"],
                "amount": pending["amount"], "currency": "BRL", "status": "pending",
                "requiresConfirmation": pending["provider"] == "developer", "checkoutUrl": pending["checkout_url"]}
    # All other users keep the environment price and real provider checkout.
    checkout_ref = _orb_signed_checkout_ref(identity["id"])
    result = await create_mercado_pago_checkout(
        CheckoutRequest(customer_ref=identity["id"], email=identity["email"], plan=body.plan,
                        payer_email=body.payer_email),
        checkout_ref=checkout_ref,
    )
    saved = next(row for row in _account_rows(identity) if row["checkout_ref"] == result["checkoutRef"])
    await _persist_checkout_for_account(
        authorization, result["checkoutRef"], saved["provider_subscription_id"], body.plan
    )
    return {**result, "userId": identity["id"], "amount": saved["amount"],
            "currency": "BRL", "requiresConfirmation": False}


@router.post("/subscription/confirm")
async def subscription_confirm(body: SubscriptionConfirmRequest, authorization: str | None = Header(default=None)):
    identity = await _verified_identity(authorization)
    if not identity["developer"]:
        raise HTTPException(status_code=403, detail="Payment confirmation requires the provider webhook")
    with _db() as conn:
        row = conn.execute("SELECT * FROM billing_subscriptions WHERE checkout_ref=? AND customer_ref=?",
                           (body.checkout_ref, identity["id"])).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Checkout not found")
        if row["provider"] != "developer" or row["amount"] != 0.0 or row["status"] not in ("pending", "active"):
            raise HTTPException(status_code=409, detail="Checkout cannot be confirmed")
        conn.execute("UPDATE billing_subscriptions SET status='active', updated_at=? WHERE checkout_ref=? AND status='pending'",
                     (_now(), body.checkout_ref))
        conn.commit()
    return _subscription_state(identity)


@router.post("/subscription/cancel")
async def subscription_cancel(authorization: str | None = Header(default=None)):
    identity = await _verified_identity(authorization)
    rows = _account_rows(identity)
    for row in rows:
        if row["status"] not in ("active", "pending", "paused", "past_due"):
            continue
        if row["provider"] != "developer":
            provider_id = row["provider_subscription_id"]
            if row["provider"] != "mercadopago" or not provider_id or not MP_ACCESS_TOKEN:
                raise HTTPException(status_code=409, detail="This subscription requires provider cancellation")
            async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
                response = await client.put(f"{MP_API}/preapproval/{provider_id}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"}, json={"status": "canceled"})
            if response.status_code >= 400 or _normalize_mp_status(response.json().get("status")) != "canceled":
                raise HTTPException(status_code=502, detail="Provider cancellation failed")
        _update_by_checkout_ref(row["checkout_ref"], status="canceled")
    if identity["developer"]:
        # A marker also turns off initial early access when no subscription existed.
        _save_checkout(checkout_ref=str(uuid.uuid4()), customer_ref=identity["id"], provider="developer",
                       provider_subscription_id=None, checkout_id=None, email=identity["email"],
                       plan="monthly", amount=0.0, status="canceled", checkout_url=None)
    return _subscription_state(identity)


@router.get("/providers")
async def providers():
    monthly_price = _configured_price("monthly")
    yearly_price = _configured_price("yearly")

    return {
        "mercadoPago": {
            "configured": bool(MP_ACCESS_TOKEN),
            "webhookConfigured": bool(MP_WEBHOOK_SECRET),
        },
        "asaas": {
            "configured": bool(ASAAS_API_KEY),
            "pixAutomatic": False,
            "note": (
                "Pix Automático requires a separate Asaas "
                "authorization flow."
            ),
        },
        "plans": {
            "monthlyConfigured": monthly_price is not None,
            "yearlyConfigured": yearly_price is not None,
            "monthlyPrice": monthly_price,
            "yearlyPrice": yearly_price,
            "currency": "BRL",
            "source": "environment",
        },
        "runtime": {
            "gitCommit": os.getenv("RENDER_GIT_COMMIT", "").strip() or None,
        },
    }


def _orb_mp_failure(response: httpx.Response) -> HTTPException:
    """Return a safe, structured, client-readable failure without provider PII."""
    import logging
    import re

    http_status = int(response.status_code)
    try:
        data = response.json()
    except (ValueError, TypeError):
        data = {}
    if not isinstance(data, dict):
        data = {}

    # Mercado Pago may return strings, arrays or numeric cause codes.
    # Never write raw provider responses, credentials, card data or email to logs.
    candidates = []
    causes = data.get("cause")
    if isinstance(causes, dict):
        causes = [causes]
    if isinstance(causes, list):
        for cause in causes[:5]:
            if isinstance(cause, dict):
                candidates.extend((cause.get("code"), cause.get("error")))
    # Prefer specific cause codes over a generic top-level 'bad_request'.
    candidates.extend((data.get("code"), data.get("error")))
    provider_code = next(
        (str(value) for value in candidates
         if value is not None and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", str(value))),
        "unknown",
    )
    if http_status in (401, 403):
        code = "MP_CREDENTIALS_REJECTED"
    elif http_status == 429:
        code = "MP_RATE_LIMITED"
    elif 400 <= http_status < 500:
        code = "MP_REQUEST_REJECTED"
    else:
        code = "MP_UNAVAILABLE"
    if provider_code == "guest_site_mismatch":
        code = "MP_PAYER_SITE_MISMATCH"
    def _safe_error_text(value: Any) -> str:
        """Keep provider field names readable; redact private data and identifiers."""
        if not isinstance(value, str):
            return ""
        text = value[:600]
        text = re.sub(r"https?://[^\s,;]+", "[url]", text, flags=re.I)
        text = re.sub(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", "[email]", text)
        text = re.sub(r"(?i)\b(?:bearer\s+|APP_USR[-_]|TEST[-_])\S+", "[credential]", text)
        text = re.sub(r"(?i)\b(?:authorization|access[_-]?token|client[_-]?secret|password|api[_-]?key)\s*[:=]\s*\S+", "[secret]", text)
        text = re.sub(r"\b\d(?:[ -]?\d){11,18}\b", "[number]", text)
        text = re.sub(r"\b\d{4,}\b", "[number]", text)
        text = re.sub(r"\b[A-Za-z0-9_+\-/=]{24,}\b", "[identifier]", text)
        text = re.sub(r"[\r\n\t]+", " ", text)
        text = re.sub(r"[^\w .,:;()\[\]/?=+\-]", " ", text)
        return " ".join(text.split())[:220]

    # A 400 response can carry the useful reason only in 'message' or
    # in cause[].description, with no machine-readable provider code.
    cause_details = data.get("cause")
    if isinstance(cause_details, dict):
        cause_details = [cause_details]
    if not isinstance(cause_details, list):
        cause_details = []
    cause_details = [item for item in cause_details[:5] if isinstance(item, dict)]
    top_reason = (
        _safe_error_text(data.get("message"))
        or _safe_error_text(data.get("description"))
        or _safe_error_text(data.get("error_description"))
        or _safe_error_text(data.get("error"))
        or "none"
    )
    cause_reason = next(
        (
            text
            for item in cause_details
            for text in (
                _safe_error_text(item.get("description")),
                _safe_error_text(item.get("message")),
                _safe_error_text(item.get("error")),
            )
            if text
        ),
        _safe_error_text(data.get("cause")) or "none",
    )
    logging.getLogger("orb.billing").warning(
        "Mercado Pago preapproval failed: http_status=%d code=%s "
        "provider_code=%s provider_message=%s provider_cause=%s",
        http_status, code, provider_code, top_reason, cause_reason,
    )
    return HTTPException(status_code=502 if code != "MP_RATE_LIMITED" else 503, detail={
        "code": code,
        "providerStatus": http_status,
        "providerCode": provider_code,
    })


@router.post("/checkout/mercadopago")
async def create_mercado_pago_checkout(body: CheckoutRequest, checkout_ref: str | None = None):
    if not MP_ACCESS_TOKEN:
        raise HTTPException(status_code=503, detail="Mercado Pago is not configured")

    amount = _price(body.plan)
    checkout_ref = checkout_ref or str(uuid.uuid4())
    recurring = {
        "frequency": 1 if body.plan == "monthly" else 12,
        "frequency_type": "months",
        "transaction_amount": amount,
        "currency_id": "BRL",
    }
    payload = {
        "reason": "Orb Premium",
        "external_reference": checkout_ref,
        "payer_email": body.payer_email or str(body.email),
        "auto_recurring": recurring,
        "back_url": f"{PUBLIC_BASE_URL}/api/billing/return/mercadopago",
        "status": "pending",
    }

    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            response = await client.post(
                f"{MP_API}/preapproval",
                headers={
                    "Authorization": f"Bearer {MP_ACCESS_TOKEN}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
    except (httpx.RequestError, TimeoutError) as exc:
        import logging
        logging.getLogger("orb.billing").warning(
            "Mercado Pago preapproval transport failure: exception_type=%s",
            type(exc).__name__,
        )
        raise HTTPException(status_code=503, detail={"code": "MP_UNAVAILABLE"}) from exc

    if response.status_code >= 400:
        raise _orb_mp_failure(response)

    try:
        data = response.json()
    except (ValueError, TypeError) as exc:
        raise HTTPException(status_code=502, detail={"code": "MP_INVALID_RESPONSE"}) from exc
    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail={"code": "MP_INVALID_RESPONSE"})
    provider_id = str(data.get("id") or "")
    checkout_url = data.get("init_point")
    if (not provider_id or not isinstance(checkout_url, str)
            or not checkout_url.startswith("https://")):
        import logging
        logging.getLogger("orb.billing").warning(
            "Mercado Pago preapproval incomplete response: has_id=%s has_checkout_url=%s",
            bool(provider_id), isinstance(checkout_url, str) and checkout_url.startswith("https://"),
        )
        raise HTTPException(status_code=502, detail={"code": "MP_CHECKOUT_LINK_MISSING"})

    _save_checkout(
        checkout_ref=checkout_ref,
        customer_ref=body.customer_ref,
        provider="mercadopago",
        provider_subscription_id=provider_id,
        checkout_id=None,
        email=str(body.email),
        plan=body.plan,
        amount=amount,
        status=_normalize_mp_status(data.get("status")),
        checkout_url=checkout_url,
    )
    return {
        "checkoutRef": checkout_ref,
        "provider": "mercadopago",
        "checkoutUrl": checkout_url,
        "status": _normalize_mp_status(data.get("status")),
    }


@router.post("/checkout/asaas")
async def create_asaas_checkout(body: CheckoutRequest):
    if not ASAAS_API_KEY:
        raise HTTPException(status_code=503, detail="Asaas is not configured")

    amount = _price(body.plan)
    checkout_ref = str(uuid.uuid4())
    payload: dict[str, Any] = {
        "billingTypes": ["CREDIT_CARD"],
        "chargeTypes": ["RECURRENT"],
        "minutesToExpire": 60,
        "externalReference": checkout_ref,
        "callback": {
            "successUrl": f"{PUBLIC_BASE_URL}/api/billing/return/asaas?result=success",
            "cancelUrl": f"{PUBLIC_BASE_URL}/api/billing/return/asaas?result=cancel",
            "expiredUrl": f"{PUBLIC_BASE_URL}/api/billing/return/asaas?result=expired",
        },
        "items": [
            {
                "name": "Orb Premium",
                "description": "Assinatura Orb Premium",
                "quantity": 1,
                "value": amount,
            }
        ],
        "customerData": {
            "name": body.name or str(body.email).split("@", 1)[0],
            "email": str(body.email),
        },
        "subscription": {
            "cycle": "MONTHLY" if body.plan == "monthly" else "YEARLY",
            "nextDueDate": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        },
    }

    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
        response = await client.post(
            f"{ASAAS_API}/checkouts",
            headers={
                "access_token": ASAAS_API_KEY,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            json=payload,
        )

    if response.status_code >= 400:
        raise HTTPException(
            status_code=502,
            detail=f"Asaas checkout failed: {response.text[:800]}",
        )

    data = response.json()
    checkout_id = str(data.get("id") or "")
    if not checkout_id:
        raise HTTPException(status_code=502, detail="Asaas returned an invalid checkout")

    checkout_url = f"https://asaas.com/checkoutSession/show?id={checkout_id}"
    _save_checkout(
        checkout_ref=checkout_ref,
        customer_ref=body.customer_ref,
        provider="asaas",
        provider_subscription_id=None,
        checkout_id=checkout_id,
        email=str(body.email),
        plan=body.plan,
        amount=amount,
        status="pending",
        checkout_url=checkout_url,
    )
    return {
        "checkoutRef": checkout_ref,
        "provider": "asaas",
        "checkoutUrl": checkout_url,
        "status": "pending",
    }


@router.get("/status")
async def checkout_status(checkout_ref: str = Query(..., min_length=32, max_length=64)):
    with _db() as conn:
        row = conn.execute(
            """
            SELECT checkout_ref, provider, plan, amount, currency, status, updated_at
            FROM billing_subscriptions
            WHERE checkout_ref=?
            """,
            (checkout_ref,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Checkout not found")
    data = dict(row)
    return {
        "checkoutRef": data["checkout_ref"],
        "provider": data["provider"],
        "plan": data["plan"],
        "amount": data["amount"],
        "currency": data["currency"],
        "status": data["status"],
        "premium": _premium(data["status"]),
        "updatedAt": data["updated_at"],
    }


def _verify_mp_webhook(request: Request, data_id: str) -> None:
    if not MP_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail="Mercado Pago webhook secret is not configured")

    signature = request.headers.get("x-signature", "")
    request_id = request.headers.get("x-request-id", "")
    parts = {}
    for part in signature.split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            parts[key.strip()] = value.strip()

    ts = parts.get("ts")
    received = parts.get("v1")
    if not ts or not received or not request_id:
        raise HTTPException(status_code=401, detail="Invalid Mercado Pago webhook signature")

    candidates = [data_id, data_id.lower()]
    valid = False
    for candidate in candidates:
        manifest = f"id:{candidate};request-id:{request_id};ts:{ts};"
        expected = hmac.new(
            MP_WEBHOOK_SECRET.encode(),
            manifest.encode(),
            hashlib.sha256,
        ).hexdigest()
        if hmac.compare_digest(expected, received):
            valid = True
            break
    if not valid:
        raise HTTPException(status_code=401, detail="Invalid Mercado Pago webhook signature")


@router.post("/webhooks/mercadopago")
async def mercado_pago_webhook(request: Request):
    body = await request.json()
    data_id = str(
        request.query_params.get("data.id")
        or request.query_params.get("data_id")
        or ((body.get("data") or {}).get("id"))
        or ""
    )
    if not data_id:
        raise HTTPException(status_code=400, detail="Missing Mercado Pago data id")

    _verify_mp_webhook(request, data_id)

    event_id = str(body.get("id") or f"{body.get('type')}:{data_id}:{body.get('action')}")
    with _db() as conn:
        processed = conn.execute(
            "SELECT 1 FROM billing_events WHERE provider=? AND event_id=?",
            ("mercadopago", event_id),
        ).fetchone()
    if processed:
        return {"ok": True, "duplicate": True}

    event_type = str(body.get("type") or "")
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
            if event_type == "subscription_preapproval":
                response = await client.get(
                    f"{MP_API}/preapproval/{data_id}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
                )
                if response.status_code != 200:
                    raise HTTPException(503, detail={"code": "MP_WEBHOOK_VERIFY_UNAVAILABLE"})
                if response.status_code == 200:
                    sub = response.json()
                    checkout_ref = str(sub.get("external_reference") or "")
                    if checkout_ref:
                        _update_by_checkout_ref(
                            checkout_ref,
                            status=_normalize_mp_status(sub.get("status")),
                            provider_subscription_id=str(sub.get("id") or data_id),
                        )
            elif event_type == "payment":
                response = await client.get(
                    f"{MP_API}/v1/payments/{data_id}",
                    headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
                )
                if response.status_code != 200:
                    raise HTTPException(503, detail={"code": "MP_WEBHOOK_VERIFY_UNAVAILABLE"})
                if response.status_code == 200:
                    payment = response.json()
                    checkout_ref = str(payment.get("external_reference") or "")
                    payment_status = str(payment.get("status") or "").lower()
                    mapped = {
                        "approved": "active",
                        "in_process": "pending",
                        "pending": "pending",
                        "rejected": "past_due",
                        "cancelled": "canceled",
                        "canceled": "canceled",
                        "refunded": "inactive",
                        "charged_back": "inactive",
                    }.get(payment_status)
                    if checkout_ref and mapped:
                        _update_by_checkout_ref(checkout_ref, status=mapped)

    except (httpx.HTTPError, ValueError, TypeError) as exc:
        raise HTTPException(503, detail={"code": "MP_WEBHOOK_VERIFY_UNAVAILABLE"}) from exc

    _event_once("mercadopago", event_id)
    return {"ok": True}


@router.post("/webhooks/asaas")
async def asaas_webhook(
    request: Request,
    asaas_access_token: str | None = Header(default=None, alias="asaas-access-token"),
):
    if not ASAAS_WEBHOOK_TOKEN:
        raise HTTPException(status_code=503, detail="Asaas webhook token is not configured")
    if not asaas_access_token or not hmac.compare_digest(asaas_access_token, ASAAS_WEBHOOK_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid Asaas webhook token")

    body = await request.json()
    event = str(body.get("event") or "")
    event_id = str(body.get("id") or "")
    if event_id and not _event_once("asaas", event_id):
        return {"ok": True, "duplicate": True}

    checkout = body.get("checkout") or {}
    subscription = body.get("subscription") or {}
    payment = body.get("payment") or {}

    checkout_id = str(checkout.get("id") or "")
    checkout_ref = str(
        checkout.get("externalReference")
        or subscription.get("externalReference")
        or payment.get("externalReference")
        or ""
    )
    subscription_id = str(
        subscription.get("id")
        or payment.get("subscription")
        or ""
    )

    if event == "CHECKOUT_PAID" and checkout_id:
        _update_by_checkout_id("asaas", checkout_id, status="active")
    elif event in {"CHECKOUT_CANCELED", "CHECKOUT_EXPIRED"} and checkout_id:
        _update_by_checkout_id("asaas", checkout_id, status="canceled")
    elif event == "SUBSCRIPTION_CREATED":
        if checkout_ref:
            _update_by_checkout_ref(
                checkout_ref,
                provider_subscription_id=subscription_id or None,
            )
    elif event in {"PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"}:
        if subscription_id:
            _update_by_provider_id("asaas", subscription_id, status="active")
        elif checkout_ref:
            _update_by_checkout_ref(checkout_ref, status="active")
    elif event in {
        "PAYMENT_OVERDUE",
        "PAYMENT_REPROVED_BY_RISK_ANALYSIS",
    }:
        if subscription_id:
            _update_by_provider_id("asaas", subscription_id, status="past_due")
    elif event in {
        "PAYMENT_REFUNDED",
        "PAYMENT_CHARGEBACK_REQUESTED",
        "PAYMENT_CHARGEBACK_DISPUTE",
        "PAYMENT_DELETED",
    }:
        if subscription_id:
            _update_by_provider_id("asaas", subscription_id, status="inactive")

    return {"ok": True}


@router.get("/return/{provider}")
async def billing_return(provider: str, result: str | None = None):
    return {
        "ok": True,
        "provider": provider,
        "result": result or "returned",
        "message": "Pagamento processado. Você já pode voltar ao Orb.",
    }
