import json

from worker.mongo_client import ensure_indexes
from worker_helpers import VALID_PDF, VALID_PNG, insert_job, put_document
from test_worker import FakePipeline, SAMPLE_PDF, make_txn
from worker.job_worker import JobWorker


def run_sample(db, config, clock, ml=None, pipeline=None, content=None, content_type="application/pdf", confidence_fn=None):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", content or SAMPLE_PDF.read_bytes(), content_type=content_type)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    worker = JobWorker(config, db, (lambda: pipeline) if pipeline else None, clock, ml_categorizer=ml,
                       confidence_fn=confidence_fn)
    worker.process_one()
    return job_id, doc_id


def test_completed_job_writes_one_summary_with_counts_and_reasons(db, config, clock):
    job_id, doc_id = run_sample(db, config, clock)

    report = db["reports"].find_one({"userId": "user-1", "jobId": str(job_id)})
    assert report["documentId"] == str(doc_id)
    assert (report["extracted"], report["duplicates"], report["attempt"]) == (7, 0, 1)
    assert report["discarded"] == {"otp": 1, "promo": 1, "balance_alert": 1}
    assert report["mappingErrors"] == 0 and report["partial"] is False
    assert report["needsReview"] == 1 and report["unknownDirection"] == 0
    assert report["mlModel"] is None and report["ocrConfidence"] is None
    assert report["createdAt"] == clock() and report["updatedAt"] == clock()


def test_report_contains_no_personal_or_financial_detail(db, config, clock):
    job_id, _ = run_sample(db, config, clock)
    report = db["reports"].find_one({"jobId": str(job_id)})
    text = json.dumps({k: v for k, v in report.items() if k != "_id"}, default=str).lower()

    for row in db["transactions"].find({}):
        for field in ("merchant", "description", "referenceNumber"):
            if row.get(field):
                assert str(row[field]).lower() not in text, f"{field} leaked into the report"
    assert "swiggy" not in text and "inr" not in text


def test_reprocessing_the_same_job_keeps_a_single_report(db, config, clock):
    job_id, _ = run_sample(db, config, clock)
    first = db["reports"].find_one({"jobId": str(job_id)})
    clock.advance(10)
    db["processing_jobs"].update_one({"_id": job_id}, {"$set": {"status": "PENDING", "remainingAttempts": 2, "attempts": 1}})
    JobWorker(config, db, clock=clock).process_one()

    assert db["reports"].count_documents({"jobId": str(job_id)}) == 1
    again = db["reports"].find_one({"jobId": str(job_id)})
    assert again["createdAt"] == first["createdAt"] and again["updatedAt"] > first["updatedAt"]
    assert (again["extracted"], again["duplicates"]) == (7, 0)             # retry is idempotent: rows are "already saved"


def test_failed_jobs_write_no_report(db, config, clock):
    job_id, _ = run_sample(db, config, clock, pipeline=FakePipeline(error=RuntimeError("boom")))
    assert db["reports"].count_documents({}) == 0 and db["processing_jobs"].find_one({"_id": job_id})["status"] == "PENDING"


def test_a_reporting_failure_never_changes_the_job_result(db, config, clock, monkeypatch):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", VALID_PDF)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    worker = JobWorker(config, db, lambda: FakePipeline([make_txn()]), clock)

    def boom(*args, **kwargs):
        raise RuntimeError("reports collection unavailable")

    monkeypatch.setattr(worker._reports, "update_one", boom)
    worker.process_one()

    assert db["processing_jobs"].find_one({"_id": job_id})["status"] == "COMPLETED"
    assert db["transactions"].count_documents({}) == 1


def test_report_records_image_confidence_ml_usage_and_flagged_rows(db, config, clock):
    class FakeML:
        version = "cat-test"

        def predict(self, merchant, description, direction):
            from categorization.model import Prediction
            return Prediction("Entertainment", 0.9, "cat-test", False, "ml", "Entertainment")

    pipeline = FakePipeline([make_txn("a", category="Uncategorized"), make_txn("b", direction="unknown", needs_review=True)],
                            discarded=[{"reason": "no_amount", "text": "t", "source": "s"}])
    job_id, _ = run_sample(db, config, clock, ml=FakeML(), pipeline=pipeline, content=VALID_PNG,
                           content_type="image/png", confidence_fn=lambda path: 60.0)

    report = db["reports"].find_one({"jobId": str(job_id)})
    assert report["ocrConfidence"] == 60.0 and report["mlModel"] == "cat-test" and report["mlRowsConsidered"] == 1
    assert report["needsReview"] == 2                       # image below 75 -> every row flagged
    assert report["unknownDirection"] == 1 and report["discarded"] == {"no_amount": 1} and report["partial"] is True


def test_the_unique_user_job_index_exists(db):
    ensure_indexes(db)
    info = db["reports"].index_information()["userId_jobId"]
    assert info["unique"] is True and list(info["key"]) == [("userId", 1), ("jobId", 1)]
