import threading
import time
from decimal import Decimal
from pathlib import Path

import pytest
from bson.decimal128 import Decimal128

from transaction_pipeline import PipelineResult, Transaction, TransactionPipeline
from worker.job_manager import JobManager
from worker.job_worker import JobWorker, default_pipeline_factory, temp_filename
from worker.errors import PermanentJobError
from worker.mongo_client import ensure_indexes
from worker_helpers import VALID_PDF, insert_job, put_document

SAMPLE_PDF = Path(__file__).resolve().parents[1] / "samples" / "statement.pdf"


def make_txn(hash_="h1", **overrides):
    values = dict(date="2026-10-03", amount=-450.0, currency="INR", direction="debit", merchant="Swiggy",
                  category="Food & Dining", description="UPI/DR/1/SWIGGY", source="s.pdf", hash=hash_)
    values.update(overrides)
    return Transaction(**values)


class FakePipeline:
    def __init__(self, transactions=(), duplicates=(), discarded=(), error=None, hook=None):
        self.result = PipelineResult(list(transactions), list(duplicates), list(discarded))
        self.error, self.hook, self.seen_paths = error, hook, []

    def process_file(self, path):
        self.seen_paths.append(path)
        if self.hook:
            self.hook(path)
        if self.error:
            raise self.error
        return self.result


@pytest.fixture
def setup(db, config, clock):
    ensure_indexes(db)

    def build(pipeline=None, cfg=None, user="user-1", content=VALID_PDF, **doc_kwargs):
        doc_id, file_id = put_document(db, user, content, **doc_kwargs)
        job_id = insert_job(db, user_id=user, document_id=doc_id, gridfs_file_id=file_id)
        worker = JobWorker(cfg or config, db, (lambda: pipeline) if pipeline else None, clock)
        return worker, job_id, doc_id

    return build


def job_of(db, job_id):
    return db["processing_jobs"].find_one({"_id": job_id})


def doc_status(db, doc_id):
    return db["uploaded_documents"].find_one({"_id": doc_id})["status"]


# ---------------------------------------------------------------- end to end with the REAL pipeline
def test_real_pipeline_end_to_end_on_sample_statement(db, setup, config):
    worker, job_id, doc_id = setup(content=SAMPLE_PDF.read_bytes(), filename="my statement.pdf")

    assert worker.process_one() is True

    job = job_of(db, job_id)
    assert job["status"] == "COMPLETED"
    assert (job["extractedCount"], job["duplicateCount"]) == (7, 0)
    assert doc_status(db, doc_id) == "PROCESSED"

    rows = list(db["transactions"].find({}))
    assert len(rows) == 7
    for row in rows:
        assert row["userId"] == "user-1" and row["documentId"] == str(doc_id) and row["jobId"] == str(job_id)
        assert isinstance(row["amount"], Decimal128) and row["amount"].to_decimal() > 0
        assert row["direction"] in {"DEBIT", "CREDIT"}
    assert {r["direction"] for r in rows} == {"DEBIT", "CREDIT"}
    swiggy = next(r for r in rows if r["merchant"] and "swiggy" in r["merchant"].lower())
    assert swiggy["category"] == "Food" and swiggy["pipelineCategory"] == "Food & Dining"
    assert worker.process_one() is False                      # queue is empty afterwards


def test_two_users_uploading_the_same_statement_each_keep_their_own_transactions(db, config, clock):
    ensure_indexes(db)
    content = SAMPLE_PDF.read_bytes()
    jobs = {}
    for user in ("user-A", "user-B"):
        doc_id, file_id = put_document(db, user, content)
        jobs[user] = insert_job(db, user_id=user, document_id=doc_id, gridfs_file_id=file_id)
    worker = JobWorker(config, db, clock=clock)

    assert worker.process_one() and worker.process_one()

    for user in ("user-A", "user-B"):
        assert db["transactions"].count_documents({"userId": user}) == 7
        assert job_of(db, jobs[user])["extractedCount"] == 7 and job_of(db, jobs[user])["duplicateCount"] == 0


def test_reuploading_the_same_statement_reports_duplicates_and_adds_nothing(db, config, clock):
    ensure_indexes(db)
    content = SAMPLE_PDF.read_bytes()
    worker = JobWorker(config, db, clock=clock)
    job_ids = []
    for _ in range(2):
        doc_id, file_id = put_document(db, "user-1", content)
        job_ids.append(insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id))
        assert worker.process_one()

    second = job_of(db, job_ids[1])
    assert second["status"] == "COMPLETED"
    assert (second["extractedCount"], second["duplicateCount"]) == (0, 7)
    assert db["transactions"].count_documents({"userId": "user-1"}) == 7


def test_default_factory_builds_an_isolated_pipeline_without_file_store_or_llm(config):
    factory = default_pipeline_factory(config)
    first, second = factory(), factory()
    assert isinstance(first, TransactionPipeline) and first is not second
    assert first.store_path is None and first.llm is None


# ---------------------------------------------------------------- outcomes
def test_unknown_direction_is_persisted_and_flagged(db, setup):
    worker, job_id, _ = setup(FakePipeline([make_txn(direction="unknown", amount=75.0, needs_review=True)]))
    worker.process_one()

    row = db["transactions"].find_one({})
    assert row["direction"] == "UNKNOWN" and row["needsReview"] is True
    assert job_of(db, job_id)["status"] == "COMPLETED"


def test_possibly_lost_rows_make_the_job_partially_completed(db, setup):
    pipeline = FakePipeline([make_txn()], discarded=[{"reason": "no_amount", "text": "t", "source": "s"}])
    worker, job_id, doc_id = setup(pipeline)
    worker.process_one()

    assert job_of(db, job_id)["status"] == "PARTIALLY_COMPLETED"
    assert doc_status(db, doc_id) == "PROCESSED"


def test_intentional_noise_filtering_does_not_make_a_job_partial(db, setup):
    discarded = [{"reason": r, "text": "t", "source": "s"} for r in ("otp", "promo", "balance_alert", "balance_row")]
    worker, job_id, _ = setup(FakePipeline([make_txn()], discarded=discarded))
    worker.process_one()
    assert job_of(db, job_id)["status"] == "COMPLETED"


def test_unmappable_row_is_skipped_and_job_is_partial(db, setup):
    pipeline = FakePipeline([make_txn("good"), make_txn("bad", amount=float("nan"))])
    worker, job_id, _ = setup(pipeline)
    worker.process_one()

    job = job_of(db, job_id)
    assert job["status"] == "PARTIALLY_COMPLETED" and job["extractedCount"] == 1
    assert db["transactions"].count_documents({}) == 1


def test_pipeline_duplicates_are_counted(db, setup):
    pipeline = FakePipeline([make_txn()], duplicates=[{"reason": "exact", "duplicate_of": "h", "transaction": {}}])
    worker, job_id, _ = setup(pipeline)
    worker.process_one()
    assert job_of(db, job_id)["duplicateCount"] == 1


# ---------------------------------------------------------------- failures and retries
def test_pipeline_exception_schedules_retry_then_fails_after_max_attempts(db, setup, clock, config):
    worker, job_id, doc_id = setup(FakePipeline(error=RuntimeError("secret statement text: 4111-1111")))

    worker.process_one()
    job = job_of(db, job_id)
    assert job["status"] == "PENDING" and job["attempts"] == 1
    assert job["errorMessage"] == "Processing failed (RuntimeError)"
    assert "4111" not in job["errorMessage"], "exception text must never be stored"
    assert doc_status(db, doc_id) == "UPLOADED"

    assert worker.process_one() is False                       # backing off
    for expected in ("PENDING", "FAILED"):
        clock.advance(7200)
        assert worker.process_one() is True
        assert job_of(db, job_id)["status"] == expected
    assert doc_status(db, doc_id) == "FAILED"
    assert job_of(db, job_id)["attempts"] == 3
    clock.advance(10 ** 6)
    assert worker.process_one() is False
    assert db["transactions"].count_documents({}) == 0


def test_missing_document_fails_permanently_on_first_attempt(db, config, clock):
    ensure_indexes(db)
    job_id = insert_job(db, user_id="user-1")
    worker = JobWorker(config, db, lambda: FakePipeline([make_txn()]), clock)

    worker.process_one()

    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and job["attempts"] == 1
    assert job["errorMessage"] == "Document not found for this job"


def test_document_record_of_another_user_is_never_processed(db, config, clock):
    """The GridFS file's metadata deliberately matches the job's user, so ONLY the document-level
    ownership check can stop this (defence in depth: each layer is tested on its own)."""
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-2", VALID_PDF, file_owner="user-1")
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    pipeline = FakePipeline([make_txn()])
    JobWorker(config, db, lambda: pipeline, clock).process_one()

    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and job["errorMessage"] == "Document not found for this job"
    assert pipeline.seen_paths == []
    assert db["transactions"].count_documents({}) == 0
    assert doc_status(db, doc_id) == "UPLOADED", "another user's document must not be modified either"


def test_gridfs_file_owned_by_someone_else_is_refused(db, setup):
    pipeline = FakePipeline([make_txn()])
    worker, job_id, _ = setup(pipeline, file_owner="user-2")
    worker.process_one()

    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and "does not belong" in job["errorMessage"]
    assert pipeline.seen_paths == [] and db["transactions"].count_documents({}) == 0


def test_mismatched_file_reference_is_refused(db, config, clock):
    ensure_indexes(db)
    doc_id, _ = put_document(db, "user-1", VALID_PDF)
    _, other_file = put_document(db, "user-1", VALID_PDF)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=other_file)
    JobWorker(config, db, lambda: FakePipeline([make_txn()]), clock).process_one()
    assert "does not match" in job_of(db, job_id)["errorMessage"]


def test_unsupported_content_type_fails_permanently(db, setup):
    worker, job_id, doc_id = setup(FakePipeline([make_txn()]), content_type="application/x-msdownload")
    worker.process_one()
    assert job_of(db, job_id)["status"] == "FAILED"
    assert doc_status(db, doc_id) == "FAILED"


def test_document_without_transactions_fails_with_a_clear_message(db, setup):
    worker, job_id, _ = setup(FakePipeline([]))
    worker.process_one()
    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and job["attempts"] == 1
    assert job["errorMessage"] == "No transactions could be extracted from this document"


def test_oversized_stored_file_is_refused(db, setup, make_config):
    worker, job_id, _ = setup(FakePipeline([make_txn()]), cfg=make_config(max_file_bytes=5))
    worker.process_one()
    assert "maximum" in job_of(db, job_id)["errorMessage"]


# ---------------------------------------------------------------- temp files
def test_temp_files_are_removed_after_success_and_after_failure(db, setup, make_config, tmp_path):
    cfg = make_config()
    ok = FakePipeline([make_txn()])
    worker, _, _ = setup(ok, cfg=cfg)
    worker.process_one()
    assert ok.seen_paths and not Path(ok.seen_paths[0]).exists()
    assert list(Path(cfg.temp_directory).iterdir()) == []

    boom = FakePipeline(error=RuntimeError("x"))
    worker2, _, _ = setup(boom, cfg=cfg)
    worker2.process_one()
    assert boom.seen_paths and not Path(boom.seen_paths[0]).exists()
    assert list(Path(cfg.temp_directory).iterdir()) == []


def test_temp_file_has_a_sanitised_name_and_matching_extension(db, setup):
    pipeline = FakePipeline([make_txn()])
    worker, _, _ = setup(pipeline, filename="../../my statement (final).PDF")
    worker.process_one()
    name = Path(pipeline.seen_paths[0]).name
    assert name.endswith(".pdf") and "/" not in name and ".." not in name


def test_temp_filename_rules():
    assert temp_filename({"contentType": "image/png", "originalFilename": "bill.jpeg"}) == "bill.png"
    assert temp_filename({"contentType": "image/jpeg", "originalFilename": None}) == "document.jpg"
    with pytest.raises(PermanentJobError):
        temp_filename({"contentType": "text/html", "originalFilename": "a.html"})


# ---------------------------------------------------------------- crash recovery, lease loss, loop
def test_job_abandoned_by_a_crashed_worker_is_recovered_by_another(db, make_config, clock):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", VALID_PDF)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    crashed = JobManager(db, make_config(worker_id="worker-crashed"), clock)
    assert crashed.claim_next() is not None                    # claims, then the process dies

    clock.advance(61)
    survivor = JobWorker(make_config(worker_id="worker-b"), db, lambda: FakePipeline([make_txn()]), clock)
    assert survivor.process_one() is True

    job = job_of(db, job_id)
    assert job["status"] == "COMPLETED" and job["attempts"] == 2
    assert crashed.complete(job_id, 99, 99) is False
    assert db["transactions"].count_documents({}) == 1


def test_worker_that_lost_its_lease_writes_nothing_and_does_not_touch_the_job(db, setup, make_config):
    cfg = make_config(job_lease_seconds=0.2)                  # heartbeat every 0.05s

    def steal_lease(_path):
        db["processing_jobs"].update_one({}, {"$set": {"leaseOwner": "worker-b"}})
        time.sleep(0.4)                                      # let the heartbeat notice

    worker, job_id, doc_id = setup(FakePipeline([make_txn()], hook=steal_lease), cfg=cfg)
    worker.process_one()

    job = job_of(db, job_id)
    assert job["leaseOwner"] == "worker-b" and job["status"] == "PROCESSING"
    assert db["transactions"].count_documents({}) == 0


def test_loop_survives_database_errors_and_stops_gracefully(db, config, clock):
    worker = JobWorker(config, db, clock=clock)
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("mongodb unreachable")
        return None

    worker.jobs.claim_next = flaky
    stop = threading.Event()
    thread = threading.Thread(target=worker.run, args=(stop,))
    thread.start()
    deadline = time.time() + 5
    while calls["n"] < 3 and time.time() < deadline:
        time.sleep(0.02)
    stop.set()
    thread.join(5)

    assert calls["n"] >= 3, "loop must keep polling after an error"
    assert not thread.is_alive()


def test_run_returns_immediately_when_already_stopped(db, config, clock):
    stop = threading.Event()
    stop.set()
    JobWorker(config, db, clock=clock).run(stop)


def test_health_file_is_touched_by_the_loop(db, make_config, clock, tmp_path):
    health = tmp_path / "alive"
    worker = JobWorker(make_config(health_file=str(health)), db, clock=clock)
    stop = threading.Event()
    thread = threading.Thread(target=worker.run, args=(stop,))
    thread.start()
    deadline = time.time() + 3
    while not health.exists() and time.time() < deadline:
        time.sleep(0.02)
    stop.set()
    thread.join(5)
    assert health.exists()
