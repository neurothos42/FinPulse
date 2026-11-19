"""Inspect and (carefully) create the indexes FinPulse needs.

    python -m migration.indexes --db finpulse                  # report only (default): changes nothing
    python -m migration.indexes --db finpulse --apply          # create MISSING indexes (never drops anything)
    python -m migration.indexes --db finpulse --drop-legacy-hash-index --confirm-drop-index finpulse

Rules:
  * Existing indexes are reviewed first. Matching is by key pattern + uniqueness, so an index with the same
    keys under another name counts as present.
  * A unique index is only created when the data has no duplicates for its key; otherwise the duplicates are
    listed and nothing is created for that collection.
  * An existing index that conflicts with a wanted one (same keys, different uniqueness) is reported, never
    dropped or replaced automatically.
  * The old global unique index on `hash` (written by the legacy pipeline) blocks two users from having the
    same transaction. It is only dropped when explicitly requested, the new unique (userId, sourceHash) index
    already exists, and the database name is typed back as confirmation.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

from pymongo import MongoClient
from pymongo.errors import OperationFailure

CONTRACT = Path(__file__).resolve().parents[1] / "contracts" / "indexes.json"
LEGACY_HASH_KEYS = [("hash", 1)]


def load_expected(path: Path = CONTRACT) -> dict[str, list[dict]]:
    return json.loads(path.read_text(encoding="utf-8"))["collections"]


def _keys(index_keys) -> list[tuple[str, int]]:
    items = index_keys.items() if hasattr(index_keys, "items") else index_keys
    return [(k, int(v)) for k, v in items]


@dataclass
class CollectionReport:
    collection: str
    present: list[str] = field(default_factory=list)
    equivalent_other_name: list[tuple[str, str]] = field(default_factory=list)   # (wanted name, existing name)
    missing: list[dict] = field(default_factory=list)
    conflicts: list[str] = field(default_factory=list)
    duplicates_blocking: dict[str, list] = field(default_factory=dict)           # wanted name -> sample duplicate keys
    legacy_hash_index: str | None = None
    unexpected: list[str] = field(default_factory=list)                          # informational only
    created: list[str] = field(default_factory=list)
    dropped: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not (self.missing or self.conflicts or self.duplicates_blocking or self.legacy_hash_index)


def find_duplicates(db, collection: str, keys: list[tuple[str, int]], limit: int = 10) -> list[dict]:
    """Groups of documents that would violate a unique index on `keys`."""
    fields = [k for k, _ in keys]
    pipeline = [
        {"$group": {"_id": {f: f"${f}" for f in fields}, "count": {"$sum": 1}}},
        {"$match": {"count": {"$gt": 1}}},
        {"$limit": limit},
    ]
    return [{"key": g["_id"], "count": g["count"]} for g in db[collection].aggregate(pipeline)]


def inspect_indexes(db, expected: dict[str, list[dict]] | None = None) -> dict[str, CollectionReport]:
    expected = expected or load_expected()
    existing_collections = set(db.list_collection_names())
    reports = {}
    for collection, wanted in expected.items():
        report = CollectionReport(collection)
        actual = db[collection].index_information() if collection in existing_collections else {}
        by_keys = {tuple(_keys(info["key"])): (name, info) for name, info in actual.items() if name != "_id_"}
        matched = set()
        for index in wanted:
            wanted_keys = tuple((k, v) for k, v in index["keys"])
            hit = by_keys.get(wanted_keys)
            if hit is None:
                report.missing.append(index)
                continue
            name, info = hit
            matched.add(name)
            if bool(info.get("unique", False)) != bool(index["unique"]):
                report.conflicts.append(
                    f"{collection}.{name}: keys {list(wanted_keys)} exist with unique={bool(info.get('unique', False))}, "
                    f"wanted unique={index['unique']}. Resolve by hand (never auto-dropped).")
            elif name == index["name"]:
                report.present.append(name)
            else:
                report.equivalent_other_name.append((index["name"], name))
        for name, info in actual.items():
            if name == "_id_" or name in matched:
                continue
            if _keys(info["key"]) == LEGACY_HASH_KEYS and info.get("unique"):
                report.legacy_hash_index = name
            else:
                report.unexpected.append(name)
        for index in report.missing:
            if index["unique"] and collection in existing_collections:
                dupes = find_duplicates(db, collection, [(k, v) for k, v in index["keys"]])
                if dupes:
                    report.duplicates_blocking[index["name"]] = dupes
        reports[collection] = report
    return reports


def apply_indexes(db, reports: dict[str, CollectionReport], drop_legacy_hash_index: bool = False,
                  confirm_drop_index: str | None = None) -> dict[str, CollectionReport]:
    """Creates missing indexes whose preconditions hold. Never drops anything unless the legacy-hash drop is
    explicitly requested and confirmed."""
    for collection, report in reports.items():
        for index in report.missing:
            if index["name"] in report.duplicates_blocking:
                continue                                          # data must be fixed first
            db[collection].create_index([(k, v) for k, v in index["keys"]], name=index["name"],
                                        unique=bool(index["unique"]))
            report.created.append(index["name"])

    transactions = reports.get("transactions")
    if drop_legacy_hash_index and transactions and transactions.legacy_hash_index:
        if confirm_drop_index != db.name:
            raise ValueError(f"to drop the legacy index, pass --confirm-drop-index {db.name}")
        keys = {tuple(_keys(i["key"])): i.get("unique") for i in db["transactions"].index_information().values()}
        if not keys.get((("userId", 1), ("sourceHash", 1))):
            raise ValueError("refusing to drop the legacy hash index: the unique (userId, sourceHash) index does "
                             "not exist yet (it must be created first, so duplicates stay impossible)")
        db["transactions"].drop_index(transactions.legacy_hash_index)
        transactions.dropped.append(transactions.legacy_hash_index)
    return reports


def format_report(db_name: str, reports: dict[str, CollectionReport]) -> str:
    lines = [f"database: {db_name}"]
    for collection, r in reports.items():
        status = "OK" if r.ok else "ATTENTION"
        lines.append(f"\n[{status}] {collection}")
        for name in r.present:
            lines.append(f"   present            {name}")
        for wanted, actual in r.equivalent_other_name:
            lines.append(f"   present (as '{actual}')  {wanted}")
        for index in r.missing:
            blocked = " (BLOCKED: duplicates exist)" if index["name"] in r.duplicates_blocking else ""
            lines.append(f"   MISSING            {index['name']}{blocked}")
        for name, dupes in r.duplicates_blocking.items():
            lines.append(f"   duplicates for {name}: {dupes[:3]}{' ...' if len(dupes) > 3 else ''}")
        for text in r.conflicts:
            lines.append(f"   CONFLICT           {text}")
        if r.legacy_hash_index:
            lines.append(f"   LEGACY             unique index '{r.legacy_hash_index}' on hash alone (blocks per-user duplicates)")
        for name in r.unexpected:
            lines.append(f"   extra (ignored)    {name}")
        for name in r.created:
            lines.append(f"   CREATED            {name}")
        for name in r.dropped:
            lines.append(f"   DROPPED            {name}")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", default=os.environ.get("MONGODB_DATABASE", "finpulse"))
    parser.add_argument("--apply", action="store_true", help="create missing indexes")
    parser.add_argument("--drop-legacy-hash-index", action="store_true")
    parser.add_argument("--confirm-drop-index", default=None, help="type the database name to confirm the drop")
    args = parser.parse_args(argv)
    uri = os.environ.get("MONGODB_URI")
    if not uri:
        print("MONGODB_URI is not set", file=sys.stderr)
        return 2
    db = MongoClient(uri, serverSelectionTimeoutMS=10000)[args.db]
    reports = inspect_indexes(db)
    try:
        if args.apply or args.drop_legacy_hash_index:
            apply_indexes(db, reports, args.drop_legacy_hash_index, args.confirm_drop_index)
    except (ValueError, OperationFailure) as exc:
        print(format_report(args.db, reports))
        print(f"\nERROR: {exc}", file=sys.stderr)
        return 1
    print(format_report(args.db, reports))
    return 0 if all(r.ok or r.created or r.dropped for r in reports.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
