# Orb Billing — Mercado Pago

Billing recorrente do Orb Premium usando Mercado Pago.

## Estado atual

As novas assinaturas ficam fechadas por padrão.

O endpoint de checkout só cria novas assinaturas quando:

```text
ORB_PREMIUM_SIGNUPS_ENABLED=true
```

Sem essa variável, ou com valor `false`, o endpoint retorna HTTP 403 e nenhum
checkout é criado.

## Arquitetura

O APK nunca recebe chaves privadas do Mercado Pago.

Orb Android -> Orb FastAPI -> Mercado Pago -> Webhooks -> entitlement Premium.

## Variáveis de ambiente

Configure no servidor, nunca no APK e nunca no GitHub:

- `MERCADO_PAGO_ACCESS_TOKEN`
- `MERCADO_PAGO_WEBHOOK_SECRET`
- `ORB_PREMIUM_MONTHLY_PRICE`
- `ORB_PREMIUM_YEARLY_PRICE`
- `ORB_PREMIUM_SIGNUPS_ENABLED` (padrão: `false`)
- `ORB_BILLING_DB_PATH`
- `PUBLIC_BASE_URL`

## Endpoints

- `GET /api/billing/providers`
- `POST /api/billing/checkout/mercadopago`
- `GET /api/billing/status/mercadopago?plan_id=...`
- `GET /api/billing/status?checkout_ref=...`
- `POST /api/billing/webhooks/mercadopago`

## Checkout

O Mercado Pago usa um plano de assinatura (`preapproval_plan`) e devolve um
`init_point` hospedado pelo próprio Mercado Pago.

Exemplo:

```json
{
  "customer_ref": "orb-user-id",
  "name": "Cliente",
  "plan": "monthly"
}
```

Quando as inscrições estiverem fechadas, a mesma chamada retorna HTTP 403.

## Webhook

URL:

```text
https://SEU_BACKEND/api/billing/webhooks/mercadopago
```

Ative os eventos de Planos e assinaturas e Pagamentos.

A chave secreta gerada pelo Mercado Pago deve ser salva em
`MERCADO_PAGO_WEBHOOK_SECRET`.

## Persistência

O SQLite atual serve apenas como apoio de homologação. O status do Mercado Pago
também pode ser recuperado pelo `providerPlanId`, sem depender do filesystem
efêmero do Render.

Antes de abrir o Premium ao público, os entitlements devem ser associados a uma
conta autenticada do Orb em armazenamento persistente.
