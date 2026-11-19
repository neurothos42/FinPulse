import hashlib
import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import pytest
from bson import ObjectId, json_util
from bson.decimal128 import Decimal128

from migration.migrate import QUARANTINE, MigrationError, main as migrate_main, migrate, rollback
from mongo_io import MongoStore
from transaction_pipeline import Transaction, TransactionPipeline
from worker.job_worker import JobWorker
from worker.mongo_client import ensure_indexes
from worker_helpers import put_document

SAMPLE = Path(__file__).resolve().parents[1] / "samples" / "statement.pdf"
T0 = datetime(2026, 11, 1, 9, 0, tzinfo=timezone.utc)


class Legacy:
    """Three databases on one client: expensetrackerdb (Java users), finplus (Python pipeline), finpulse (target)."""

    def __init__(self, client, base):
        self.client = client
        self.java, self.py, self.target = f"{base}_java", f"{base}_py", f"{base}_target"
        self.store = MongoStore(client=client, db=self.py)            # the REAL legacy writer, incl. its unique hash index
        self.user_a, self.user_b = ObjectId(), ObjectId()
        client[self.java]["users"].insert_many([
            self.user(self.user_a, "Asha", "asha@example.com"), self.user(self.user_b, "Bala", "bala@example.com")])

    @staticmethod
    def user(oid, name, email, hash_="$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012345"):
        return {"_id": oid, "name": name, "email": email, "passwordHash": hash_, "createdAt": T0, "updatedAt": T0,
                "_class": "in.expensetrackerapp.user.model.User"}

    def statement(self):
        return TransactionPipeline(None).process_file(str(SAMPLE)).transactions

    def save_legacy(self, txns, user_id):
        self.store.save_transactions(txns, user_id=user_id)

    def snapshot(self, *names):
        """Hash of every document in every collection of the given databases: proves a source was not touched."""
        digest = hashlib.sha256()
        for name in names:
            for coll in sorted(self.client[name].list_collection_names()):
                for doc in self.client[name][coll].find({}).sort("_id", 1):
                    digest.update(json_util.dumps(doc, sort_keys=True).encode())
        return digest.hexdigest()

    def run(self, **kwargs):
        return migrate(self.client, legacy_java_db=self.java, legacy_python_db=self.py, target_db=self.target,
                       now=T0, **kwargs)

    @property
    def tx(self):
        return self.client[self.target]["transactions"]


@pytest.fixture
def legacy(real_db):
    """Real MongoDB only: the legacy writer (mongo_io.MongoStore) uses bulk_write, which the in-memory mock cannot
    run with the installed pymongo, and a migration should be proven on a real server anyway."""
    scenario = Legacy(real_db.client, real_db.name)
    yield scenario
    for name in (scenario.java, scenario.py, scenario.target):
        real_db.client.drop_database(name)


# ------------------------------------------------------------------ safety of the plan itself
def test_dry_run_changes_nothing_anywhere_and_predicts_the_result(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    before = legacy.snapshot(legacy.java, legacy.py)

    report = legacy.run(execute=False)

    assert report["mode"] == "dry-run" and report["ok"] is True
    assert report["users"]["copied"] == 2 and report["transactions"]["copied"] == 7
    assert legacy.snapshot(legacy.java, legacy.py) == before
    assert legacy.client[legacy.target].list_collection_names() == []          # not even created


def test_target_must_not_be_a_legacy_database(legacy):
    with pytest.raises(MigrationError, match="differ"):
        migrate(legacy.client, legacy_java_db=legacy.java, legacy_python_db=legacy.py, target_db=legacy.py)


# ------------------------------------------------------------------ the happy path
def test_execute_copies_users_with_the_same_ids_and_converts_transactions_to_the_canonical_model(legacy):
    originals = legacy.statement()
    legacy.save_legacy(originals, str(legacy.user_a))
    before = legacy.snapshot(legacy.java, legacy.py)

    report = legacy.run(execute=True)

    assert report["ok"] and report["transactions"]["verified_in_target"] == 7 and report["users"]["verified_in_target"] == 2
    users = legacy.client[legacy.target]["users"]
    asha = users.find_one({"_id": legacy.user_a})
    assert asha["email"] == "asha@example.com" and asha["passwordHash"].startswith("$2a$10$")     # hash copied byte for byte
    assert asha["migration"]["runId"] == report["run_id"]

    rows = list(legacy.tx.find({}))
    assert len(rows) == 7
    assert {r["sourceHash"] for r in rows} == {t.hash for t in originals}                          # legacy hash == sourceHash
    for row in rows:
        assert row["userId"] == str(legacy.user_a)
        assert row["documentId"] is None and row["jobId"] == f"migration-{report['run_id']}"
        assert isinstance(row["amount"], Decimal128) and row["amount"].to_decimal() > 0
        assert row["direction"] in {"DEBIT", "CREDIT", "UNKNOWN"} and row["category"] in {
            "Food", "Shopping", "Travel", "Transport", "Bills", "Entertainment", "Healthcare", "Education", "Rent",
            "Salary", "Investment", "Transfer", "Other"}
        assert row["legacy"]["db"] == legacy.py and row["legacy"]["runId"] == report["run_id"]
    assert legacy.snapshot(legacy.java, legacy.py) == before, "a source database was modified"
    assert {"userId_sourceHash_unique"} <= set(legacy.tx.index_information())


def test_migrated_amounts_keep_the_exact_signed_value_as_positive_amount_plus_direction(legacy):
    originals = {t.hash: t for t in legacy.statement()}
    legacy.save_legacy(list(originals.values()), str(legacy.user_a))
    legacy.run(execute=True)

    for row in legacy.tx.find({}):
        source = originals[row["sourceHash"]]
        assert row["amount"].to_decimal() == Decimal(repr(abs(source.amount))).quantize(Decimal("0.01"))
        if source.direction in ("debit", "credit"):
            assert row["direction"] == source.direction.upper()


# ------------------------------------------------------------------ nothing is ever lost
def test_every_source_row_is_accounted_for_and_unplaceable_rows_are_quarantined_verbatim(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    good = legacy.statement()[0]
    raw = legacy.client[legacy.py]["transactions"]
    extra = [
        {"hash": "no-owner", "date": "2026-10-01", "amount": -10.0, "currency": "INR", "direction": "debit",
         "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"},
        {"hash": "ghost-owner", "user_id": "not-a-user", "date": "2026-10-01", "amount": -11.0, "currency": "INR",
         "direction": "debit", "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"},
        {"user_id": str(legacy.user_a), "date": "2026-10-01", "amount": -12.0, "currency": "INR", "direction": "debit",
         "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"},                     # no hash
        {"hash": "nan-amount", "user_id": str(legacy.user_a), "date": "2026-10-01", "amount": float("nan"),
         "currency": "INR", "direction": "debit", "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"},
    ]
    raw.insert_many(extra)
    originals = {str(d["_id"]): d for d in raw.find({"hash": {"$in": ["no-owner", "ghost-owner", "nan-amount"]}})}
    originals["nohash"] = raw.find_one({"hash": None})

    report = legacy.run(execute=True)

    t = report["transactions"]
    assert t["source"] == 7 + 4 and t["copied"] == 7 and t["quarantined"] == 4 and t["balanced"] is True
    assert t["quarantine_reasons"] == {"no_user_id": 1, "owner_not_found_in_target": 1, "no_hash": 1,
                                       "unmappable: MappingError": 1}
    quarantined = {q["legacyId"]: q for q in legacy.client[legacy.target][QUARANTINE].find({})}
    assert len(quarantined) == 4
    for legacy_id, original in ((str(d["_id"]), d) for d in originals.values()):
        stored = quarantined[legacy_id]["document"]
        assert stored["_id"] == original["_id"]
        assert {k: v for k, v in stored.items() if k != "amount"} == {k: v for k, v in original.items() if k != "amount"}
    assert good["hash"] if isinstance(good, dict) else good.hash


def test_rerunning_is_idempotent_and_adds_nothing(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    legacy.client[legacy.py]["transactions"].insert_one({"hash": "orphan", "date": "2026-10-01", "amount": -1.0,
        "currency": "INR", "direction": "debit", "merchant": "X", "category": "Uncategorized", "description": "d", "source": "s"})
    first = legacy.run(execute=True)
    counts = (legacy.tx.count_documents({}), legacy.client[legacy.target]["users"].count_documents({}),
              legacy.client[legacy.target][QUARANTINE].count_documents({}))

    second = legacy.run(execute=True)

    assert second["transactions"]["copied"] == 0 and second["transactions"]["already_present"] == first["transactions"]["copied"]
    assert second["users"]["copied"] == 0 and second["users"]["already_present"] == 2
    assert second["ok"] and counts == (legacy.tx.count_documents({}),
                                       legacy.client[legacy.target]["users"].count_documents({}),
                                       legacy.client[legacy.target][QUARANTINE].count_documents({}))


def test_an_existing_target_transaction_is_never_overwritten(legacy):
    originals = legacy.statement()
    legacy.save_legacy(originals, str(legacy.user_a))
    legacy.client[legacy.target]["users"].insert_one(Legacy.user(legacy.user_a, "Asha", "asha@example.com"))
    ensure_indexes(legacy.client[legacy.target])
    legacy.tx.insert_one({"userId": str(legacy.user_a), "sourceHash": originals[0].hash, "merchant": "EDITED BY USER",
                          "jobId": "job-from-new-app", "amount": Decimal128("1.00")})

    report = legacy.run(execute=True)

    kept = legacy.tx.find_one({"userId": str(legacy.user_a), "sourceHash": originals[0].hash})
    assert kept["merchant"] == "EDITED BY USER" and kept["jobId"] == "job-from-new-app"
    assert report["transactions"]["duplicates"] == 1 and report["transactions"]["copied"] == 6 and report["ok"]
    assert legacy.client[legacy.target][QUARANTINE].find_one({"reason": "duplicate_of_existing_transaction"}) is not None


# ------------------------------------------------------------------ users and ownership
def test_a_user_whose_email_is_already_registered_is_not_overwritten_and_their_data_waits_for_an_explicit_map(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    existing = ObjectId()
    legacy.client[legacy.target]["users"].insert_one(Legacy.user(existing, "Asha (new account)", "asha@example.com"))

    report = legacy.run(execute=True)
    assert report["users"]["quarantine_reasons"] == {"email_already_registered_with_different_id": 1}
    assert legacy.client[legacy.target]["users"].find_one({"email": "asha@example.com"})["_id"] == existing
    assert report["transactions"]["quarantined"] == 7 and report["transactions"]["copied"] == 0     # not guessed

    again = legacy.run(execute=True, user_map={str(legacy.user_a): str(existing)})                  # explicit decision
    assert again["transactions"]["copied"] == 7 and legacy.tx.count_documents({"userId": str(existing)}) == 7


def test_rows_without_an_owner_can_be_assigned_explicitly_by_email_but_unknown_owners_never_are(legacy):
    raw = legacy.client[legacy.py]["transactions"]
    base = {"date": "2026-10-01", "currency": "INR", "direction": "debit", "merchant": "X",
            "category": "Food & Dining", "description": "d", "source": "s"}
    raw.insert_many([{**base, "hash": "h-unowned", "amount": -5.0},
                     {**base, "hash": "h-ghost", "amount": -6.0, "user_id": "someone-else"}])

    report = legacy.run(execute=True, assign_unowned_to="BALA@example.com")

    assert report["transactions"]["copied"] == 1 and report["transactions"]["quarantine_reasons"] == {"owner_not_found_in_target": 1}
    assert legacy.tx.find_one({"sourceHash": "h-unowned"})["userId"] == str(legacy.user_b)


def test_an_unresolvable_assignment_target_aborts_before_anything_is_written(legacy):
    with pytest.raises(MigrationError, match="not a known user"):
        legacy.run(execute=True, assign_unowned_to="nobody@example.com")
    assert legacy.tx.count_documents({}) == 0


def test_execute_refuses_to_start_when_the_target_already_has_duplicates_that_block_the_unique_index(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    legacy.tx.insert_many([{"userId": "u", "sourceHash": "dup"}, {"userId": "u", "sourceHash": "dup"}])

    with pytest.raises(MigrationError, match="index problems"):
        legacy.run(execute=True)
    assert legacy.tx.count_documents({}) == 2                                                    # nothing was added


# ------------------------------------------------------------------ rollback
def test_rollback_removes_only_what_the_run_created(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    legacy.client[legacy.py]["transactions"].insert_one({"hash": "orphan", "date": "2026-10-01", "amount": -1.0,
        "currency": "INR", "direction": "debit", "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"})
    report = legacy.run(execute=True)
    # activity that happened in the new app AFTER the migration
    legacy.tx.insert_one({"userId": str(legacy.user_a), "sourceHash": "new-app-row", "jobId": "job-1"})
    before_sources = legacy.snapshot(legacy.java, legacy.py)

    plan = rollback(legacy.client, target_db=legacy.target, run_id=report["run_id"], execute=False)
    assert plan["transactions"] == 7 and plan["quarantine"] == 1
    assert plan["users_kept"] == [str(legacy.user_a)] and plan["users_removable"] == [str(legacy.user_b)]
    assert legacy.tx.count_documents({}) == 8                                                   # dry-run removed nothing

    rollback(legacy.client, target_db=legacy.target, run_id=report["run_id"], execute=True)

    assert [r["sourceHash"] for r in legacy.tx.find({})] == ["new-app-row"]
    users = legacy.client[legacy.target]["users"]
    assert users.find_one({"_id": legacy.user_a}) is not None and users.find_one({"_id": legacy.user_b}) is None
    assert legacy.client[legacy.target][QUARANTINE].count_documents({}) == 0
    assert legacy.snapshot(legacy.java, legacy.py) == before_sources


# ------------------------------------------------------------------ the point of keeping the hash
def test_after_migration_reprocessing_the_same_statement_is_recognised_as_duplicates(legacy, tmp_path, make_config, clock):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    legacy.run(execute=True)
    target = legacy.client[legacy.target]
    doc_id, file_id = put_document(target, str(legacy.user_a), SAMPLE.read_bytes())
    job_id = ObjectId()
    target["processing_jobs"].insert_one({
        "_id": job_id, "userId": str(legacy.user_a), "documentId": str(doc_id), "gridFsFileId": str(file_id),
        "status": "PENDING", "attempts": 0, "maxAttempts": 3, "remainingAttempts": 3, "availableAt": clock(),
        "createdAt": clock()})

    JobWorker(make_config(), target, clock=clock).process_one()

    job = target["processing_jobs"].find_one({"_id": job_id})
    assert job["status"] == "COMPLETED" and (job["extractedCount"], job["duplicateCount"]) == (0, 7)
    assert legacy.tx.count_documents({}) == 7                                                   # no second copy


def test_the_legacy_global_hash_index_already_collapsed_identical_rows_of_two_users(legacy):
    """Documented, unrepairable legacy limitation: user B's identical copy was never stored."""
    txns = legacy.statement()
    legacy.save_legacy(txns, str(legacy.user_a))
    legacy.save_legacy(txns, str(legacy.user_b))
    assert legacy.client[legacy.py]["transactions"].count_documents({}) == 7
    assert {d["user_id"] for d in legacy.client[legacy.py]["transactions"].find({})} == {str(legacy.user_b)}


# ------------------------------------------------------------------ CLI
def test_cli_prints_a_dry_run_report_then_executes_and_writes_the_report_file(legacy, tmp_path, monkeypatch, capsys):
    import os
    monkeypatch.setenv("MONGODB_URI", os.environ["TEST_MONGODB_URI"])
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    args = ["--legacy-java-db", legacy.java, "--legacy-python-db", legacy.py, "--target-db", legacy.target]

    assert migrate_main(args) == 0
    assert "dry-run" in capsys.readouterr().out and legacy.tx.count_documents({}) == 0

    report_file = tmp_path / "report.json"
    assert migrate_main(args + ["--execute", "--report", str(report_file)]) == 0
    saved = json.loads(report_file.read_text())
    assert saved["ok"] is True and legacy.tx.count_documents({}) == 7


# ------------------------------------------------------------------ the plan must predict reality
def test_dry_run_predicts_conflicts_and_quarantines_exactly_like_execute(legacy):
    legacy.save_legacy(legacy.statement(), str(legacy.user_a))
    legacy.client[legacy.py]["transactions"].insert_one(
        {"hash": "orphan", "date": "2026-10-01", "amount": -1.0, "currency": "INR", "direction": "debit",
         "merchant": "X", "category": "Food & Dining", "description": "d", "source": "s"})
    legacy.client[legacy.target]["users"].insert_one(Legacy.user(ObjectId(), "Asha (new)", "asha@example.com"))

    plan = legacy.run(execute=False)
    actual = legacy.run(execute=True)

    for section in ("users", "transactions"):
        for key in ("source", "copied", "already_present", "duplicates", "quarantined", "quarantine_reasons", "balanced"):
            assert plan[section][key] == actual[section][key], f"{section}.{key}: plan {plan[section][key]} != actual {actual[section][key]}"


def test_the_accounting_check_can_actually_fail():
    from migration.migrate import _close
    unbalanced = {"source": 5, "copied": 2, "already_present": 1, "duplicates": 0, "quarantined": 1}
    _close(unbalanced)
    assert unbalanced["balanced"] is False
    balanced = {"source": 5, "copied": 2, "already_present": 1, "duplicates": 1, "quarantined": 1}
    _close(balanced)
    assert balanced["balanced"] is True
