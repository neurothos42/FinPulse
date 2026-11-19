"""Proves the worker runs with ONLY the privileges in contracts/mongodb_roles.json, and is denied everything else.

Needs a MongoDB with authentication enabled and an admin user, e.g.
    docker run -d -p 27019:27017 -e MONGO_INITDB_ROOT_USERNAME=admin -e MONGO_INITDB_ROOT_PASSWORD=adminpw mongo:7
    $env:TEST_MONGODB_AUTH_URI = "mongodb://admin:adminpw@localhost:27019/?authSource=admin"
Uses a random throw-away database; the role and user it creates are removed afterwards."""
import json
import os
import uuid
from pathlib import Path
from urllib.parse import urlsplit

import pytest
from bson import ObjectId
from pymongo import MongoClient
from pymongo.errors import OperationFailure

from conftest import assert_safe_test_uri
from worker.config import WorkerConfig
from worker.job_worker import JobWorker
from worker.mongo_client import ensure_indexes
from worker_helpers import START, insert_job, put_document

ROLES = json.loads((Path(__file__).resolve().parents[1] / "contracts" / "mongodb_roles.json").read_text())["roles"]
SAMPLE = Path(__file__).resolve().parents[1] / "samples" / "statement.pdf"
URI = os.environ.get("TEST_MONGODB_AUTH_URI")

pytestmark = pytest.mark.skipif(not URI, reason="needs an auth-enabled MongoDB: set TEST_MONGODB_AUTH_URI (admin credentials)")


def privileges(role: dict, database: str) -> list[dict]:
    return [{"resource": {"db": database, "collection": c}, "actions": p["actions"]}
            for p in role["privileges"] for c in p["collections"]]


@pytest.fixture
def secured():
    assert_safe_test_uri(URI)
    admin = MongoClient(URI, tz_aware=True)
    database = f"finpulse_it_{uuid.uuid4().hex}"
    suffix = uuid.uuid4().hex[:8]
    role_name, user_name, password = f"worker_{suffix}", f"worker_user_{suffix}", "worker-pw-1"
    admin.admin.command("createRole", role_name, privileges=privileges(ROLES["finpulse_worker"], database), roles=[])
    admin.admin.command("createUser", user_name, pwd=password, roles=[{"role": role_name, "db": "admin"}])
    host = urlsplit(URI).netloc.rsplit("@", 1)[-1]
    worker_client = MongoClient(f"mongodb://{user_name}:{password}@{host}/?authSource=admin", tz_aware=True)
    try:
        yield admin[database], worker_client[database], worker_client
    finally:
        worker_client.close()
        admin.drop_database(database)
        admin.admin.command("dropUser", user_name)
        admin.admin.command("dropRole", role_name)
        admin.close()


def make_config(**overrides):
    values = dict(mongodb_uri="mongodb://unused", mongodb_database="x", worker_id="worker-lp", poll_interval=0.01,
                  job_lease_seconds=60, ensure_indexes=False)
    values.update(overrides)
    return WorkerConfig(**values)


def test_the_worker_completes_a_whole_job_with_only_the_documented_privileges(secured, tmp_path):
    admin_db, worker_db, _ = secured
    ensure_indexes(admin_db)                                  # what the Spring Boot service does at startup
    doc_id, file_id = put_document(admin_db, "user-1", SAMPLE.read_bytes())
    job_id = insert_job(admin_db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)

    assert JobWorker(make_config(temp_directory=str(tmp_path / "tmp")), worker_db).process_one() is True

    job = admin_db["processing_jobs"].find_one({"_id": job_id})
    assert job["status"] == "COMPLETED" and job["extractedCount"] == 7
    assert admin_db["transactions"].count_documents({"userId": "user-1"}) == 7
    assert admin_db["uploaded_documents"].find_one({"_id": doc_id})["status"] == "PROCESSED"
    assert admin_db["reports"].count_documents({"userId": "user-1", "jobId": str(job_id)}) == 1


def test_a_failing_job_can_still_be_retried_and_failed_with_those_privileges(secured, tmp_path):
    admin_db, worker_db, _ = secured
    ensure_indexes(admin_db)
    job_id = insert_job(admin_db, user_id="user-1")           # document does not exist -> permanent failure path
    JobWorker(make_config(temp_directory=str(tmp_path / "tmp")), worker_db).process_one()
    assert admin_db["processing_jobs"].find_one({"_id": job_id})["status"] == "FAILED"


@pytest.mark.parametrize("name,operation", [
    ("read users", lambda db: db["users"].find_one({})),
    ("write users", lambda db: db["users"].insert_one({"email": "x@x.com"})),
    ("create a job", lambda db: db["processing_jobs"].insert_one({"status": "PENDING"})),
    ("delete a job", lambda db: db["processing_jobs"].delete_many({})),
    ("delete transactions", lambda db: db["transactions"].delete_many({})),
    ("edit a transaction (the API's job)", lambda db: db["transactions"].update_one({}, {"$set": {"merchant": "x"}})),
    ("delete documents", lambda db: db["uploaded_documents"].delete_many({})),
    ("write a GridFS file", lambda db: db["fs.files"].insert_one({"filename": "evil"})),
    ("write GridFS chunks", lambda db: db["fs.chunks"].insert_one({"data": b"x"})),
    ("delete a GridFS file", lambda db: db["fs.files"].delete_many({})),
    ("drop a collection", lambda db: db.drop_collection("transactions")),
    ("change indexes", lambda db: db["transactions"].create_index("merchant")),
    ("read the migration quarantine", lambda db: db["migration_quarantine"].find_one({})),
])
def test_everything_outside_the_role_is_denied(secured, name, operation):
    admin_db, worker_db, _ = secured
    admin_db["users"].insert_one({"email": "victim@example.com"})
    with pytest.raises(OperationFailure) as denied:
        operation(worker_db)
    assert denied.value.code == 13, f"{name}: expected 'unauthorized' (13), got {denied.value.code}: {denied.value}"


def test_other_databases_are_not_reachable(secured):
    _, _, client = secured
    for database, collection in (("admin", "system.users"), ("finpulse", "users"), ("local", "oplog.rs")):
        with pytest.raises(OperationFailure) as denied:
            client[database][collection].find_one({})
        assert denied.value.code == 13


def test_creating_indexes_needs_a_privilege_the_worker_role_deliberately_lacks(secured):
    """Hence the documented rule: run the worker with ENSURE_INDEXES=false; the API service owns the indexes."""
    _, worker_db, _ = secured
    with pytest.raises(OperationFailure) as denied:
        ensure_indexes(worker_db)
    assert denied.value.code == 13
