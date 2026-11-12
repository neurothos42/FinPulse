"""MongoDB input/output for the FinPlus pipeline.

INPUT  : collection `raw_inputs`  -> documents the pipeline should process
OUTPUT : collection `transactions` -> accepted transactions (unique on `hash`)
         collection `reports`      -> duplicates / rejected rows / errors per run

Input document shapes (all optional fields besides `type`):
  {"type": "sms",  "text": "Rs 450 debited to SWIGGY ...", "user_id": "u1"}
  {"type": "text", "text": "<pasted SMS log or statement text>", "source": "log1"}
  {"type": "file", "filename": "statement.pdf", "data": <bytes>, "pdf_password": "..."}
Processed documents get status="processed" (or "error") so they are not read twice.

Connection string comes from --mongo-uri or the MONGODB_URI environment variable.
"""
from __future__ import annotations

import os
import tempfile
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional


class MongoStore:
    def __init__(self, uri: Optional[str] = None, db: str = "finplus", client=None):
        if client is None:
            uri = uri or os.environ.get("MONGODB_URI")
            if not uri:
                raise ValueError("MongoDB URI missing: use --mongo-uri or set MONGODB_URI")
            from pymongo import MongoClient          # imported lazily: only needed with Mongo
            client = MongoClient(uri, serverSelectionTimeoutMS=8000)
            client.admin.command("ping")             # fail fast with a clear error
        self.db = client[db]
        self.raw = self.db["raw_inputs"]
        self.txns = self.db["transactions"]
        self.reports = self.db["reports"]
        self.txns.create_index("hash", unique=True)
        self.raw.create_index("status")

    # ---------------------------------------------------------------- INPUT
    def pending_inputs(self, limit: int = 0) -> list[dict]:
        """Documents not yet processed (status missing or 'pending')."""
        cur = self.raw.find({"status": {"$in": [None, "pending"]}})
        return list(cur.limit(limit) if limit else cur)

    def materialize_file(self, doc: dict, tmpdir: str) -> Path:
        """Write a binary `file` document to disk so the existing pipeline can read it."""
        name = Path(doc.get("filename") or f"{doc['_id']}.bin").name   # strip any path parts
        path = Path(tmpdir) / f"{doc['_id']}_{name}"
        path.write_bytes(bytes(doc["data"]))
        return path

    def mark(self, doc_id, status: str, note: str = "") -> None:
        self.raw.update_one({"_id": doc_id}, {"$set": {
            "status": status, "note": note, "processed_at": datetime.now(timezone.utc)}})

    # --------------------------------------------------------- DE-DUP MEMORY
    def load_store(self, user_id: Optional[str] = None) -> list[dict]:
        """Previously accepted transactions, used by the pipeline's duplicate checks."""
        q = {"user_id": user_id} if user_id else {}
        return [{k: v for k, v in d.items() if k != "_id"} for d in self.txns.find(q)]

    # --------------------------------------------------------------- OUTPUT
    def save_transactions(self, txns: Iterable, user_id: Optional[str] = None) -> int:
        from pymongo import UpdateOne
        ops = []
        for t in txns:
            d = asdict(t) if is_dataclass(t) else dict(t)
            if user_id:
                d["user_id"] = user_id
            d["saved_at"] = datetime.now(timezone.utc)
            ops.append(UpdateOne({"hash": d["hash"]}, {"$set": d}, upsert=True))
        if not ops:
            return 0
        res = self.txns.bulk_write(ops, ordered=False)
        return res.upserted_count + res.modified_count

    def save_report(self, duplicates: list, discarded: list, errors: list) -> None:
        self.reports.insert_one({
            "created_at": datetime.now(timezone.utc),
            "duplicates": duplicates, "discarded": discarded, "errors": errors})


def temp_dir():
    return tempfile.TemporaryDirectory(prefix="finplus_")
