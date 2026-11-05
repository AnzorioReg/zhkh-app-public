from dataclasses import dataclass
from datetime import datetime, timezone
import json
import sqlite3

class AuthorizationRequired(Exception):
    pass

@dataclass(frozen=True)
class Record:
    external_id: str
    account_id: str
    kind: str
    payload: dict

@dataclass(frozen=True)
class Connection:
    user_id: str
    apartment_id: str
    provider: str
    account_id: str
    credential_ref: str

class SyncEngine:
    """Internal prototype. No live adapters, HTTP API, vault or scheduler.
    Call only from a future trusted authenticated layer after ownership checks.
    """
    def __init__(self, database, adapters):
        self.db = sqlite3.connect(database)
        self.adapters = adapters
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS records (
            user_id TEXT, apartment_id TEXT, provider TEXT, account_id TEXT,
            external_id TEXT, kind TEXT, payload TEXT,
            PRIMARY KEY(user_id, apartment_id, provider, account_id, kind, external_id)
        );
        CREATE TABLE IF NOT EXISTS sync_status (
            user_id TEXT, apartment_id TEXT, provider TEXT, account_id TEXT,
            state TEXT, last_success TEXT,
            PRIMARY KEY(user_id, apartment_id, provider, account_id)
        );
        """)

    def sync(self, connection, credential_resolver):
        c = connection
        scope = (c.user_id, c.apartment_id, c.provider, c.account_id)
        adapter = self.adapters.get(c.provider)
        state = "not_configured"
        encoded = []
        if adapter is not None:
            try:
                records = list(adapter.fetch(c.account_id, credential_resolver(c)))
                if any(r.account_id != c.account_id for r in records):
                    raise ValueError("Account mismatch")
                if any(not r.external_id or r.kind not in {"charge", "receipt", "payment", "reading"} for r in records):
                    raise ValueError("Invalid record")
                encoded = [(r.external_id, r.kind, json.dumps(r.payload, ensure_ascii=False, allow_nan=False)) for r in records]
                state = "success"
            except AuthorizationRequired:
                state = "reauthorization_required"
            except Exception:
                state = "error"
        with self.db:
            if state == "success":
                for external_id, kind, payload in encoded:
                    self.db.execute("INSERT OR REPLACE INTO records VALUES (?,?,?,?,?,?,?)", (*scope, external_id, kind, payload))
            stamp = datetime.now(timezone.utc).isoformat() if state == "success" else None
            self.db.execute("""INSERT INTO sync_status VALUES (?,?,?,?,?,?)
                ON CONFLICT(user_id, apartment_id, provider, account_id)
                DO UPDATE SET state=excluded.state,
                last_success=COALESCE(excluded.last_success, sync_status.last_success)""", (*scope, state, stamp))
        return state

    def records(self, connection):
        c = connection
        return self.db.execute("SELECT external_id, kind, payload FROM records WHERE user_id=? AND apartment_id=? AND provider=? AND account_id=?", (c.user_id, c.apartment_id, c.provider, c.account_id)).fetchall()
