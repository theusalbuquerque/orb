import asyncio
import hashlib
import hmac
import json
from weakref import WeakValueDictionary

import httpx
import pytest
from fastapi.testclient import TestClient

import billing_api as billing
import main
import server_control as control

UID = '11111111-2222-4333-8444-555555555555'
OTHER_UID = '66666666-7777-4888-8999-aaaaaaaaaaaa'
EMAIL = 'orb-user@example.com'
AUTH = {'Authorization': 'Bearer verified-test-token'}


@pytest.fixture
def service(monkeypatch, tmp_path):
    monkeypatch.setattr(billing, 'DB_PATH', str(tmp_path / 'billing.sqlite3'))
    monkeypatch.setattr(billing, 'MP_ACCESS_TOKEN', 'test-provider-credential')
    monkeypatch.setattr(billing, 'MP_WEBHOOK_SECRET', 'test-webhook-key')
    monkeypatch.setattr(billing, 'SUPABASE_KEY', 'test-publishable-key')
    monkeypatch.setattr(control, 'SUPABASE_KEY', 'test-publishable-key')
    monkeypatch.setattr(billing, '_checkout_locks', WeakValueDictionary())
    monkeypatch.setenv('ORB_PREMIUM_MONTHLY_PRICE', '6.97')
    monkeypatch.setenv('ORB_PREMIUM_YEARLY_PRICE', '69.97')
    state = {'created': [], 'persisted': 0, 'provider_reads': 0,
             'provider_status': 'pending', 'courtesy': False, 'override': None,
             'provider_ref': 'test-checkout-ref', 'links': [], 'slow': False}

    async def handler(request):
        path = request.url.path
        if path == '/auth/v1/user':
            if request.headers.get('Authorization') != AUTH['Authorization']:
                return httpx.Response(401, json={})
            return httpx.Response(200, json={'id': UID, 'email': EMAIL,
                                           'email_confirmed_at': '2026-01-01T00:00:00Z'})
        if path.endswith('/orb_current_courtesy_entitlement'):
            if state['slow']:
                await asyncio.sleep(.1)
            return httpx.Response(200, json={'premium': state['courtesy']})
        if path.endswith('/orb_billing_store_checkout_link'):
            state['persisted'] += 1
            return httpx.Response(503 if state.get('persist_fail') else 200,
                                  json=False if state.get('persist_fail') else True)
        if path.endswith('/orb_my_entitlement'):
            return httpx.Response(200, json=state['override'])
        if path.endswith('/orb_sync_account_hash'):
            state['synced_hash'] = json.loads(request.content)['p_account_hash']
            return httpx.Response(200, json=None)
        if path.endswith('/orb_remote_config'):
            return httpx.Response(200, json={'orbSwitchEnabled': False, 'refreshIntervalSeconds': 90})
        if path.endswith('/orb_billing_checkout_links'):
            if state.get('links_fail'):
                return httpx.Response(503, json={})
            return httpx.Response(200, json=state['links'])
        if path == '/preapproval/search':
            if state.get('search_fail'):
                return httpx.Response(503, json={})
            return httpx.Response(200, json={'results': []})
        if path == '/preapproval' and request.method == 'POST':
            payload = json.loads(request.content)
            if payload['payer_email'] == 'other-site@example.com':
                return httpx.Response(400, json={'error': 'guest_site_mismatch',
                                                'message': 'Payer is associated with a different site'})
            await asyncio.sleep(.01)
            state['created'].append(payload)
            state['provider_ref'] = payload['external_reference']
            return httpx.Response(201, json={'id': 'mp-test-1', 'status': 'pending',
                'init_point': 'https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=mp-test-1'})
        if path == '/preapproval/mp-test-1':
            state['provider_reads'] += 1
            if state.get('provider_fail'):
                return httpx.Response(503, json={})
            if state.get('provider_network_fail'):
                raise httpx.ConnectTimeout('provider unavailable', request=request)
            if state.get('provider_invalid_json'):
                return httpx.Response(200, text='invalid json')
            return httpx.Response(200, json={'id': 'mp-test-1',
                'external_reference': state['provider_ref'], 'status': state['provider_status'],
                'next_payment_date': state.get('renewal'), 'reason': 'Orb Premium', 'auto_recurring': {'currency_id': 'BRL',
                'frequency_type': 'months', 'frequency': 1, 'transaction_amount': 6.97}})
        raise AssertionError(f'Unexpected external request: {request.method} {path}')

    real_client = httpx.AsyncClient
    def mocked_client(*args, **kwargs):
        kwargs['transport'] = httpx.MockTransport(handler)
        return real_client(*args, **kwargs)
    monkeypatch.setattr(httpx, 'AsyncClient', mocked_client)
    with TestClient(main.app) as client:
        yield client, state


def save_pending(ref='test-checkout-ref', owner=UID, status='pending'):
    billing._save_checkout(checkout_ref=ref, customer_ref=owner, provider='mercadopago',
        provider_subscription_id='mp-test-1', checkout_id=None, email=EMAIL,
        plan='monthly', amount=6.97, status=status, checkout_url='https://www.mercadopago.com.br/pay')


def signed_event(event='event-test-1'):
    data_id = 'mp-test-1'
    request_id = 'request-test-1'
    ts = '1793941200'
    manifest = f'id:{data_id};request-id:{request_id};ts:{ts};'
    signature = hmac.new(billing.MP_WEBHOOK_SECRET.encode(), manifest.encode(), hashlib.sha256).hexdigest()
    return ({'id': event, 'type': 'subscription_preapproval', 'data': {'id': data_id},
             'status': 'authorized'},
            {'x-request-id': request_id, 'x-signature': f'ts={ts},v1={signature}'})


def test_control_registered_and_requires_auth(service):
    client, state = service
    assert client.get('/api/control/bootstrap').status_code == 401
    assert client.get('/api/control/health').json()['supabaseConfigured'] is True
    assert '/api/control/bootstrap' in client.get('/openapi.json').json()['paths']
    assert state['created'] == []


def test_bootstrap_uses_verified_account_and_remote_flags(service):
    client, state = service
    response = client.get('/api/control/bootstrap', headers=AUTH)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['userId'] == UID
    assert result['entitlement']['premium'] is False
    assert result['config']['orbSwitchEnabled'] is False
    assert result['config']['refreshIntervalSeconds'] == 90
    assert state['synced_hash'] == hashlib.sha256(EMAIL.encode()).hexdigest()


def test_bootstrap_does_not_grant_another_accounts_subscription_by_email(service):
    client, _ = service
    save_pending(owner=OTHER_UID, status='active')
    response = client.get('/api/control/bootstrap', headers=AUTH)
    assert response.status_code == 200
    assert response.json()['entitlement']['premium'] is False


def test_bootstrap_recovers_signed_persisted_paid_subscription(service):
    client, state = service
    ref = billing._orb_signed_checkout_ref(UID)
    state.update(provider_ref=ref, provider_status='authorized', links=[
        {'checkout_ref': ref, 'provider_subscription_id': 'mp-test-1', 'plan': 'monthly'}])
    result = client.get('/api/control/bootstrap', headers=AUTH)
    assert result.status_code == 200, result.text
    assert result.json()['entitlement']['premium'] is True
    assert result.json()['entitlement']['plan'] == 'monthly'


def test_bootstrap_does_not_require_legacy_provider_search_for_free_accounts(service):
    client, state = service
    state['search_fail'] = True
    result = client.get('/api/control/bootstrap', headers=AUTH)
    assert result.status_code == 200, result.text
    assert result.json()['entitlement']['premium'] is False
    # Full status verification keeps the slow legacy recovery path and reports
    # its provider outage instead of declaring the payment invalid.
    assert client.get('/api/billing/subscription', headers=AUTH).status_code == 503


def test_courtesy_is_available_without_mercado_pago(service):
    client, state = service
    state['courtesy'] = True
    state['provider_network_fail'] = True
    state['search_fail'] = True
    save_pending()
    response = client.get('/api/control/bootstrap', headers=AUTH)
    assert response.status_code == 200, response.text
    assert response.json()['entitlement']['premium'] is True
    assert state['provider_reads'] == 0
    response = client.get('/api/billing/subscription', headers=AUTH)
    assert response.status_code == 200, response.text
    assert response.json()['premium'] is True
    assert state['provider_reads'] == 0


def test_expired_courtesy_does_not_revoke_paid_subscription():
    result = control._resolve_entitlement({'premium': True, 'plan': 'monthly'},
        {'premium_override': True, 'premium_expires_at': '2020-01-01T00:00:00Z'})
    assert result['premium'] is True
    assert result['source'] == 'billing'
    assert control._resolve_entitlement({'premium': True}, {'premium_override': False})['premium'] is False


@pytest.mark.parametrize('path,budget_module,budget_name', [
    ('/api/control/bootstrap', control, 'BOOTSTRAP_BUDGET_SECONDS'),
    ('/api/billing/subscription', billing, 'STATUS_BUDGET_SECONDS')])
def test_status_timeout_is_recoverable_not_inactive_access(service, monkeypatch, path, budget_module, budget_name):
    client, state = service
    state['slow'] = True
    monkeypatch.setattr(budget_module, budget_name, .01)
    response = client.get(path, headers=AUTH)
    assert response.status_code == 503
    assert 'premium' not in response.json()


@pytest.mark.parametrize('flag', ['links_fail', 'search_fail'])
def test_recovery_failure_does_not_report_false_unsubscribed_status(service, flag):
    client, state = service
    state[flag] = True
    assert client.get('/api/billing/subscription', headers=AUTH).status_code == 503


def test_payment_email_does_not_change_orb_owner_or_entitlement_email(service):
    client, state = service
    response = client.post('/api/billing/subscription/checkout', headers=AUTH,
                           json={'plan': 'monthly', 'payer_email': 'payment@example.com'})
    assert response.status_code == 200, response.text
    assert response.json()['userId'] == UID
    assert state['created'][0]['payer_email'] == 'payment@example.com'
    assert billing._orb_signed_ref_owner(state['created'][0]['external_reference']) == UID
    row = billing._account_rows({'id': UID})[0]
    assert row['email'] == EMAIL
    assert row['customer_ref'] == UID
    assert row['status'] == 'pending'
    assert state['persisted'] == 1


def test_site_mismatch_is_actionable_and_never_grants_premium(service):
    client, state = service
    response = client.post('/api/billing/subscription/checkout', headers=AUTH,
                           json={'plan': 'monthly', 'payer_email': 'other-site@example.com'})
    assert response.status_code == 502
    assert response.json()['detail']['code'] == 'MP_PAYER_SITE_MISMATCH'
    assert response.json()['detail']['providerCode'] == 'guest_site_mismatch'
    assert billing._account_rows({'id': UID}) == []
    assert state['created'] == []


@pytest.mark.parametrize('payload', [
    {'plan': 'monthly', 'payer_email': 'not-an-email'},
    {'plan': 'monthly', 'payer_email': 'a b@example.com'},
    {'plan': 'monthly', 'customer_ref': OTHER_UID},
    {'plan': 'monthly', 'email': 'victim@example.com'}])
def test_checkout_rejects_invalid_email_and_client_identity_spoofing(service, payload):
    client, state = service
    assert client.post('/api/billing/subscription/checkout', headers=AUTH, json=payload).status_code == 422
    assert state['created'] == []


def test_pending_retry_repersists_link_before_exposing_payment_url(service):
    client, state = service
    state['persist_fail'] = True
    response = client.post('/api/billing/subscription/checkout', headers=AUTH, json={'plan': 'monthly'})
    assert response.status_code == 503
    state['persist_fail'] = False
    response = client.post('/api/billing/subscription/checkout', headers=AUTH, json={'plan': 'monthly'})
    assert response.status_code == 200
    assert len(state['created']) == 1
    assert state['persisted'] == 2


@pytest.mark.asyncio
async def test_concurrent_checkout_retries_reuse_single_provider_subscription(service):
    _, state = service
    body = billing.SubscriptionCheckoutRequest(plan='monthly')
    one, two = await asyncio.gather(
        billing.subscription_checkout(body, AUTH['Authorization']),
        billing.subscription_checkout(body, AUTH['Authorization']))
    assert one['checkoutRef'] == two['checkoutRef']
    assert len(state['created']) == 1


def test_courtesy_account_cannot_be_charged_again(service):
    client, state = service
    state['courtesy'] = True
    assert client.post('/api/billing/subscription/checkout', headers=AUTH, json={'plan': 'monthly'}).status_code == 409
    assert state['created'] == []


@pytest.mark.parametrize('flag', ['provider_fail', 'provider_network_fail', 'provider_invalid_json'])
def test_failed_webhook_verification_can_retry_and_activate_once(service, flag):
    client, state = service
    save_pending()
    body, headers = signed_event()
    state[flag] = True
    path = '/api/billing/webhooks/mercadopago'
    assert client.post(path, headers=headers, json=body).status_code == 503
    with billing._db() as conn:
        assert conn.execute('SELECT count(*) FROM billing_events').fetchone()[0] == 0
    assert billing._account_rows({'id': UID})[0]['status'] == 'pending'
    state[flag] = False
    state['provider_status'] = 'authorized'
    assert client.post(path, headers=headers, json=body).status_code == 200
    assert billing._account_rows({'id': UID})[0]['status'] == 'active'
    reads = state['provider_reads']
    assert client.post(path, headers=headers, json=body).json()['duplicate'] is True
    assert state['provider_reads'] == reads


def test_unsigned_webhook_never_activates_access(service):
    client, state = service
    save_pending()
    body, _ = signed_event()
    assert client.post('/api/billing/webhooks/mercadopago', json=body).status_code == 401
    assert state['provider_reads'] == 0
    assert billing._account_rows({'id': UID})[0]['status'] == 'pending'


def test_client_claimed_webhook_status_is_not_payment_authority(service):
    client, state = service
    save_pending()
    body, headers = signed_event()
    assert client.post('/api/billing/webhooks/mercadopago', headers=headers, json=body).status_code == 200
    assert billing._account_rows({'id': UID})[0]['status'] == 'pending'


def test_provider_health_exposes_only_webhook_configuration_presence(service):
    client, _ = service
    result = client.get('/api/billing/providers').json()
    assert result['mercadoPago']['webhookConfigured'] is True
    assert billing.MP_WEBHOOK_SECRET not in json.dumps(result)
    assert billing.MP_ACCESS_TOKEN not in json.dumps(result)


def test_provider_renewal_date_is_returned_to_app_and_bootstrap(service):
    client, state = service
    save_pending(status='active')
    state['provider_status'] = 'authorized'
    state['renewal'] = '2026-12-06T08:00:00-03:00'
    status = client.get('/api/billing/subscription', headers=AUTH)
    assert status.status_code == 200, status.text
    assert status.json()['billingActive'] is True
    assert status.json()['nextRenewalAt'] == '2026-12-06T11:00:00+00:00'
    result = client.get('/api/control/bootstrap', headers=AUTH)
    assert result.status_code == 200, result.text
    assert result.json()['entitlement']['renewsAt'] == '2026-12-06T11:00:00+00:00'


def test_missing_renewal_metadata_never_fabricates_date_or_revokes_paid_access(service):
    client, state = service
    save_pending(status='active')
    state['provider_fail'] = True
    result = client.get('/api/billing/subscription', headers=AUTH)
    assert result.status_code == 200
    assert result.json()['premium'] is True
    assert result.json()['nextRenewalAt'] is None


def test_developer_checkout_uses_same_real_monthly_provider_flow(service, monkeypatch):
    client, state = service
    async def developer_identity(_authorization):
        return {'id': UID, 'email': EMAIL, 'developer': True}
    monkeypatch.setattr(billing, '_verified_identity', developer_identity)
    state['courtesy'] = True
    result = client.post('/api/billing/subscription/checkout', headers=AUTH, json={'plan': 'monthly'})
    assert result.status_code == 200, result.text
    assert result.json()['amount'] == 6.97
    assert result.json()['requiresConfirmation'] is False
    assert len(state['created']) == 1
