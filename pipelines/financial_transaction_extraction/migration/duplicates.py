"""Find and (optionally) resolve duplicate documents that would block a unique index.

    python -m migration.duplicates --db finpulse --collection transactions            # report only
    python -m migration.duplicates --db finpulse --collection transactions --execute  # move extras aside

For each group of documents sharing the same key (transactions: userId + sourceHash; users: email) ONE is kept
(the oldest by createdAt, ties broken by _id) and every other document is first COPIED into
`<collection>_duplicates_removed` (with the id it was a duplicate of and a timestamp) and only then deleted from
the collection. Nothing is destroyed: the removed documents stay in that side collection until you delete it.
Take a backup first (scripts/backup_databases.py)."""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timezone

from pymongo import MongoClient

PRESETS = {"transactions": ["userId", "sourceHash"], "users": ["email"]}


def _keep_order(doc: dict):
    """Oldest createdAt first; documents without a usable createdAt sort last; ties broken by _id. Naive and
    timezone-aware datetimes are both accepted (legacy data may contain either)."""
    created = doc.get("createdAt")
    if isinstance(created, datetime):
        created = created if created.tzinfo else created.replace(tzinfo=timezone.utc)
        return (0, created, str(doc["_id"]))
    return (1, datetime.max.replace(tzinfo=timezone.utc), str(doc["_id"]))


def duplicate_groups(db, collection: str, fields: list[str]) -> list[list[dict]]:
    """Each group = all documents sharing the key, ordered keep-first (oldest createdAt, then _id)."""
    pipeline = [{"$group": {"_id": {f: f"${f}" for f in fields}, "ids": {"$push": "$_id"}, "n": {"$sum": 1}}},
                {"$match": {"n": {"$gt": 1}}}]
    groups = []
    for g in db[collection].aggregate(pipeline):
        docs = list(db[collection].find({"_id": {"$in": g["ids"]}}))
        docs.sort(key=_keep_order)
        groups.append(docs)
    return groups


def resolve(db, collection: str, fields: list[str], execute: bool = False, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    side = db[f"{collection}_duplicates_removed"]
    groups = duplicate_groups(db, collection, fields)
    result = {"collection": collection, "key": fields, "groups": len(groups), "kept": len(groups),
              "to_move": sum(len(g) - 1 for g in groups), "moved": 0, "mode": "execute" if execute else "dry-run"}
    if execute:
        for group in groups:
            keeper = group[0]
            for extra in group[1:]:
                side.insert_one({"originalId": extra["_id"], "duplicateOf": keeper["_id"], "removedAt": now,
                                 "document": extra})                    # copy first ...
                db[collection].delete_one({"_id": extra["_id"]})        # ... delete only after the copy exists
                result["moved"] += 1
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", default=os.environ.get("MONGODB_DATABASE", "finpulse"))
    parser.add_argument("--collection", choices=sorted(PRESETS), required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    uri = os.environ.get("MONGODB_URI")
    if not uri:
        print("MONGODB_URI is not set", file=sys.stderr)
        return 2
    db = MongoClient(uri, serverSelectionTimeoutMS=10000, tz_aware=True)[args.db]
    print(resolve(db, args.collection, PRESETS[args.collection], args.execute))
    if not args.execute:
        print("(dry-run: nothing changed. Re-run with --execute after taking a backup.)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
