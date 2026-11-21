"""Exercise the actual SQLite DDL used by the Android v1 -> v2 migration."""
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/haseebullahjan/roshd/messenger/MessagesStorage.kt").read_text()
DDL = re.findall(r'db\.execSQL\("(CREATE [^"\n]+)"\)', SOURCE)

class CameraQueueSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        for sql in DDL:
            if "camera_work" not in sql:
                self.db.execute(sql)
        self.db.execute("INSERT INTO documents VALUES (?,?,?,?,?)", ("owner", "scan", "old", b"encrypted-original", 1))
        self.db.commit()

    def migrate(self):
        with self.db:
            for sql in DDL:
                if "camera_work" in sql:
                    self.db.execute(sql)

    def test_migration_preserves_preexisting_encrypted_data_and_is_idempotent(self):
        self.migrate()
        self.migrate()
        self.assertEqual(b"encrypted-original", self.db.execute("SELECT payload FROM documents").fetchone()[0])
        self.assertTrue(self.db.execute("SELECT name FROM sqlite_master WHERE name='camera_work_pending'").fetchone())

    def test_duplicate_job_ids_are_isolated_by_account_and_state_survives(self):
        self.migrate()
        with self.db:
            self.db.execute("INSERT INTO camera_work VALUES ('one','id',1,'period','capturing',1)")
            self.db.execute("INSERT INTO camera_work VALUES ('two','id',2,'period','pending',2)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO camera_work VALUES ('one','id',1,'period','pending',3)")
        self.assertEqual([("one", "capturing"), ("two", "pending")], self.db.execute("SELECT scope,status FROM camera_work ORDER BY scope").fetchall())

    def test_document_and_pending_job_commit_or_rollback_together(self):
        self.migrate()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute("INSERT INTO documents VALUES ('owner','scan','new',x'010203',2)")
                self.db.execute("INSERT INTO camera_work VALUES ('owner','new',1,'period','pending',2)")
                raise RuntimeError("interrupted before commit")
        self.assertFalse(self.db.execute("SELECT id FROM documents WHERE id='new'").fetchone())
        self.assertFalse(self.db.execute("SELECT id FROM camera_work WHERE id='new'").fetchone())

if __name__ == "__main__":
    unittest.main()
