# Orb Billing — Mercado Pago + Asaas

Primeira camada de billing recorrente do Orb.

## Arquitetura

O APK nunca recebe chaves privadas dos gateways.

Orb Android -> Orb FastAPI -> Mercado Pago / Asaas -> Webhooks -> status local -> entitlement Premium.

## Variáveis de ambiente

Configure no servidor (por exemplo, Render), nunca no APK e nunca no GitHub:

- `MERCADO_PAGO_ACCESS_TOKEN`
- `MERCADO_PAGO_WEBHOOK_SECRET`
- `ASAAS_API_KEY`
- `ASAAS_WEBHOOK_TOKEN`
- `ASAAS_API_BASE_URL` (opcional; produção: `https://api.asaas.com/v3`)
- `ORB_PREMIUM_MONTHLY_PRICE`
- `ORB_PREMIUM_YEARLY_PRICE`
- `ORB_BILLING_DB_PATH`
- `PUBLIC_BASE_URL`

Para sandbox do Asaas, use `https://api-sandbox.asaas.com/v3`.

## Endpoints

- `GET /api/billing/providers`
- `POST /api/billing/checkout/mercadopago`
- `POST /api/billing/checkout/asaas`
- `GET /api/billing/status?checkout_ref=...`
- `POST /api/billing/webhooks/mercadopago`
- `POST /api/billing/webhooks/asaas`

Payload de checkout:

```json
{
  "customer_ref": "orb-user-or-install-id",
  "email": "cliente@example.com",
  "name": "Cliente",
  "plan": "monthly"
}
```

A resposta contém `checkoutUrl` para abrir no navegador e `checkoutRef` para consultar o estado.

## Webhooks

Mercado Pago:

`https://SEU_BACKEND/api/billing/webhooks/mercadopago`

Ative os tópicos de Assinaturas e Payments. Configure o secret em
`MERCADO_PAGO_WEBHOOK_SECRET`.

Asaas:

`https://SEU_BACKEND/api/billing/webhooks/asaas`

Configure um `authToken` forte no Asaas e salve o mesmo valor em
`ASAAS_WEBHOOK_TOKEN`. O servidor valida o header `asaas-access-token`.

Eventos recomendados no Asaas:

- `CHECKOUT_PAID`
- `CHECKOUT_CANCELED`
- `CHECKOUT_EXPIRED`
- `SUBSCRIPTION_CREATED`
- `PAYMENT_RECEIVED`
- `PAYMENT_CONFIRMED`
- `PAYMENT_OVERDUE`
- `PAYMENT_REFUNDED`
- `PAYMENT_DELETED`

## Persistência

A implementação inicial usa SQLite para homologação. Em Render, a base precisa
estar em disco persistente por meio de `ORB_BILLING_DB_PATH`; não use o
filesystem efêmero como fonte definitiva de entitlements.

Antes de produção, a tabela de billing deve migrar para o banco permanente da
conta Orb (Postgres/Supabase, por exemplo), e `customer_ref` deve vir de uma
sessão autenticada do Orb em vez de ser aceito diretamente do cliente.

## Pix

- Asaas: o checkout recorrente desta primeira implementação usa cartão.
  `billingType=PIX` em uma assinatura comum não é Pix Automático. O Pix
  Automático exige o fluxo de autorização próprio do Asaas.
- Mercado Pago: o checkout hospedado decide os meios disponíveis para a conta e
  para a assinatura. A confirmação real sempre vem por webhook.
