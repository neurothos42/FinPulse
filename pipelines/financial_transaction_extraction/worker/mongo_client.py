"""MongoDB connection and index management for the worker."""
from __future__ import annotations

import logging

from pymongo import ASCENDING, DESCENDING, MongoClient
from pymongo.database import Database

from .config import WorkerConfig
from .logging_utils import log

logger = logging.getLogger(__name__)

# Index names and options MUST match the Java entity declarations (Phase 5): MongoDB rejects an index
# whose keys match an existing one but whose name/options differ.
TRANSACTION_INDEXES = [
    ("userId_sourceHash_unique", [("userId", ASCENDING), ("sourceHash", ASCENDING)], {"unique": True}),
    ("userId_date", [("userId", ASCENDING), ("date", DESCENDING)], {}),
    ("userId_category_date", [("userId", ASCENDING), ("category", ASCENDING), ("date", DESCENDING)], {}),
    ("userId_direction_date", [("userId", ASCENDING), ("direction", ASCENDING), ("date", DESCENDING)], {}),
    ("userId_needsReview", [("userId", ASCENDING), ("needsReview", ASCENDING)], {}),
]


REPORT_INDEXES = [
    ("userId_jobId", [("userId", ASCENDING), ("jobId", ASCENDING)], {"unique": True}),
]


def create_client(config: WorkerConfig) -> MongoClient:
    # tz_aware: all timestamps are timezone-aware UTC datetimes (BSON dates are always UTC).
    return MongoClient(
        config.mongodb_uri,
        tz_aware=True,
        serverSelectionTimeoutMS=10_000,
        retryWrites=True,
        appname="finpulse-worker",
    )


def get_database(client: MongoClient, config: WorkerConfig) -> Database:
    return client[config.mongodb_database]


def ensure_indexes(db: Database) -> None:
    """Idempotent. The worker manages transactions and reports; the Java service owns the other collections."""
    for name, keys, options in TRANSACTION_INDEXES:
        db["transactions"].create_index(keys, name=name, **options)
    for name, keys, options in REPORT_INDEXES:
        db["reports"].create_index(keys, name=name, **options)
    log(logger, logging.INFO, "indexes_ensured", collections="transactions,reports",
        count=len(TRANSACTION_INDEXES) + len(REPORT_INDEXES))
