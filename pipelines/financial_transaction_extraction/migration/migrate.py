"""Copy-only migration of legacy data into the new `finpulse` database.

    # 1. read-only plan (default): changes nothing anywhere
    python -m migration.migrate --legacy-java-db expensetrackerdb --legacy-python-db finplus --target-db finpulse
    # 2. perform it
    python -m migration.migrate ... --execute --report migration-report.json
    # 3. undo a run (touches only the target, only rows that run created)
    python -m migration.migrate --target-db finpulse --rollback <runId> --execute

What it does
  users         expensetrackerdb.users -> finpulse.users, SAME _id (so existing JWTs and ownership stay valid)
  transactions  finplus.transactions   -> finpulse.transactions in the canonical model, legacy `hash` becomes
                `sourceHash` (so re-uploading an old statement dedupes against migrated rows), legacy `user_id`
                becomes `userId`.
What it never does
  * write to, modify or delete anything in a source database (it only reads);
  * overwrite an existing target row;
  * drop a row: anything it cannot place safely goes to `migration_quarantine` with the original document
    verbatim and the reason. source == copied + already_present + duplicates + quarantined is checked and reported.
Not migrated (left untouched in place): finplus.raw_inputs, finplus.reports and any other collection.
Every created row carries a run marker (transactions: jobId `migration-<runId>` and legacy.runId; users:
migration.runId) so a run can be rolled back precisely.

Legacy limitation that cannot be repaired: the old unique index on `hash` alone meant two users saving the same
transaction collapsed into ONE legacy row; the second user's copy no longer exists in the source.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import sys
from datetime import datetime, timezone
from typing import Optional

from bson import ObjectId
from pymongo import MongoClient
from pymongo.errors import DuplicateKeyError

from transaction_pipeline import Transaction
from worker.errors import MappingError
from worker.transaction_mapper import map_transaction

from .indexes import apply_indexes, inspect_indexes

QUARANTINE = "migration_quarantine"
TXN_FIELDS = {f.name for f in dataclasses.fields(Transaction)}
HEX24 = re.compile(r"^[0-9a-fA-F]{24}$")


class MigrationError(RuntimeError):
    pass


def _normalize_email(value) -> str:
    return str(value or "").strip().lower()


def _aware(value, fallback):
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    return fallback


def legacy_to_canonical(doc: dict, *, owner: str, run_id: str, legacy_db: str, now: datetime) -> dict:
    """One legacy `finplus.transactions` document -> the canonical transactions document.
    Raises TypeError / MappingError / ValueError when the row cannot be represented (the caller quarantines it)."""
    txn = Transaction(**{k: v for k, v in doc.items() if k in TXN_FIELDS})
    created = _aware(doc.get("saved_at"), now)
    mapped = map_transaction(txn, user_id=owner, document_id="", job_id=f"migration-{run_id}", now=created)
    mapped["documentId"] = None                                  # legacy rows were not uploaded documents
    mapped["legacy"] = {"db": legacy_db, "id": str(doc["_id"]), "runId": run_id}
    return mapped


def _empty_section() -> dict:
    return {"source": 0, "copied": 0, "already_present": 0, "duplicates": 0, "quarantined": 0,
            "quarantine_reasons": {}, "balanced": None}


def _close(section: dict) -> None:
    accounted = section["copied"] + section["already_present"] + section["duplicates"] + section["quarantined"]
    section["balanced"] = accounted == section["source"]


def migrate(client, *, legacy_java_db: str = "expensetrackerdb", legacy_python_db: str = "finplus",
            target_db: str = "finpulse", execute: bool = False, user_map: Optional[dict] = None,
            assign_unowned_to: Optional[str] = None, run_id: Optional[str] = None,
            now: Optional[datetime] = None) -> dict:
    if target_db in (legacy_java_db, legacy_python_db):
        raise MigrationError("the target database must differ from the legacy databases (copy-only migration)")
    now = now or datetime.now(timezone.utc)
    run_id = run_id or now.strftime("%Y%m%dT%H%M%SZ")
    user_map = {str(k): str(v) for k, v in (user_map or {}).items()}
    marker = f"migration-{run_id}"
    target = client[target_db]
    report = {"run_id": run_id, "mode": "execute" if execute else "dry-run", "target_db": target_db,
              "legacy_java_db": legacy_java_db, "legacy_python_db": legacy_python_db,
              "users": _empty_section(), "transactions": _empty_section(), "notes": []}

    def quarantine(section: dict, legacy_db: str, collection: str, reason: str, doc: dict) -> None:
        section["quarantined"] += 1
        section["quarantine_reasons"][reason] = section["quarantine_reasons"].get(reason, 0) + 1
        if execute:
            target[QUARANTINE].update_one(
                {"legacyDb": legacy_db, "legacyCollection": collection, "legacyId": str(doc.get("_id"))},
                {"$setOnInsert": {"runId": run_id, "reason": reason, "document": doc, "createdAt": now}}, upsert=True)

    # ------------------------------------------------------------------ preconditions: indexes first
    if execute:
        index_reports = inspect_indexes(target)
        blocked = {c: r for c, r in index_reports.items()
                   if c in ("users", "transactions") and (r.duplicates_blocking or r.conflicts)}
        if blocked:
            raise MigrationError("fix these target index problems first (python -m migration.indexes): "
                                 + "; ".join(f"{c}: {r.conflicts or list(r.duplicates_blocking)}" for c, r in blocked.items()))
        apply_indexes(target, index_reports)
        target[QUARANTINE].create_index([("legacyDb", 1), ("legacyCollection", 1), ("legacyId", 1)],
                                        unique=True, name="legacy_identity")

    # ------------------------------------------------------------------ users
    known_ids = {str(u["_id"]) for u in target["users"].find({}, {"_id": 1})}
    section = report["users"]
    for doc in client[legacy_java_db]["users"].find({}).sort("_id", 1):
        section["source"] += 1
        email = _normalize_email(doc.get("email"))
        if not doc.get("_id") or not email or not doc.get("passwordHash"):
            quarantine(section, legacy_java_db, "users", "invalid_user_document", doc)
            continue
        same_id = target["users"].find_one({"_id": doc["_id"]})
        if same_id is not None:
            if _normalize_email(same_id.get("email")) == email:
                section["already_present"] += 1
            else:
                quarantine(section, legacy_java_db, "users", "id_collision_different_email", doc)
            continue
        if target["users"].find_one({"email": email}) is not None:
            quarantine(section, legacy_java_db, "users", "email_already_registered_with_different_id", doc)
            continue
        copy = {**doc, "email": email, "migration": {"runId": run_id, "sourceDb": legacy_java_db}}
        if execute:
            try:
                target["users"].insert_one(copy)
            except DuplicateKeyError:
                quarantine(section, legacy_java_db, "users", "email_already_registered_with_different_id", doc)
                continue
        section["copied"] += 1
        known_ids.add(str(doc["_id"]))
    _close(section)

    unowned_owner = None
    if assign_unowned_to:
        unowned_owner = _resolve_account(target, known_ids, assign_unowned_to)
        if unowned_owner is None:
            raise MigrationError(f"--assign-unowned-to {assign_unowned_to!r} is not a known user id or e-mail")

    # ------------------------------------------------------------------ transactions
    section = report["transactions"]
    seen_in_run = set()
    for doc in client[legacy_python_db]["transactions"].find({}).sort("_id", 1):
        section["source"] += 1
        legacy_id = str(doc["_id"])
        raw_owner = str(doc.get("user_id") or "").strip()
        if not raw_owner:
            owner = unowned_owner
            reason_if_none = "no_user_id"
        else:
            candidate = user_map.get(raw_owner, raw_owner)
            owner = candidate if candidate in known_ids else None
            reason_if_none = "owner_not_found_in_target"
        if owner is None:
            quarantine(section, legacy_python_db, "transactions", reason_if_none, doc)
            continue
        source_hash = doc.get("hash")
        if not source_hash:
            quarantine(section, legacy_python_db, "transactions", "no_hash", doc)
            continue
        try:
            mapped = legacy_to_canonical(doc, owner=owner, run_id=run_id, legacy_db=legacy_python_db, now=now)
        except (TypeError, MappingError, ValueError) as exc:
            quarantine(section, legacy_python_db, "transactions", f"unmappable: {type(exc).__name__}", doc)
            continue

        key = (owner, source_hash)
        existing = target["transactions"].find_one({"userId": owner, "sourceHash": source_hash}, {"legacy": 1})
        if existing is not None:
            if (existing.get("legacy") or {}).get("id") == legacy_id and (existing["legacy"].get("db") == legacy_python_db):
                section["already_present"] += 1          # this exact row was migrated by an earlier run
            else:
                section["duplicates"] += 1               # the user already has this transaction (new app)
                quarantine_copy = dict(doc)
                if execute:
                    target[QUARANTINE].update_one(
                        {"legacyDb": legacy_python_db, "legacyCollection": "transactions", "legacyId": legacy_id},
                        {"$setOnInsert": {"runId": run_id, "reason": "duplicate_of_existing_transaction",
                                          "document": quarantine_copy, "createdAt": now}}, upsert=True)
            continue
        if key in seen_in_run:
            section["duplicates"] += 1
            continue
        if execute:
            try:
                target["transactions"].insert_one(mapped)
            except DuplicateKeyError:
                section["duplicates"] += 1
                continue
        seen_in_run.add(key)
        section["copied"] += 1
    _close(section)

    if execute:
        section["verified_in_target"] = target["transactions"].count_documents({"legacy.runId": run_id})
        report["users"]["verified_in_target"] = target["users"].count_documents({"migration.runId": run_id})

    # ------------------------------------------------------------------ what was left alone
    report["left_in_place"] = {}
    for name in (legacy_java_db, legacy_python_db):
        db = client[name]
        handled = {"users"} if name == legacy_java_db else {"transactions"}
        report["left_in_place"][name] = {c: db[c].count_documents({}) for c in sorted(db.list_collection_names())
                                         if c not in handled}
    report["ok"] = bool(report["users"]["balanced"] and report["transactions"]["balanced"])
    return report


def _resolve_account(target, known_ids: set, text: str) -> Optional[str]:
    text = text.strip()
    if HEX24.match(text) and text in known_ids:
        return text
    user = target["users"].find_one({"email": _normalize_email(text)}, {"_id": 1})
    return str(user["_id"]) if user else None


def rollback(client, *, target_db: str, run_id: str, execute: bool = False) -> dict:
    """Removes exactly what a migration run created in the target database. Sources are never touched."""
    target = client[target_db]
    marker = f"migration-{run_id}"
    txn_filter = {"jobId": marker, "legacy.runId": run_id}
    result = {"run_id": run_id, "mode": "execute" if execute else "dry-run",
              "transactions": target["transactions"].count_documents(txn_filter),
              "quarantine": target[QUARANTINE].count_documents({"runId": run_id}),
              "users_removable": [], "users_kept": []}
    for user in target["users"].find({"migration.runId": run_id}, {"_id": 1}):
        uid = str(user["_id"])
        other_data = (target["transactions"].count_documents({"userId": uid, "jobId": {"$ne": marker}})
                      + target["uploaded_documents"].count_documents({"userId": uid})
                      + target["processing_jobs"].count_documents({"userId": uid}))
        (result["users_kept"] if other_data else result["users_removable"]).append(uid)
    if execute:
        target["transactions"].delete_many(txn_filter)
        target[QUARANTINE].delete_many({"runId": run_id})
        target["users"].delete_many({"_id": {"$in": [ObjectId(u) if HEX24.match(u) else u for u in result["users_removable"]]}})
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--legacy-java-db", default="expensetrackerdb")
    parser.add_argument("--legacy-python-db", default="finplus")
    parser.add_argument("--target-db", default=os.environ.get("MONGODB_DATABASE", "finpulse"))
    parser.add_argument("--execute", action="store_true", help="actually write (default is a read-only plan)")
    parser.add_argument("--user-map", help="JSON file {legacy user_id: new user id}")
    parser.add_argument("--assign-unowned-to", help="user id or e-mail that receives legacy rows with NO user_id")
    parser.add_argument("--rollback", metavar="RUN_ID")
    parser.add_argument("--report", help="write the JSON report here")
    args = parser.parse_args(argv)
    uri = os.environ.get("MONGODB_URI")
    if not uri:
        print("MONGODB_URI is not set", file=sys.stderr)
        return 2
    client = MongoClient(uri, serverSelectionTimeoutMS=10000, tz_aware=True)
    try:
        if args.rollback:
            report = rollback(client, target_db=args.target_db, run_id=args.rollback, execute=args.execute)
        else:
            user_map = json.load(open(args.user_map, encoding="utf-8")) if args.user_map else None
            report = migrate(client, legacy_java_db=args.legacy_java_db, legacy_python_db=args.legacy_python_db,
                             target_db=args.target_db, execute=args.execute, user_map=user_map,
                             assign_unowned_to=args.assign_unowned_to)
    except MigrationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    text = json.dumps(report, indent=2, default=str)
    if args.report:
        open(args.report, "w", encoding="utf-8").write(text)
    print(text)
    if not args.execute:
        print("\n(dry-run: nothing was written. Re-run with --execute to apply.)")
    return 0 if report.get("ok", True) else 1


if __name__ == "__main__":
    raise SystemExit(main())
