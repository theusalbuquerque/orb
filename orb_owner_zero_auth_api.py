"""Orb developer card verification via Mercado Pago ZDA and Supabase.

Only the developer's verified Supabase account may create/check/cancel a checkout.
A short-lived, unguessable checkout secret authorizes the hosted card form.
Never stores PAN, CVV or the Mercado Pago token, and never charges > R$0.
A ZDA authorization is NOT a recurring financial subscription.

Deployment remains disabled without explicit ORB_OWNER_ZDA_ENABLED=true.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Header, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/billing/owner-zda", tags=["billing"])
OWNER_EMAIL_SHA256 = "2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb"
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://orb-4mrh.onrender.com").rstrip("/")
MP_API = "https://api.mercadopago.com"
TABLE = "orb_owner_zda_authorizations"


def _config():
    if os.getenv("ORB_OWNER_ZDA_ENABLED", "").lower() not in ("1", "true", "yes"):
        raise HTTPException(503, "Zero-dollar card validation not enabled")
    cfg = {
        "access": os.getenv("MERCADO_PAGO_ACCESS_TOKEN", "").strip(),
        "public": os.getenv("MERCADO_PAGO_PUBLIC_KEY", "").strip(),
        "supabase": os.getenv("SUPABASE_URL", "").strip().rstrip("/"),
        "anon": os.getenv("SUPABASE_ANON_KEY", "").strip(),
        "service": os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip(),
    }
    if not all(cfg.values()) or not PUBLIC_BASE_URL.startswith("https://"):
        raise HTTPException(503, "Card validation credentials or HTTPS unavailable")
    return cfg


def _owner_digest(email: str) -> bool:
    digest = hashlib.sha256(email.strip().lower().encode()).hexdigest()
    return hmac.compare_digest(digest, OWNER_EMAIL_SHA256)


async def _owner(authorization: str | None):
    cfg = _config()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Sign in to Orb")
    token = authorization[7:].strip()
    if not token:
        raise HTTPException(401, "Invalid Orb session")
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(cfg["supabase"] + "/auth/v1/user", headers={
                "apikey": cfg["anon"], "Authorization": "Bearer " + token})
            resp.raise_for_status()
            user = resp.json()
    except (httpx.HTTPError, ValueError):
        raise HTTPException(401, "Orb session could not be validated") from None
    if not user.get("id") or not _owner_digest(str(user.get("email") or "")):
        raise HTTPException(403, "Account is not authorized")
    return {"id": str(user["id"]), "email": str(user["email"])}


async def _db(method: str, params=None, payload=None):
    cfg = _config()
    headers = {
        "apikey": cfg["service"], "Authorization": "Bearer " + cfg["service"],
        "Content-Type": "application/json", "Prefer": "return=representation",
    }
    try:
        async with httpx.AsyncClient(timeout=12) as client:
            response = await client.request(method, cfg["supabase"] + "/rest/v1/" + TABLE,
                                            headers=headers, params=params, json=payload)
            response.raise_for_status()
            return response.json()
    except (httpx.HTTPError, ValueError):
        raise HTTPException(503, "Persistent subscription storage is temporarily unavailable") from None


def _at(epoch: int) -> str:
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat()


def _secret_digest(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


async def _latest(user_id: str):
    rows = await _db("GET", {"select": "id,status,provider_payment_id", "user_id": "eq." + user_id,
                             "status": "in.(approved,canceled)", "order": "created_at.desc", "limit": "1"})
    return rows[0] if rows else None


@router.get("/status")
async def status(authorization: str | None = Header(default=None)):
    user = await _owner(authorization)
    row = await _latest(user["id"])
    return {"userId": user["id"], "subscribed": bool(row and row["status"] == "approved"),
            "status": row["status"] if row else "not_subscribed", "amount": 0,
            "validationProvider": "mercadopago_zda" if row else None}


@router.post("/start")
async def start(authorization: str | None = Header(default=None)):
    user = await _owner(authorization)
    checkout_id = str(uuid.uuid4())
    secret = secrets.token_urlsafe(32)
    now = int(time.time())
    await _db("POST", payload={
        "id": checkout_id, "user_id": user["id"],
        "checkout_secret_hash": _secret_digest(secret), "status": "pending",
        "expires_at": _at(now + 900),
    })
    return {"userId": user["id"], "checkoutRef": checkout_id, "amount": 0,
            "requiresConfirmation": False,
            "checkoutUrl": PUBLIC_BASE_URL + "/api/billing/owner-zda/card?id=" + checkout_id + "&code=" + secret}


@router.post("/cancel")
async def cancel(authorization: str | None = Header(default=None)):
    user = await _owner(authorization)
    row = await _latest(user["id"])
    if row is None or row["status"] != "approved":
        return {"userId": user["id"], "subscribed": False, "status": "not_subscribed"}
    updated = await _db("PATCH", {"id": "eq." + row["id"], "user_id": "eq." + user["id"],
                                  "status": "eq.approved"},
                        {"status": "canceled", "updated_at": _at(int(time.time()))})
    if not updated:
        raise HTTPException(409, "Subscription state changed during cancellation")
    return {"userId": user["id"], "subscribed": False, "status": "canceled"}


async def _checkout(id: str, code: str):
    rows = await _db("GET", {"select": "*", "id": "eq." + id, "limit": "1"})
    row = rows[0] if rows else None
    if row is None or not hmac.compare_digest(str(row["checkout_secret_hash"]), _secret_digest(code)):
        raise HTTPException(404, "Checkout unavailable")
    try:
        expires = datetime.fromisoformat(row["expires_at"].replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError):
        raise HTTPException(404, "Checkout invalid") from None
    if expires < time.time():
        raise HTTPException(410, "Checkout expired")
    return row


@router.get("/card", response_class=HTMLResponse)
async def card(id: str = Query(min_length=36,max_length=36), code: str = Query(min_length=32,max_length=64)):
    cfg = _config()
    row = await _checkout(id, code)
    if row["status"] != "pending":
        raise HTTPException(409, "Checkout is not pending")
    page = r'''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer">
    <title>Orb Premium · Validação real do cartão</title>
    <style>body{background:#111;color:white;font:16px system-ui;margin:auto;padding:24px;max-width:500px}
    label{display:block;margin:16px 0 6px}.field,input,select{box-sizing:border-box;width:100%;height:44px;
    background:#252525;color:white;border:1px solid #777;border-radius:12px;padding:8px}
    button{background:#fff;color:#111;border:0;border-radius:40px;padding:16px;width:100%;font-weight:bold;margin-top:24px}
    h1{font-size:26px}small,p{color:#bbb}#msg{margin-top:20px} .hidden{display:none}</style>
    <script src="https://sdk.mercadopago.com/js/v2"></script></head><body>
    <h1>Orb Premium · cartão</h1><h2>Total: R$ 0,00</h2>
    <p>Validação real de cartão pelo Mercado Pago, sem cobrança e sem renovação financeira.
    O Orb não armazena número, CVV ou token do cartão.</p>
    <form id="form-checkout">
    <label>Número do cartão</label><div id="form-checkout__cardNumber" class="field"></div>
    <label>Validade</label><div id="form-checkout__expirationDate" class="field"></div>
    <label>Código de segurança</label><div id="form-checkout__securityCode" class="field"></div>
    <label>Nome no cartão</label><input id="form-checkout__cardholderName" autocomplete="cc-name" required>
    <label>Tipo de documento</label><select id="form-checkout__identificationType" required></select>
    <label>Número do documento</label><input id="form-checkout__identificationNumber" required>
    <button id="form-checkout__submit" type="submit">Validar cartão por R$ 0,00</button>
    </form><p id="msg" role="status"></p>
    <script>
    const id=__ID__, code=__CODE__;
    const mp=new MercadoPago(__PUBLIC_KEY__,{locale:'pt-BR'});
    const cardNumber=mp.fields.create('cardNumber',{placeholder:'Número do cartão'}).mount('form-checkout__cardNumber');
    mp.fields.create('expirationDate',{placeholder:'MM/AA'}).mount('form-checkout__expirationDate');
    mp.fields.create('securityCode',{placeholder:'CVV'}).mount('form-checkout__securityCode');
    const documentSelect=document.getElementById('form-checkout__identificationType');
    mp.getIdentificationTypes().then(types=>{types.forEach(type=>{
      const option=document.createElement('option');option.value=type.id;option.textContent=type.name;
      documentSelect.appendChild(option);
    });}).catch(()=>document.getElementById('msg').textContent='Não foi possível carregar tipos de documento.');
    let paymentMethodId='';
    cardNumber.on('binChange',async data=>{
      paymentMethodId='';
      try{if(!data.bin)return;
        const result=await mp.getPaymentMethods({bin:data.bin});
        const method=result.results?.[0];if(!method)throw new Error('Bandeira indisponível');
        paymentMethodId=method.id;
        if(method.settings?.[0]){
          cardNumber.update({settings:method.settings[0].card_number});
        }
      }catch(e){document.getElementById('msg').textContent='Cartão não reconhecido pelo provedor.';}
    });
    document.getElementById('form-checkout').addEventListener('submit',async event=>{
      event.preventDefault();
      const button=document.getElementById('form-checkout__submit');const msg=document.getElementById('msg');
      button.disabled=true;msg.textContent='Validando cartão diretamente no Mercado Pago…';
      try{
        if(!paymentMethodId)throw new Error('Aguarde o reconhecimento da bandeira do cartão.');
        const card=await mp.fields.createCardToken({
          cardholderName:document.getElementById('form-checkout__cardholderName').value,
          identificationType:documentSelect.value,
          identificationNumber:document.getElementById('form-checkout__identificationNumber').value,
        });
        if(!card?.id)throw new Error('O Mercado Pago não gerou um token válido.');
        const response=await fetch('/api/billing/owner-zda/verify',{
          method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({id,code,token:card.id,payment_method_id:paymentMethodId}),
        });
        const data=await response.json();
        if(!response.ok||!data.subscribed)throw new Error(data.detail||'Cartão recusado pelo provedor.');
        msg.textContent='Cartão validado de verdade. Nenhuma cobrança foi efetuada: R$ 0,00. Volte ao Orb para ver a confirmação.';
        document.getElementById('form-checkout').classList.add('hidden');
      }catch(error){msg.textContent=error.message||'Falha de validação';button.disabled=false;}
    });
    </script></body></html>'''

    page = page.replace("__ID__", json.dumps(id)).replace("__CODE__", json.dumps(code)).replace("__PUBLIC_KEY__", json.dumps(cfg["public"]))
    return HTMLResponse(page, headers={
        "Cache-Control": "no-store, max-age=0", "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
        "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline' https://sdk.mercadopago.com https://http2.mlstatic.com; frame-src https://*.mercadopago.com https://*.mercadolibre.com; connect-src 'self' https://*.mercadopago.com https://*.mercadolibre.com; style-src 'unsafe-inline'"
    })


class CardVerification(BaseModel):
    id: str = Field(min_length=36,max_length=36)
    code: str = Field(min_length=32,max_length=64)
    token: str = Field(min_length=12,max_length=256)
    payment_method_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{2,40}$")


async def _payer_email(user_id: str):
    cfg = _config()
    # Read from trusted Supabase Auth, not user-controlled form fields.
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.get(cfg["supabase"] + "/auth/v1/admin/users/" + quote(user_id,safe=""),
                                        headers={"apikey": cfg["service"],
                                                 "Authorization": "Bearer " + cfg["service"]})
            response.raise_for_status()
            user = response.json()
    except (httpx.HTTPError, ValueError):
        raise HTTPException(503, "Account identity verification unavailable") from None
    email = str(user.get("email") or "").strip().lower()
    if str(user.get("id")) != user_id or not _owner_digest(email):
        raise HTTPException(403, "Account no longer authorized")
    return email


@router.post("/verify")
async def verify(body: CardVerification):
    cfg = _config()
    row = await _checkout(body.id, body.code)
    if row["status"] != "pending":
        raise HTTPException(409, "Checkout has already been submitted")
    email = await _payer_email(row["user_id"])
    # Atomic claim ensures a concurrent verify cannot send a second provider request.
    claimed = await _db("PATCH", {"id": "eq." + row["id"], "status": "eq.pending"},
                        {"status": "processing", "updated_at": _at(int(time.time()))})
    if not claimed:
        raise HTTPException(409, "Checkout is being processed")
    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.post(MP_API + "/v1/payments", headers={
                "Authorization": "Bearer " + cfg["access"], "Content-Type": "application/json",
                "X-Idempotency-Key": row["id"], "X-Card-Validation": "card_validation",
            }, json={
                "transaction_amount": 0, "token": body.token,
                "payment_method_id": body.payment_method_id,
                "payer": {"email": email, "type": "guest"},
                "description": "Orb Premium - validação de cartão de valor zero",
            })
            response.raise_for_status()
            result = response.json()
    except (httpx.HTTPError, ValueError):
        # Do not authorize on ambiguous provider timeouts. Leave as processing
        # for later reconciliation with the idempotent Mercado Pago request.
        raise HTTPException(502, "Provider did not confirm validation; review provider transaction") from None
    approved = (result.get("status") in ("approved", "authorized")
                and result.get("transaction_amount") == 0
                and result.get("operation_type") == "card_validation"
                and result.get("id") is not None)
    updated = await _db("PATCH", {"id": "eq." + row["id"], "status": "eq.processing"},
                        {"status": "approved" if approved else "rejected",
                         "provider_payment_id": str(result.get("id") or "") or None,
                         "provider_status": str(result.get("status") or ""),
                         "updated_at": _at(int(time.time()))})
    if not updated:
        raise HTTPException(503, "Could not persist payment provider result")
    if not approved:
        raise HTTPException(422, "Mercado Pago did not approve card validation")
    return {"subscribed": True, "status": "approved", "amount": 0}
