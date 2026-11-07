"""Isolated backend regressions that run without live payment credentials."""
from __future__ import annotations

import ast
import asyncio
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any
import unittest
from unittest.mock import AsyncMock


class HTTPException(Exception):
    def __init__(self, status_code: int, detail: Any = None):
        self.status_code = status_code
        self.detail = detail
        super().__init__(str(detail))


def load_functions(*names: str) -> dict[str, Any]:
    source = ast.parse(Path(__file__).with_name("billing_api.py").read_text())
    functions = []
    for node in source.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in names:
            node.decorator_list = []
            functions.append(node)
    assert len(functions) == len(names)
    module = ast.Module(
        body=[ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")],
                             level=0), *functions], type_ignores=[]
    )
    ast.fix_missing_locations(module)
    env: dict[str, Any] = {
        "Any": Any, "asyncio": asyncio, "HTTPException": HTTPException,
        "Header": lambda default=None: default,
        "datetime": datetime, "timezone": timezone,
        "httpx": SimpleNamespace(HTTPError=OSError),
        "OWNER_COUNT_REFRESH_SECONDS": 300,
        "OWNER_COUNT_MAX_STALE_SECONDS": 86400,
        "OWNER_COUNT_INITIAL_WAIT_SECONDS": 0.03,
        "SUPABASE_KEY": "public-key",
        "SUPABASE_URL": "https://example.supabase.co",
        "_owner_count_snapshot": None,
        "_billing_logger": type("Logger", (), {"warning": lambda *_: None})(),
    }
    exec(compile(module, str(Path(__file__).with_name("billing_api.py")), "exec"), env)
    return env


def snapshot(minutes_ago: int = 0) -> dict[str, Any]:
    return {
        "activePremiumUsers": 3,
        "paidPremiumUsers": 2,
        "courtesyPremiumUsers": 1,
        "unlinkedPaidSubscriptions": 0,
        "updatedAt": (datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)).isoformat(),
    }


class PremiumBackendTests(unittest.IsolatedAsyncioTestCase):
    def test_snapshot_validation_rejects_inconsistent_and_untrusted_data(self):
        e = load_functions("_normalize_owner_count_snapshot")
        self.assertIsNotNone(e["_normalize_owner_count_snapshot"](snapshot()))
        invalid = {**snapshot(), "paidPremiumUsers": -1}
        self.assertIsNone(e["_normalize_owner_count_snapshot"](invalid))
        invalid = {**snapshot(), "activePremiumUsers": 999}
        self.assertIsNone(e["_normalize_owner_count_snapshot"](invalid))
        invalid = {**snapshot(), "courtesyPremiumUsers": "1"}
        self.assertIsNone(e["_normalize_owner_count_snapshot"](invalid))

    async def test_stale_verified_count_returns_immediately_and_refreshes(self):
        e = load_functions("owner_active_premium_count", "_owner_count_snapshot_age")
        e["_verified_identity"] = AsyncMock(return_value={"id": "owner", "developer": True})
        e["_load_persisted_owner_count"] = AsyncMock(return_value=snapshot(10))
        e["_begin_owner_count_refresh"] = AsyncMock()
        result = await e["owner_active_premium_count"]("Bearer test")
        self.assertEqual(result["activePremiumUsers"], 3)
        self.assertTrue(result["stale"])
        e["_begin_owner_count_refresh"].assert_awaited_once()

    async def test_old_verified_count_survives_provider_outage(self):
        e = load_functions("owner_active_premium_count", "_owner_count_snapshot_age")
        e["_verified_identity"] = AsyncMock(return_value={"id": "owner", "developer": True})
        e["_load_persisted_owner_count"] = AsyncMock(return_value=snapshot(60 * 48))
        e["_begin_owner_count_refresh"] = AsyncMock(
            side_effect=RuntimeError("provider temporarily unavailable")
        )
        # A previously verified count is still safe to display as stale.
        # Refresh scheduling errors must not discard it.
        result = await e["owner_active_premium_count"]("Bearer test")
        self.assertEqual(result["activePremiumUsers"], 3)
        self.assertTrue(result["stale"])

    async def test_owner_only_count(self):
        e = load_functions("owner_active_premium_count")
        e["_verified_identity"] = AsyncMock(return_value={"id": "visitor", "developer": False})
        with self.assertRaises(HTTPException) as err:
            await e["owner_active_premium_count"]("Bearer test")
        self.assertEqual(err.exception.status_code, 403)

    async def test_initial_count_waits_briefly_then_returns_verified_data(self):
        e = load_functions("owner_active_premium_count")
        e["_verified_identity"] = AsyncMock(return_value={"id": "owner", "developer": True})
        e["_load_persisted_owner_count"] = AsyncMock(return_value=None)
        future = asyncio.get_running_loop().create_future()
        future.set_result(snapshot())
        e["_begin_owner_count_refresh"] = AsyncMock(return_value=future)
        result = await e["owner_active_premium_count"]("Bearer test")
        self.assertEqual(result["paidPremiumUsers"], 2)
        self.assertFalse(result["stale"])

    async def test_missing_count_timeout_is_not_a_fabricated_zero(self):
        e = load_functions("owner_active_premium_count")
        e["_verified_identity"] = AsyncMock(return_value={"id": "owner", "developer": True})
        e["_load_persisted_owner_count"] = AsyncMock(return_value=None)
        future = asyncio.get_running_loop().create_future()
        e["_begin_owner_count_refresh"] = AsyncMock(return_value=future)
        with self.assertRaises(HTTPException) as err:
            await e["owner_active_premium_count"]("Bearer test")
        self.assertEqual(err.exception.status_code, 503)
        self.assertFalse(future.cancelled())  # Background work survives the Android timeout.
        future.cancel()

    async def test_verified_paid_and_developer_entitlements_skip_provider(self):
        e = load_functions("subscription_for_identity")
        e["_subscription_state"] = lambda identity, courtesy=None: {
            "premium": True, "billingActive": bool(identity.get("paid")),
        }
        e["_next_renewal_for_account"] = AsyncMock(return_value=None)
        e["_verified_courtesy_entitlement"] = AsyncMock(
            side_effect=AssertionError("Known premium must not block on courtesy lookup")
        )
        for identity in ({"developer": True, "paid": False},
                         {"developer": False, "paid": True}):
            self.assertTrue((await e["subscription_for_identity"](identity, "Bearer test"))["premium"])
        e["_verified_courtesy_entitlement"].assert_not_awaited()

    async def test_verified_provider_and_courtesy_are_deduplicated(self):
        e = load_functions("_refresh_verified_owner_count")
        class Response:
            status_code = 200
            def __init__(self, data):
                self.data = data
            def json(self):
                return self.data
        class Client:
            async def __aenter__(self):
                return self
            async def __aexit__(self, *_):
                return None
            async def post(self, url, **_):
                if "courtesy_user_ids" in url:
                    return Response(["user1"])
                return Response([
                    {"provider_subscription_id": "paid1", "user_id": "user1"},
                    {"provider_subscription_id": "paid2", "user_id": "user2"},
                ])
        class Connection:
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return None
            def execute(self, *_):
                return self
            def fetchall(self):
                return []
        e["httpx"] = SimpleNamespace(AsyncClient=lambda **_: Client())
        e["REQUEST_TIMEOUT"] = None
        e["_owner_count_headers"] = lambda _: {}
        e["_provider_active_orb_subscriptions"] = AsyncMock(return_value=[
            {"id": "paid1", "external_reference": ""},
            {"id": "paid2", "external_reference": ""},
        ])
        e["_db"] = Connection
        e["_orb_signed_ref_owner"] = lambda _: None
        e["_now"] = lambda: datetime.now(timezone.utc).isoformat()
        e["_save_persisted_owner_count"] = AsyncMock()
        count = await e["_refresh_verified_owner_count"]("Bearer owner")
        self.assertEqual(count["activePremiumUsers"], 2)
        self.assertEqual(count["paidPremiumUsers"], 2)
        self.assertEqual(count["courtesyPremiumUsers"], 1)
        self.assertEqual(count["unlinkedPaidSubscriptions"], 0)
        e["_save_persisted_owner_count"].assert_awaited_once()

    async def test_provider_scan_reads_all_pages_and_filters_invalid_subscriptions(self):
        e = load_functions("_provider_active_orb_subscriptions")
        e["math"] = math
        e["MP_ACCESS_TOKEN"] = "test-provider-token"
        e["MP_API"] = "https://api.example.test"
        e["REQUEST_TIMEOUT"] = None

        def subscription(i: int):
            return {
                "id": f"mp-{i}",
                "status": "canceled" if i == 2 else "authorized",
                "reason": "Not Orb" if i == 3 else "Orb Premium",
                "external_reference": f"owner-{i}",
                "auto_recurring": {
                    "frequency": 1, "frequency_type": "months",
                    "currency_id": "BRL", "transaction_amount": 6.97,
                },
            }

        calls = []
        class Response:
            status_code = 200
            def __init__(self, offset):
                self.offset = offset
            def json(self):
                return {
                    "results": [subscription(i) for i in range(self.offset, min(self.offset+20, 24))],
                    "paging": {"limit": 20, "offset": self.offset, "total": 24},
                }
        class Client:
            async def __aenter__(self):
                return self
            async def __aexit__(self, *_):
                return None
            async def get(self, _, *, headers, params):
                calls.append(params.copy())
                return Response(params["offset"])
        e["httpx"] = SimpleNamespace(AsyncClient=lambda **_: Client(), HTTPError=OSError)
        subs = await e["_provider_active_orb_subscriptions"]()
        self.assertEqual(calls, [{"limit": 20, "offset": 0}, {"limit": 20, "offset": 20}])
        self.assertEqual(len(subs), 22)
        self.assertIn("mp-23", {s["id"] for s in subs})


if __name__ == "__main__":
    unittest.main()
