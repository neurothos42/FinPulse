"""Worker behaviour added in Phase 7: the image-quality gate and the optional ML category step."""
import pytest

from transaction_pipeline import PipelineResult
from worker.config import ConfigError, WorkerConfig
from worker.job_worker import JobWorker
from worker.mongo_client import ensure_indexes
from worker_helpers import VALID_PDF, VALID_PNG, insert_job, put_document
from test_worker import FakePipeline, doc_status, job_of, make_txn

PNG = VALID_PNG


def image_job(db, config, clock, confidence, pipeline=None, ml=None, filename="bill.png"):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", PNG, content_type="image/png", filename=filename)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    seen = []

    def confidence_fn(path):
        seen.append(path)
        return confidence

    worker = JobWorker(config, db, lambda: pipeline or FakePipeline([make_txn()]), clock,
                       confidence_fn=confidence_fn, ml_categorizer=ml)
    return worker, job_id, doc_id, seen


def test_clear_image_is_processed_normally_and_its_confidence_is_recorded(db, config, clock):
    worker, job_id, _, seen = image_job(db, config, clock, 92.4)
    worker.process_one()

    row = db["transactions"].find_one({})
    assert job_of(db, job_id)["status"] == "COMPLETED"
    assert row["needsReview"] is False and row["ocrConfidence"] == 92.4
    assert len(seen) == 1 and seen[0].endswith(".png")


def test_borderline_image_is_accepted_but_every_row_is_flagged_for_review(db, config, clock):
    worker, job_id, _, _ = image_job(db, config, clock, 55.0,
                                     pipeline=FakePipeline([make_txn("a"), make_txn("b", amount=-9.0)]))
    worker.process_one()

    rows = list(db["transactions"].find({}))
    assert job_of(db, job_id)["status"] == "COMPLETED" and len(rows) == 2
    assert all(r["needsReview"] is True and r["ocrConfidence"] == 55.0 for r in rows)


@pytest.mark.parametrize("confidence,message", [
    (None, "No readable text was found in the image"),
    (12.0, "too unclear"),
    (39.9, "too unclear"),
])
def test_unreadable_images_fail_permanently_before_the_pipeline_runs(db, config, clock, confidence, message):
    pipeline = FakePipeline([make_txn()])
    worker, job_id, doc_id, _ = image_job(db, config, clock, confidence, pipeline=pipeline)
    worker.process_one()

    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and job["attempts"] == 1 and message in job["errorMessage"]
    assert pipeline.seen_paths == [] and db["transactions"].count_documents({}) == 0
    assert doc_status(db, doc_id) == "FAILED"


def test_pdfs_are_never_confidence_gated(db, config, clock):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", VALID_PDF)
    insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    calls = []
    worker = JobWorker(config, db, lambda: FakePipeline([make_txn()]), clock,
                       confidence_fn=lambda path: calls.append(path) or 1.0)
    worker.process_one()
    assert calls == [] and db["transactions"].count_documents({}) == 1
    assert "ocrConfidence" not in db["transactions"].find_one({})


def test_thresholds_can_be_disabled(db, make_config, clock):
    worker, job_id, _, seen = image_job(db, make_config(ocr_min_confidence=0, ocr_review_confidence=0), clock, 5.0)
    worker.process_one()
    assert seen == [] and job_of(db, job_id)["status"] == "COMPLETED"


def test_ml_fills_only_uncategorised_rows_when_enabled(db, config, clock):
    from test_categorization_ml import FakeML
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", VALID_PDF)
    insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    rows = [make_txn("a", category="Uncategorized"), make_txn("b", category="Groceries")]
    JobWorker(config, db, lambda: FakePipeline(rows), clock, ml_categorizer=FakeML("ml")).process_one()

    saved = {r["sourceHash"]: r for r in db["transactions"].find({})}
    assert saved["a"]["category"] == "Entertainment" and saved["a"]["categorySource"] == "ml:cat-test"
    assert saved["b"]["category"] == "Food" and "categorySource" not in saved["b"]


def test_without_an_ml_categorizer_nothing_changes(db, config, clock):
    ensure_indexes(db)
    doc_id, file_id = put_document(db, "user-1", VALID_PDF)
    insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    JobWorker(config, db, lambda: FakePipeline([make_txn(category="Uncategorized")]), clock).process_one()

    row = db["transactions"].find_one({})
    assert row["category"] == "Other" and "categorySource" not in row


def test_config_defaults_and_validation():
    cfg = WorkerConfig.from_env({"MONGODB_URI": "m"})
    assert (cfg.ocr_min_confidence, cfg.ocr_review_confidence) == (40.0, 75.0)
    assert cfg.enable_ml_categorizer is False and cfg.ml_min_confidence == 0.6
    with pytest.raises(ConfigError, match="ML_MODEL_DIR"):
        WorkerConfig.from_env({"MONGODB_URI": "m", "ENABLE_ML_CATEGORIZER": "true"})
    with pytest.raises(ConfigError):
        WorkerConfig.from_env({"MONGODB_URI": "m", "OCR_MIN_CONFIDENCE": "-5"})
    ok = WorkerConfig.from_env({"MONGODB_URI": "m", "ENABLE_ML_CATEGORIZER": "1", "ML_MODEL_DIR": "/models",
                                "ML_MODEL_SHA256": "ab" * 32})
    assert ok.enable_ml_categorizer and ok.ml_model_dir == "/models"
