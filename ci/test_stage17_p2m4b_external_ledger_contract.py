"""Synthetic B1-1 atomic-ledger reference tests, NOT an enrollment authority.

No production credential, signing key, API endpoint, owner seed or real device.
SQLite here is deliberately a *test fixture* to exercise the external ledger
transaction contract; it is not deployed or called by any Android component.
"""

import concurrent.futures
import os
import sqlite3
import tempfile
import threading
import unittest


class ReferenceLedger:
    """Minimal SQLite reference: exactly-one durable consume per fixture/grant/challenge."""

    def __init__(self, path):
        self.path = path
        conn = self._connect()
        try:
            conn.execute("""CREATE TABLE IF NOT EXISTS consumed (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                fixture_id TEXT NOT NULL UNIQUE,
                grant_id TEXT NOT NULL UNIQUE,
                install_challenge TEXT NOT NULL UNIQUE,
                apk_digest TEXT NOT NULL,
                consumed_at INTEGER NOT NULL
            )""")
            conn.commit()
        finally:
            conn.close()

    def _connect(self):
        return sqlite3.connect(self.path, timeout=5, isolation_level=None)

    def consume(self, *, fixture_id, grant_id, install_challenge,
                apk_digest, now, expires, observed_apk_digest):
        # Source-independent identity and time checks. Never sign a grant.
        if not all([fixture_id, grant_id, install_challenge, apk_digest]):
            return None
        if now <= 0 or now > expires or apk_digest != observed_apk_digest:
            return None
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")  # serialize competing writers
            try:
                cur = conn.execute(
                    "INSERT INTO consumed (fixture_id, grant_id, install_challenge, "
                    "apk_digest, consumed_at) VALUES (?, ?, ?, ?, ?)",
                    (fixture_id, grant_id, install_challenge, apk_digest, now),
                )
                sequence = cur.lastrowid
                conn.execute("COMMIT")  # acknowledgment only AFTER commit
                return sequence
            except sqlite3.IntegrityError:
                conn.execute("ROLLBACK")
                return None
        finally:
            conn.close()

    def observed(self, grant_id):
        conn = self._connect()
        try:
            return conn.execute(
                "SELECT seq, fixture_id, install_challenge, apk_digest "
                "FROM consumed WHERE grant_id=?", (grant_id,),
            ).fetchone()
        finally:
            conn.close()


class ExternalLedgerContractTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(prefix="m4b-ledger-contract-", suffix=".sqlite", delete=False)
        self.path = tmp.name
        tmp.close()
        self.addCleanup(lambda: os.path.exists(self.path) and os.unlink(self.path))
        self.ledger = ReferenceLedger(self.path)
        self.now = 1900000100
        self.record = dict(
            fixture_id="synthetic-fixture-1",
            grant_id="synthetic-grant-1",
            install_challenge="synthetic-install-1",
            apk_digest="a" * 64,
            now=self.now,
            expires=self.now + 60,
            observed_apk_digest="a" * 64,
        )

    def attempt(self, **changes):
        value = {**self.record, **changes}
        return self.ledger.consume(**value)

    def test_first_commit_then_replay_denied_even_after_restart(self):
        self.assertEqual(1, self.attempt())
        self.assertIsNone(self.attempt())
        restarted = ReferenceLedger(self.path)
        self.assertIsNone(restarted.consume(**self.record))
        self.assertEqual((1, "synthetic-fixture-1", "synthetic-install-1", "a" * 64),
                         restarted.observed("synthetic-grant-1"))

    def test_uniqueness_is_global_for_grant_challenge_and_fixture(self):
        self.assertEqual(1, self.attempt())
        self.assertIsNone(self.attempt(fixture_id="fixture-2", install_challenge="new-challenge"))
        self.assertIsNone(self.attempt(grant_id="grant-2", fixture_id="fixture-2"))
        self.assertIsNone(self.attempt(grant_id="grant-3", install_challenge="new-challenge"))
        self.assertEqual(2, self.attempt(fixture_id="fixture-2", grant_id="grant-2",
                                         install_challenge="new-challenge"))

    def test_wrong_binary_and_expiry_do_not_consume(self):
        self.assertIsNone(self.attempt(observed_apk_digest="b" * 64))
        self.assertIsNone(self.attempt(now=self.now + 61))
        self.assertIsNone(self.attempt(expires=self.now - 1))
        self.assertIsNone(self.ledger.observed("synthetic-grant-1"))
        self.assertEqual(1, self.attempt())

    def test_two_competing_consumers_exactly_one_success(self):
        gate = threading.Barrier(8)

        def concurrent_attempt(_):
            gate.wait(timeout=5)
            return self.attempt()

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(concurrent_attempt, range(8)))
        self.assertEqual(1, len([x for x in results if x is not None]))
        self.assertEqual(7, len([x for x in results if x is None]))
        self.assertEqual(1, self.ledger.observed("synthetic-grant-1")[0])

    def test_lost_ack_cannot_reissue_and_requires_independent_readback(self):
        self.assertEqual(1, self.attempt())
        # Simulate lost response: caller only knows that it timed out.
        # Retrying consumption cannot create a second successful transition.
        self.assertIsNone(self.attempt())
        # A true authority must support an independent readback / adjudication.
        self.assertEqual(1, ReferenceLedger(self.path).observed("synthetic-grant-1")[0])

    def test_unknown_grant_lookup_does_not_create_any_consumption(self):
        # An audit read is not itself a grant or authority to arm.
        self.assertIsNone(self.ledger.observed("missing-grant"))
        self.assertEqual(1, self.attempt())
        self.assertIsNone(self.ledger.observed("missing-grant"))


if __name__ == "__main__":
    unittest.main()
