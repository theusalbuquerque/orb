from __future__ import annotations

import hashlib
import hmac
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

import httpx
from fastapi import APIRouter, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field, field_validator

router = APIRouter(prefix="/api/billing", tags=["billing"])

MP_API = "https://api.mercadopago.com"
ASAAS_API = os.getenv("ASAAS_API_BASE_URL", "https://api.asaas.com/v3").rstrip("/")
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://orb-4mrh.onrender.com").rstrip("/")
REQUEST_TIMEOUT = httpx.Timeout(25.0, connect=10.0)

MP_ACCESS_TOKEN = os.getenv("MERCADO_PAGO_ACCESS_TOKEN", "").strip()
MP_WEBHOOK_SECRET = os.getenv("MERCADO_PAGO_WEBHOOK_SECRET", "").strip()
ASAAS_API_KEY = os.getenv("ASAAS_API_KEY", "").strip()
ASAAS_WEBHOOK_TOKEN = os.getenv("ASAAS_WEBHOOK_TOKEN", "").strip()

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
    if value <= 0:
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
            f"UPDATE billing_subscriptions SET {', '.join(sets)} WHERE checkout_ref=?",
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
            WHERE provider=? AND provider_subscription_id=?
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
            WHERE provider=? AND checkout_id=?
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


@router.get("/providers")
async def providers():
    return {
        "mercadoPago": {"configured": bool(MP_ACCESS_TOKEN)},
        "asaas": {
            "configured": bool(ASAAS_API_KEY),
            "pixAutomatic": False,
            "note": "Pix Automático requires a separate Asaas authorization flow.",
        },
        "plans": {
            "monthlyConfigured": bool(os.getenv("ORB_PREMIUM_MONTHLY_PRICE", "").strip()),
            "yearlyConfigured": bool(os.getenv("ORB_PREMIUM_YEARLY_PRICE", "").strip()),
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
