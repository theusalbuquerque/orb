import asyncio
import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

import billing_api
import main
import orb_owner_zero_auth_api as owner

UID = '11111111-2222-4333-8444-555555555555'
AUTH = {'Authorization': 'Bearer owner-test-token'}


@pytest.fixture
def management(monkeypatch):
    # Management is deliberately tested with all new-card configuration absent.
    for key in ('ORB_OWNER_ZDA_ENABLED', 'MERCADO_PAGO_ACCESS_TOKEN', 'MERCADO_PAGO_PUBLIC_KEY',
                'SUPABASE_SERVICE_ROLE_KEY'):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(billing_api, 'SUPABASE_KEY', 'test-public-key')
    state = {'subscribed': True, 'calls': [], 'status_code': 200, 'slow': False}

    async def verified(token):
        if token != AUTH['Authorization']:
            raise HTTPException(401, 'Sign in')
        return {'id': UID, 'email': 'test-owner@example.com', 'developer': True}
    monkeypatch.setattr(billing_api, '_verified_identity', verified)
    monkeypatch.setattr(owner, '_owner_digest', lambda email: email == 'test-owner@example.com')

    async def handler(request):
        state['calls'].append(request)
        assert request.headers['Authorization'] == AUTH['Authorization']
        assert request.headers['apikey'] == 'test-public-key'
        assert request.content == b'{}'
        if state['slow']:
            await asyncio.sleep(.1)
        if state['status_code'] != 200:
            return httpx.Response(state['status_code'], json={})
        cancel = request.url.path.endswith('orb_owner_zda_cancel')
        if cancel and not state.get('cancel_race'):
            state['subscribed'] = False
        return httpx.Response(200, json={
            'userId': 'other-account' if state.get('wrong_user') else UID,
            'subscribed': 'false' if state.get('bad_bool') else state['subscribed'],
            'status': 'approved' if state['subscribed'] else 'canceled', 'amount': 0})

    real = httpx.AsyncClient
    def mocked(*args, **kwargs):
        kwargs['transport'] = httpx.MockTransport(handler)
        return real(*args, **kwargs)
    monkeypatch.setattr(httpx, 'AsyncClient', mocked)
    with TestClient(main.app) as client:
        yield client, state


def test_status_works_when_new_card_feature_is_disabled(management):
    client, state = management
    response = client.get('/api/billing/owner-zda/status', headers=AUTH)
    assert response.status_code == 200
    assert response.json()['subscribed'] is True
    assert state['calls'][0].url.path.endswith('orb_owner_zda_status')


def test_cancel_is_server_confirmed_and_idempotent_without_card_credentials(management):
    client, state = management
    for _ in range(2):
        response = client.post('/api/billing/owner-zda/cancel', headers=AUTH)
        assert response.status_code == 200
        assert response.json()['subscribed'] is False
    assert len(state['calls']) == 2
    assert all(req.url.path.endswith('orb_owner_zda_cancel') for req in state['calls'])


def test_new_card_validation_still_requires_explicit_opt_in(management):
    client, state = management
    assert client.post('/api/billing/owner-zda/start', headers=AUTH).status_code == 503
    assert state['calls'] == []


@pytest.mark.parametrize('endpoint', ['status', 'cancel'])
def test_management_requires_verified_sign_in(management, endpoint):
    client, state = management
    response = client.get('/api/billing/owner-zda/status') if endpoint == 'status' else client.post('/api/billing/owner-zda/cancel')
    assert response.status_code == 401
    assert state['calls'] == []


def test_other_accounts_cannot_manage_owner_records(management, monkeypatch):
    client, state = management
    monkeypatch.setattr(owner, '_owner_digest', lambda email: False)
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).status_code == 403
    assert state['calls'] == []


@pytest.mark.parametrize('flag', ['wrong_user', 'bad_bool'])
def test_invalid_backend_state_never_reports_cancellation_success(management, flag):
    client, state = management
    state[flag] = True
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).status_code == 503


def test_storage_failure_remains_retryable(management):
    client, state = management
    state['status_code'] = 503
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).status_code == 503
    state['status_code'] = 200
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).json()['subscribed'] is False


def test_cancel_race_is_not_false_success(management):
    client, state = management
    state['cancel_race'] = True
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).status_code == 409


def test_slow_management_exits_with_recoverable_error(management, monkeypatch):
    client, state = management
    state['slow'] = True
    monkeypatch.setattr(owner, 'OWNER_MANAGEMENT_BUDGET', .01)
    assert client.post('/api/billing/owner-zda/cancel', headers=AUTH).status_code == 503
