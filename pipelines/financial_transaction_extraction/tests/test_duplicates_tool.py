from datetime import datetime, timedelta, timezone

from migration.duplicates import duplicate_groups, resolve
from migration.indexes import apply_indexes, inspect_indexes

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def seed(db):
    db["transactions"].insert_many([
        {"userId": "u1", "sourceHash": "h", "createdAt": T0 + timedelta(days=2), "merchant": "newest"},
        {"userId": "u1", "sourceHash": "h", "createdAt": T0, "merchant": "oldest"},
        {"userId": "u1", "sourceHash": "h", "createdAt": T0 + timedelta(days=1), "merchant": "middle"},
        {"userId": "u2", "sourceHash": "h", "createdAt": T0, "merchant": "other user, not a duplicate"},
        {"userId": "u1", "sourceHash": "solo", "createdAt": T0, "merchant": "unique row"},
    ])


def test_groups_list_only_real_duplicates_oldest_first(db):
    seed(db)
    (group,) = duplicate_groups(db, "transactions", ["userId", "sourceHash"])
    assert [d["merchant"] for d in group] == ["oldest", "middle", "newest"]


def test_dry_run_reports_and_changes_nothing(db):
    seed(db)
    result = resolve(db, "transactions", ["userId", "sourceHash"], execute=False)

    assert (result["groups"], result["to_move"], result["moved"], result["mode"]) == (1, 2, 0, "dry-run")
    assert db["transactions"].count_documents({}) == 5 and "transactions_duplicates_removed" not in db.list_collection_names()


def test_execute_keeps_the_oldest_and_moves_the_rest_aside_without_losing_them(db):
    seed(db)
    result = resolve(db, "transactions", ["userId", "sourceHash"], execute=True, now=T0)

    assert result["moved"] == 2
    kept = list(db["transactions"].find({"userId": "u1", "sourceHash": "h"}))
    assert [d["merchant"] for d in kept] == ["oldest"]
    assert db["transactions"].count_documents({}) == 3                     # keeper + other user + unique row
    moved = list(db["transactions_duplicates_removed"].find({}))
    assert sorted(m["document"]["merchant"] for m in moved) == ["middle", "newest"]
    assert all(m["duplicateOf"] == kept[0]["_id"] and m["removedAt"] == T0 for m in moved)


def test_after_resolution_the_unique_index_can_be_created_and_a_second_run_is_a_no_op(db):
    seed(db)
    assert not inspect_indexes(db)["transactions"].ok
    resolve(db, "transactions", ["userId", "sourceHash"], execute=True)

    reports = inspect_indexes(db)
    assert not reports["transactions"].duplicates_blocking
    apply_indexes(db, reports)
    assert db["transactions"].index_information()["userId_sourceHash_unique"]["unique"] is True
    assert resolve(db, "transactions", ["userId", "sourceHash"], execute=True)["moved"] == 0


def test_mixed_naive_aware_and_missing_timestamps_are_ordered_without_error(db):
    db["transactions"].insert_many([
        {"userId": "u", "sourceHash": "h", "merchant": "no timestamp"},
        {"userId": "u", "sourceHash": "h", "createdAt": datetime(2026, 1, 5), "merchant": "naive"},          # naive
        {"userId": "u", "sourceHash": "h", "createdAt": datetime(2026, 1, 3, tzinfo=timezone.utc), "merchant": "aware"},
    ])
    (group,) = duplicate_groups(db, "transactions", ["userId", "sourceHash"])
    assert [d["merchant"] for d in group] == ["aware", "naive", "no timestamp"]
