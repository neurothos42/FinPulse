import pytest

from migration.indexes import apply_indexes, find_duplicates, inspect_indexes, load_expected


def names(db, collection):
    return set(db[collection].index_information()) - {"_id_"}


def test_empty_database_reports_every_index_as_missing_and_creating_them_is_idempotent(db):
    reports = inspect_indexes(db)
    expected = load_expected()
    for collection, wanted in expected.items():
        assert {i["name"] for i in reports[collection].missing} == {i["name"] for i in wanted}

    apply_indexes(db, reports)
    again = inspect_indexes(db)
    assert all(r.ok for r in again.values())
    assert names(db, "transactions") >= {"userId_sourceHash_unique", "userId_date", "userId_category_date",
                                         "userId_direction_date", "userId_needsReview"}
    assert "userId_jobId" in names(db, "reports") and "leaseUntil_idx" in names(db, "processing_jobs")
    apply_indexes(db, again)                                       # second run changes nothing
    assert all(not r.created for r in again.values())


def test_an_equivalent_index_under_another_name_is_accepted_and_not_duplicated(db):
    db["users"].create_index([("email", 1)], unique=True, name="email_1")
    reports = inspect_indexes(db)

    assert reports["users"].equivalent_other_name == [("email", "email_1")] and reports["users"].ok
    apply_indexes(db, reports)
    assert names(db, "users") == {"email_1"}


def test_unique_index_is_not_created_while_duplicates_exist_and_they_are_listed(db):
    db["transactions"].insert_many([
        {"userId": "u1", "sourceHash": "same"}, {"userId": "u1", "sourceHash": "same"},
        {"userId": "u2", "sourceHash": "same"}, {"userId": "u1", "sourceHash": "other"}])
    reports = inspect_indexes(db)

    blocking = reports["transactions"].duplicates_blocking["userId_sourceHash_unique"]
    assert blocking == [{"key": {"userId": "u1", "sourceHash": "same"}, "count": 2}]
    assert not reports["transactions"].ok

    apply_indexes(db, reports)
    assert "userId_sourceHash_unique" not in names(db, "transactions")        # refused...
    assert "userId_date" in names(db, "transactions")                          # ...but harmless ones are created
    assert db["transactions"].count_documents({}) == 4                         # nothing deleted


def test_a_conflicting_existing_index_is_reported_and_left_alone(db):
    db["transactions"].create_index([("userId", 1), ("sourceHash", 1)], name="weak", unique=False)
    reports = inspect_indexes(db)

    assert any("unique=False" in c for c in reports["transactions"].conflicts)
    apply_indexes(db, reports)
    assert names(db, "transactions") >= {"weak"} and "userId_sourceHash_unique" not in names(db, "transactions")


def test_legacy_global_hash_index_is_detected_but_not_dropped_by_default(db):
    db["transactions"].create_index("hash", unique=True)
    reports = inspect_indexes(db)
    assert reports["transactions"].legacy_hash_index == "hash_1"

    apply_indexes(db, reports)
    assert "hash_1" in names(db, "transactions")                                # still there: never dropped silently
    assert "userId_sourceHash_unique" in names(db, "transactions")              # new index added alongside


def test_legacy_hash_index_drop_needs_the_flag_the_confirmation_and_the_replacement_index(db):
    db["transactions"].create_index("hash", unique=True)
    reports = inspect_indexes(db)

    with pytest.raises(ValueError, match="confirm-drop-index"):
        apply_indexes(db, reports, drop_legacy_hash_index=True)
    assert "hash_1" in names(db, "transactions")

    # replacement index blocked by duplicates -> the drop must be refused (legacy index is the only protection left)
    db2 = db.client[db.name + "_b"]
    try:
        db2["transactions"].create_index("hash", unique=True)
        db2["transactions"].insert_many([{"userId": "u", "sourceHash": "x", "hash": "1"}, {"userId": "u", "sourceHash": "x", "hash": "2"}])
        r2 = inspect_indexes(db2)
        with pytest.raises(ValueError, match="does not exist yet"):
            apply_indexes(db2, r2, drop_legacy_hash_index=True, confirm_drop_index=db2.name)
        assert "hash_1" in names(db2, "transactions")
    finally:
        db.client.drop_database(db2.name)

    ok = inspect_indexes(db)
    apply_indexes(db, ok, drop_legacy_hash_index=True, confirm_drop_index=db.name)
    assert ok["transactions"].dropped == ["hash_1"] and "hash_1" not in names(db, "transactions")
    assert "userId_sourceHash_unique" in names(db, "transactions")


def test_find_duplicates_only_reports_real_duplicates(db):
    db["users"].insert_many([{"email": "a@x.com"}, {"email": "b@x.com"}, {"email": "a@x.com"}])
    assert find_duplicates(db, "users", [("email", 1)]) == [{"key": {"email": "a@x.com"}, "count": 2}]
    assert find_duplicates(db, "transactions", [("userId", 1)]) == []
