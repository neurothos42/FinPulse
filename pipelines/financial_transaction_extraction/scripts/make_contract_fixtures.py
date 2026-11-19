"""Regenerates contracts/worker_transaction.json from the REAL worker mapper.
    python scripts/make_contract_fixtures.py

The Java test WorkerContractTest reads this file and deserialises it into the Transaction entity, so any
change to the worker's output that Java cannot read fails a test on the Java side too.
Documents are MongoDB extended JSON (relaxed): $oid, $numberDecimal, $date."""
from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

from bson import ObjectId, json_util

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from transaction_pipeline import Transaction  # noqa: E402
from worker.transaction_mapper import map_transaction  # noqa: E402

TARGET = ROOT / "contracts" / "worker_transaction.json"
MIGRATED_TARGET = ROOT / "contracts" / "migrated_transaction.json"
NOW = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)

# Fields only the legacy migration adds (traceability back to the old row). Java must simply ignore them.
MIGRATION_ONLY_FIELDS = ("legacy",)

# Fields the worker may add that the Java entity does not declare. Java must simply ignore them.
WORKER_ONLY_FIELDS = ("categorySource", "categoryConfidence", "ocrConfidence")


def build_documents() -> list[dict]:
    common = dict(currency="INR", merchant="Swiggy", description="UPI/DR/427619384729/SWIGGY", source="statement.pdf")
    full = Transaction(date="2026-10-03", amount=-450.0, direction="debit", category="Food & Dining",
                       ref_no="427619384729", balance=10000.5, hash="a" * 64, needs_review=False, **common)
    unknown = Transaction(date=None, amount=75.0, direction="unknown", category="Uncategorized",
                          ref_no=None, balance=None, hash="b" * 64, needs_review=True,
                          possible_duplicate_of="a" * 64, **{**common, "merchant": ""})
    documents = []
    for i, txn in enumerate((full, unknown)):
        doc = map_transaction(txn, user_id="contract-user", document_id="507f1f77bcf86cd799439012",
                              job_id="507f1f77bcf86cd799439013", now=NOW)
        doc["_id"] = ObjectId(f"507f1f77bcf86cd79943901{i}")
        if i == 0:                                    # extras added by the optional OCR gate / ML step
            doc.update(categorySource="ml:cat-test", categoryConfidence=0.91, ocrConfidence=88.5)
        documents.append(doc)
    return documents


def render() -> str:
    return json_util.dumps({"transactions": build_documents()}, json_options=json_util.RELAXED_JSON_OPTIONS,
                           indent=2, sort_keys=True) + "\n"


def build_migrated_document() -> dict:
    """A legacy finplus.transactions row converted by the REAL migration code path."""
    from migration.migrate import legacy_to_canonical
    legacy = {"_id": ObjectId("507f1f77bcf86cd799439021"), "date": "2026-10-03", "amount": -450.0, "currency": "INR",
              "direction": "debit", "merchant": "Swiggy", "category": "Food & Dining",
              "description": "UPI/DR/427619384729/SWIGGY", "source": "statement.pdf", "ref_no": "427619384729",
              "balance": 10000.5, "needs_review": False, "hash": "c" * 64, "user_id": "contract-user",
              "saved_at": datetime(2025, 12, 1, 8, 30, tzinfo=timezone.utc)}
    doc = legacy_to_canonical(legacy, owner="contract-user", run_id="20261114T100000Z", legacy_db="finplus", now=NOW)
    doc["_id"] = ObjectId("507f1f77bcf86cd799439020")
    return doc


def render_migrated() -> str:
    return json_util.dumps({"transactions": [build_migrated_document()]},
                           json_options=json_util.RELAXED_JSON_OPTIONS, indent=2, sort_keys=True) + "\n"


def main() -> int:
    TARGET.parent.mkdir(exist_ok=True)
    TARGET.write_text(render(), encoding="utf-8")
    MIGRATED_TARGET.write_text(render_migrated(), encoding="utf-8")
    print(f"wrote {TARGET}\nwrote {MIGRATED_TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
