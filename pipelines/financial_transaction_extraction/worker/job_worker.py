"""The worker loop: claim a job, fetch its document from GridFS, run the existing pipeline, persist."""
from __future__ import annotations

import logging
import os
import re
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Optional

from pymongo.database import Database

from .config import WorkerConfig
from .errors import LeaseLostError, MappingError, PermanentJobError
from .gridfs_storage import GridFsStorage, parse_object_id
from .input_guard import check_document
from .job_manager import FAILED, NOT_OWNER, RETRY_SCHEDULED, JobManager, utcnow
from .logging_utils import log
from .ml_adapter import apply_ml_categories
from .transaction_mapper import map_transaction
from .transaction_store import TransactionStore

logger = logging.getLogger(__name__)

EXTENSIONS = {"application/pdf": ".pdf", "image/jpeg": ".jpg", "image/png": ".png"}

# Pipeline discard reasons that mean "this looked like a transaction but could not be read".
# (otp / promo / balance_alert / balance_row are intentional non-transaction filtering.)
SUSPECT_DISCARD_REASONS = {"no_amount", "not_a_debit_or_credit"}


@dataclass
class JobOutcome:
    extracted: int
    duplicates: int
    partial: bool
    document_id: object
    user_id: str
    ocr_confidence: Optional[float] = None
    report: Optional[dict] = None


class LeaseHeartbeat(threading.Thread):
    """Keeps the lease alive while a long extraction (OCR) runs; flags `lost` if ownership is gone."""

    def __init__(self, jobs: JobManager, job_id, interval: float):
        super().__init__(name=f"lease-heartbeat-{job_id}", daemon=True)
        self._jobs, self._job_id, self._interval = jobs, job_id, interval
        self._stop_event = threading.Event()
        self.lost = threading.Event()

    def run(self) -> None:
        while not self._stop_event.wait(self._interval):
            try:
                if not self._jobs.extend_lease(self._job_id):
                    self.lost.set()
                    return
            except Exception:  # network blip: try again next tick; the lease has slack
                log(logger, logging.WARNING, "lease_extend_failed", job_id=str(self._job_id))

    def stop(self) -> None:
        self._stop_event.set()
        if self.is_alive() and threading.current_thread() is not self:
            self.join(timeout=5)


def default_pipeline_factory(config: WorkerConfig) -> Callable[[], object]:
    def build():
        from transaction_pipeline import LLMCategorizer, TransactionPipeline
        # LLM categorisation sends merchant/description text to an external provider: opt-in only.
        llm = LLMCategorizer() if config.enable_llm_categorizer else None
        # store_path=None: no JSON file; a fresh pipeline per job: no state shared between users.
        return TransactionPipeline(store_path=None, llm_categorizer=llm)
    return build


def default_confidence_fn(path: str) -> Optional[float]:
    """Mean Tesseract confidence (0-100) of an image file, or None when no text was recognised."""
    from PIL import Image
    from ocr_preprocessing import mean_confidence
    with Image.open(path) as image:
        return mean_confidence(image.copy())


def temp_filename(document: dict) -> str:
    ext = EXTENSIONS.get(document.get("contentType"))
    if ext is None:
        raise PermanentJobError("Unsupported document type")
    stem = re.sub(r"[^A-Za-z0-9._-]", "_", Path(str(document.get("originalFilename") or "document")).stem)
    stem = stem.strip("._")[:60] or "document"
    return f"{stem}{ext}"


class JobWorker:
    def __init__(self, config: WorkerConfig, db: Database,
                 pipeline_factory: Optional[Callable[[], object]] = None,
                 clock: Callable[[], datetime] = utcnow,
                 confidence_fn: Optional[Callable[[str], Optional[float]]] = None,
                 ml_categorizer=None):
        self.config = config
        self._clock = clock
        self.jobs = JobManager(db, config, clock)
        self.storage = GridFsStorage(db, config.gridfs_bucket, config.max_file_bytes)
        self.store = TransactionStore(db)
        self._documents = db["uploaded_documents"]
        self._reports = db["reports"]
        self._pipeline_factory = pipeline_factory or default_pipeline_factory(config)
        self._confidence_fn = confidence_fn or default_confidence_fn
        self._ml = ml_categorizer
        if self._ml is None and config.enable_ml_categorizer:
            from categorization.model import MLCategorizer       # imported lazily: needs scikit-learn
            self._ml = MLCategorizer.load(config.ml_model_dir, config.ml_min_confidence, config.ml_model_sha256)
        if config.temp_directory:
            os.makedirs(config.temp_directory, exist_ok=True)

    # ------------------------------------------------------------------ loop
    def run(self, stop: threading.Event) -> None:
        log(logger, logging.INFO, "worker_started", worker_id=self.config.worker_id)
        delay = 1.0
        while not stop.is_set():
            self._touch_health_file()
            try:
                processed = self.process_one()
                delay = 1.0
            except Exception as exc:    # e.g. MongoDB unreachable while claiming; never kill the process
                log(logger, logging.ERROR, "worker_loop_error", error_type=type(exc).__name__, retry_in=delay)
                stop.wait(delay)
                delay = min(delay * 2, 60.0)
                continue
            if not processed:
                stop.wait(self.config.poll_interval)
        log(logger, logging.INFO, "worker_stopped", worker_id=self.config.worker_id)

    def process_one(self) -> bool:
        """Claims and processes one job. Returns False when there was nothing to do."""
        job = self.jobs.claim_next()
        if job is None:
            return False
        self._handle(job)
        return True

    # ------------------------------------------------------------------ one job
    def _handle(self, job: dict) -> None:
        job_id = job["_id"]
        fields = {"job_id": str(job_id), "attempt": job.get("attempts"), "worker_id": self.config.worker_id}
        log(logger, logging.INFO, "job_claimed", **fields)
        heartbeat = LeaseHeartbeat(self.jobs, job_id, self.config.heartbeat_interval)
        heartbeat.start()
        try:
            outcome = self._run_job(job, heartbeat)
            if self.jobs.complete(job_id, outcome.extracted, outcome.duplicates, outcome.partial):
                self._set_document_status(outcome.document_id, outcome.user_id, "PROCESSED")
                self._write_report(job, outcome)
                log(logger, logging.INFO, "job_completed", extracted=outcome.extracted,
                    duplicates=outcome.duplicates, partial=outcome.partial, **fields)
            else:
                log(logger, logging.WARNING, "job_lost_before_complete", **fields)
        except LeaseLostError:
            log(logger, logging.WARNING, "job_lease_lost", **fields)
        except PermanentJobError as exc:
            self._finish_failed(job, str(exc), permanent=True, fields=fields)
        except Exception as exc:
            self._finish_failed(job, f"Processing failed ({type(exc).__name__})", permanent=False, fields=fields,
                                exc=exc)
        finally:
            heartbeat.stop()

    def _run_job(self, job: dict, heartbeat: LeaseHeartbeat) -> JobOutcome:
        user_id = job.get("userId")
        if not user_id:
            raise PermanentJobError("Job has no owner")
        if job.get("attempts", 0) > self.config.max_job_attempts:
            raise PermanentJobError("Maximum processing attempts exceeded")

        document_id = parse_object_id(job.get("documentId"), "document reference")
        document = self._documents.find_one({"_id": document_id, "userId": user_id})
        if document is None:
            raise PermanentJobError("Document not found for this job")
        if document.get("gridFsFileId") != job.get("gridFsFileId"):
            raise PermanentJobError("Document file reference does not match the job")

        filename = temp_filename(document)
        self._set_document_status(document_id, user_id, "PROCESSING")

        # TemporaryDirectory is created 0700 and removed on exit even when processing raises.
        with tempfile.TemporaryDirectory(prefix="finpulse-", dir=self.config.temp_directory) as tmp:
            path = self.storage.download(document["gridFsFileId"], user_id, Path(tmp), filename)
            self._ensure_lease(heartbeat)
            check_document(path, document.get("contentType", ""), max_image_pixels=self.config.max_image_pixels,
                           max_pdf_pages=self.config.max_pdf_pages)
            ocr_confidence = self._check_image_quality(document, path)
            result = self._pipeline_factory().process_file(str(path))
            self._ensure_lease(heartbeat)

        now = self._clock()
        mapped, mapping_errors = [], 0
        for txn in result.transactions:
            try:
                mapped.append(map_transaction(
                    txn, user_id=user_id, document_id=str(document_id), job_id=str(job["_id"]), now=now))
            except MappingError:
                mapping_errors += 1

        ml_rows = 0
        if self._ml is not None:
            ml_rows = apply_ml_categories(mapped, self._ml)
        if ocr_confidence is not None:
            for doc in mapped:
                doc["ocrConfidence"] = round(ocr_confidence, 1)
                if ocr_confidence < self.config.ocr_review_confidence:
                    doc["needsReview"] = True              # low-quality scan: a human should confirm the figures

        summary = self.store.save_all(mapped, job["_id"])
        extracted = summary.saved + summary.already_saved
        duplicates = len(result.duplicates) + summary.duplicates
        suspect = sum(1 for d in result.discarded if d.get("reason") in SUSPECT_DISCARD_REASONS)

        if extracted + duplicates == 0:
            raise PermanentJobError("No transactions could be extracted from this document")
        discarded_by_reason: dict = {}
        for d in result.discarded:
            reason = d.get("reason", "unknown")
            discarded_by_reason[reason] = discarded_by_reason.get(reason, 0) + 1
        report = {
            "extracted": extracted, "duplicates": duplicates, "pipelineDuplicates": len(result.duplicates),
            "databaseDuplicates": summary.duplicates, "discarded": discarded_by_reason,
            "mappingErrors": mapping_errors,
            "needsReview": sum(1 for d in mapped if d["needsReview"]),
            "unknownDirection": sum(1 for d in mapped if d["direction"] == "UNKNOWN"),
            "ocrConfidence": None if ocr_confidence is None else round(ocr_confidence, 1),
            "mlModel": getattr(self._ml, "version", None), "mlRowsConsidered": ml_rows,
        }
        return JobOutcome(extracted, duplicates, partial=(suspect + mapping_errors) > 0,
                          document_id=document_id, user_id=user_id, ocr_confidence=ocr_confidence, report=report)

    # ------------------------------------------------------------------ helpers
    def _check_image_quality(self, document: dict, path: Path) -> Optional[float]:
        """Confidence gate for uploaded images only (PDFs are not gated). Returns the confidence or None."""
        if not str(document.get("contentType", "")).startswith("image/"):
            return None
        if self.config.ocr_min_confidence <= 0 and self.config.ocr_review_confidence <= 0:
            return None
        confidence = self._confidence_fn(str(path))
        if confidence is None:
            raise PermanentJobError("No readable text was found in the image")
        if confidence < self.config.ocr_min_confidence:
            raise PermanentJobError("The image is too unclear to read. Please upload a sharper photo or the PDF")
        return confidence

    @staticmethod
    def _ensure_lease(heartbeat: LeaseHeartbeat) -> None:
        if heartbeat.lost.is_set():
            raise LeaseLostError()

    def _finish_failed(self, job: dict, message: str, *, permanent: bool, fields: dict, exc=None) -> None:
        job_id = job["_id"]
        try:
            if permanent:
                if self.jobs.fail_permanently(job_id, message):
                    self._set_document_status(job.get("documentId"), job.get("userId"), "FAILED")
                    log(logger, logging.WARNING, "job_failed_permanently", reason=message, **fields)
                return
            outcome = self.jobs.fail(job_id, message)
            if outcome == RETRY_SCHEDULED:
                self._set_document_status(job.get("documentId"), job.get("userId"), "UPLOADED")
                log(logger, logging.WARNING, "job_retry_scheduled", error_type=type(exc).__name__, **fields)
            elif outcome == FAILED:
                self._set_document_status(job.get("documentId"), job.get("userId"), "FAILED")
                log(logger, logging.ERROR, "job_failed", error_type=type(exc).__name__, **fields)
            elif outcome == NOT_OWNER:
                log(logger, logging.WARNING, "job_lost_before_fail", **fields)
        except Exception as db_exc:
            # The lease will expire and the job will be recovered by claim_next.
            log(logger, logging.ERROR, "job_failure_not_recorded", error_type=type(db_exc).__name__, **fields)

    def _write_report(self, job: dict, outcome: JobOutcome) -> None:
        """One summary per (user, job): counts and reasons only (no merchants, descriptions or amounts).
        Best effort: a reporting failure never changes the job's result."""
        try:
            now = self._clock()
            self._reports.update_one(
                {"userId": outcome.user_id, "jobId": str(job["_id"])},
                {"$set": {**outcome.report, "documentId": str(outcome.document_id),
                          "attempt": job.get("attempts"), "partial": outcome.partial, "updatedAt": now},
                 "$setOnInsert": {"createdAt": now}},
                upsert=True)
        except Exception as exc:
            log(logger, logging.WARNING, "report_not_written", error_type=type(exc).__name__)

    def _set_document_status(self, document_id, user_id, status: str) -> None:
        try:
            self._documents.update_one(
                {"_id": parse_object_id(document_id, "document reference"), "userId": user_id},
                {"$set": {"status": status, "updatedAt": self._clock()}})
        except Exception as exc:    # a status label must never break job handling
            log(logger, logging.WARNING, "document_status_update_failed", status=status,
                error_type=type(exc).__name__)

    def _touch_health_file(self) -> None:
        if not self.config.health_file:
            return
        try:
            Path(self.config.health_file).touch()
        except OSError:
            pass
