import unittest
from dataclasses import replace
from sync_core import SyncEngine, Connection, Record, AuthorizationRequired

class FixtureAdapter:
    def fetch(self, account, credential):
        return [Record("fixture-1", account, "charge", {"amount": "100.00"})]

class Tests(unittest.TestCase):
    def setUp(self):
        self.c = Connection("user1", "flat1", "fixture", "account1", "vault-reference")
        self.e = SyncEngine(":memory:", {"fixture": FixtureAdapter()})
    def tearDown(self):
        self.e.db.close()
    def sync(self):
        return self.e.sync(self.c, lambda _: "test-only")
    def test_not_configured(self):
        self.assertEqual(self.e.sync(replace(self.c, provider="gis_zhkh"), lambda _: None), "not_configured")
    def test_success(self):
        self.assertEqual(self.sync(), "success")
        self.assertEqual(len(self.e.records(self.c)), 1)
    def test_idempotent(self):
        self.sync(); self.sync()
        self.assertEqual(len(self.e.records(self.c)), 1)
    def test_isolation(self):
        self.sync()
        for field, value in [("user_id", "user2"), ("apartment_id", "flat2"), ("account_id", "account2"), ("provider", "other")]:
            self.assertEqual(self.e.records(replace(self.c, **{field: value})), [])
    def test_expired(self):
        def expired(_): raise AuthorizationRequired()
        self.assertEqual(self.e.sync(self.c, expired), "reauthorization_required")
    def test_mismatch(self):
        class Wrong:
            def fetch(self, *_): return [Record("1", "other-account", "charge", {})]
        self.e.adapters["fixture"] = Wrong()
        self.assertEqual(self.sync(), "error")
        self.assertEqual(self.e.records(self.c), [])
    def test_failure_keeps_previous(self):
        self.sync()
        before = self.e.db.execute("SELECT last_success FROM sync_status").fetchone()[0]
        def unavailable(_): raise RuntimeError("no credentials in errors")
        self.assertEqual(self.e.sync(self.c, unavailable), "error")
        self.assertEqual(len(self.e.records(self.c)), 1)
        self.assertEqual(self.e.db.execute("SELECT last_success FROM sync_status").fetchone()[0], before)

if __name__ == "__main__": unittest.main()
