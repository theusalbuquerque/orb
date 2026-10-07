from __future__ import annotations

import hashlib
import hmac
import math
import os
import sqlite3
import uuid
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


class CheckoutRequest(BaseModel):
    customer_ref: str = Field(min_length=8, max_length=200)
    email: str = Field(min_length=5, max_length=254)
    name: str | None = Field(default=None, max_length=200)
    plan: Literal["monthly", "yearly"]

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


def _subscription_state(identity: dict[str, Any]) -> dict[str, Any]:
    rows = _account_rows(identity)
    active = next((row for row in rows if row["status"] == "active"), None)
    # Keep the early-access starting state only until the first explicit action.
    preview = identity["developer"] and not rows
    current = active or (rows[0] if rows else None)
    return {"userId": identity["id"], "premium": active is not None or preview,
            "status": "active" if preview else (current["status"] if current else "inactive"),
            "developer": identity["developer"], "amount": 0.0 if identity["developer"] else _configured_price("monthly"),
            "currency": "BRL", "checkoutRef": current["checkout_ref"] if current else None}


def beta_preview_allowed(account_hash: str) -> bool:
    """An owner cancellation must also disable the old Automix beta bypass."""
    if not hmac.compare_digest(account_hash.strip().lower(), DEVELOPER_EMAIL_HASH):
        return True
    with _db() as conn:
        rows = conn.execute("SELECT email FROM billing_subscriptions WHERE provider='developer'").fetchall()
    return not any(hmac.compare_digest(hashlib.sha256(str(row["email"]).strip().lower().encode()).hexdigest(), DEVELOPER_EMAIL_HASH) for row in rows)


@router.get("/subscription")
async def subscription(authorization: str | None = Header(default=None)):
    return _subscription_state(await _verified_identity(authorization))


@router.post("/subscription/checkout")
async def subscription_checkout(body: SubscriptionCheckoutRequest, authorization: str | None = Header(default=None)):
    identity = await _verified_identity(authorization)
    if _subscription_state(identity)["premium"]:
        raise HTTPException(status_code=409, detail="Cancel the active subscription before subscribing again")
    # Reuse pending checkouts to avoid duplicate subscriptions on retry.
    pending = next((r for r in _account_rows(identity) if r["status"] == "pending" and r["plan"] == body.plan), None)
    if pending:
        return {"userId": identity["id"], "checkoutRef": pending["checkout_ref"],
                "amount": pending["amount"], "currency": "BRL", "status": "pending",
                "requiresConfirmation": pending["provider"] == "developer", "checkoutUrl": pending["checkout_url"]}
    if identity["developer"]:
        checkout_ref = str(uuid.uuid4())
        _save_checkout(checkout_ref=checkout_ref, customer_ref=identity["id"], provider="developer",
                       provider_subscription_id=None, checkout_id=None, email=identity["email"],
                       plan=body.plan, amount=0.0, status="pending", checkout_url=None)
        return {"userId": identity["id"], "checkoutRef": checkout_ref, "amount": 0.0,
                "currency": "BRL", "status": "pending", "requiresConfirmation": True, "checkoutUrl": None}
    # All other users keep the environment price and real provider checkout.
    result = await create_mercado_pago_checkout(CheckoutRequest(
        customer_ref=identity["id"], email=identity["email"], plan=body.plan))
    saved = next(row for row in _account_rows(identity) if row["checkout_ref"] == result["checkoutRef"])
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


@router.post("/checkout/mercadopago")
async def create_mercado_pago_checkout(body: CheckoutRequest):
    if not MP_ACCESS_TOKEN:
        raise HTTPException(status_code=503, detail="Mercado Pago is not configured")

    amount = _price(body.plan)
    checkout_ref = str(uuid.uuid4())
    recurring = {
        "frequency": 1 if body.plan == "monthly" else 12,
        "frequency_type": "months",
        "transaction_amount": amount,
        "currency_id": "BRL",
    }
    payload = {
        "reason": "Orb Premium",
        "external_reference": checkout_ref,
        "payer_email": str(body.email),
        "auto_recurring": recurring,
        "back_url": f"{PUBLIC_BASE_URL}/api/billing/return/mercadopago",
        "status": "pending",
    }

    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
        response = await client.post(
            f"{MP_API}/preapproval",
            headers={
                "Authorization": f"Bearer {MP_ACCESS_TOKEN}",
                "Content-Type": "application/json",
            },
            json=payload,
        )

    if response.status_code >= 400:
        raise HTTPException(
            status_code=502,
            detail=f"Mercado Pago checkout failed: {response.text[:800]}",
        )

    data = response.json()
    provider_id = str(data.get("id") or "")
    checkout_url = data.get("init_point")
    if not provider_id or not checkout_url:
        raise HTTPException(status_code=502, detail="Mercado Pago returned an invalid checkout")

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
    if not _event_once("mercadopago", event_id):
        return {"ok": True, "duplicate": True}

    event_type = str(body.get("type") or "")
    async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
        if event_type == "subscription_preapproval":
            response = await client.get(
                f"{MP_API}/preapproval/{data_id}",
                headers={"Authorization": f"Bearer {MP_ACCESS_TOKEN}"},
            )
            if response.status_code < 400:
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
            if response.status_code < 400:
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
