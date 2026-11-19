import io
from pathlib import Path

import pytest
from PIL import Image
from reportlab.pdfgen import canvas

from worker.errors import PermanentJobError
from worker.input_guard import check_document
from worker.job_worker import JobWorker
from worker.mongo_client import ensure_indexes
from worker_helpers import insert_job, put_document
from test_worker import FakePipeline, doc_status, job_of, make_txn

SAMPLES = Path(__file__).resolve().parents[1] / "samples"


def pdf(path, pages=1, password=None):
    c = canvas.Canvas(str(path), encrypt=password) if password else canvas.Canvas(str(path))
    for i in range(pages):
        c.drawString(72, 720, f"page {i}")
        c.showPage()
    c.save()
    return path


def png(path, size, mode="1"):
    Image.new(mode, size).save(path, "PNG")
    return path


# ------------------------------------------------------------------ PDFs
def test_normal_pdfs_pass(tmp_path):
    check_document(SAMPLES / "statement.pdf", "application/pdf")
    check_document(pdf(tmp_path / "ok.pdf", pages=3), "application/pdf", max_pdf_pages=3)


def test_too_many_pages_are_refused(tmp_path):
    with pytest.raises(PermanentJobError, match="too many pages"):
        check_document(pdf(tmp_path / "long.pdf", pages=5), "application/pdf", max_pdf_pages=4)


def test_password_protected_pdfs_are_refused_with_a_clear_message(tmp_path):
    with pytest.raises(PermanentJobError, match="password-protected"):
        check_document(pdf(tmp_path / "locked.pdf", password="secret"), "application/pdf")


@pytest.mark.parametrize("content", [b"%PDF-1.4 this is not really a pdf", b"%PDF-", b""])
def test_corrupt_pdfs_are_refused(tmp_path, content):
    path = tmp_path / "bad.pdf"
    path.write_bytes(content)
    with pytest.raises(PermanentJobError, match="not a readable PDF"):
        check_document(path, "application/pdf")


# ------------------------------------------------------------------ images
def test_normal_images_pass():
    for name in ("Screenshot 2026-10-05 173242.png",):
        check_document(SAMPLES / name, "image/png")


def test_a_tiny_file_that_declares_a_huge_image_is_refused_without_decoding_it(tmp_path):
    bomb = png(tmp_path / "bomb.png", (12000, 12000))             # 144 million pixels in a few KB
    assert bomb.stat().st_size < 200_000
    with pytest.raises(PermanentJobError, match="too large"):
        check_document(bomb, "image/png", max_image_pixels=10_000_000)


def test_an_extreme_image_that_pil_itself_refuses_is_reported_as_too_large_not_unreadable(tmp_path):
    bomb = png(tmp_path / "huge.png", (20000, 20000))                # 400 million pixels: PIL raises DecompressionBombError
    assert bomb.stat().st_size < 1_000_000
    with pytest.raises(PermanentJobError, match="too large"):
        check_document(bomb, "image/png")


def test_the_pixel_limit_is_configurable_and_exact(tmp_path):
    path = png(tmp_path / "a.png", (1000, 1000))
    check_document(path, "image/png", max_image_pixels=1_000_000)
    with pytest.raises(PermanentJobError):
        check_document(path, "image/png", max_image_pixels=999_999)


@pytest.mark.parametrize("content", [b"\x89PNG\r\n\x1a\n" + b"garbage", b"not an image", b""])
def test_unreadable_images_are_refused(tmp_path, content):
    path = tmp_path / "bad.png"
    path.write_bytes(content)
    with pytest.raises(PermanentJobError, match="not a readable image"):
        check_document(path, "image/png")


def test_jpeg_gets_the_same_checks(tmp_path):
    buffer = io.BytesIO()
    Image.new("RGB", (3000, 3000)).save(buffer, "JPEG")
    path = tmp_path / "big.jpg"
    path.write_bytes(buffer.getvalue())
    with pytest.raises(PermanentJobError, match="too large"):
        check_document(path, "image/jpeg", max_image_pixels=1_000_000)


# ------------------------------------------------------------------ inside the worker
@pytest.mark.parametrize("builder,content_type,message", [
    (lambda p: pdf(p, password="secret"), "application/pdf", "password-protected"),
    (lambda p: png(p, (12000, 12000)), "image/png", "too large"),
    (lambda p: png(p, (20000, 20000)), "image/png", "too large"),
])
def test_hostile_uploads_fail_the_job_at_once_and_never_reach_the_pipeline(db, config, clock, tmp_path, builder, content_type, message):
    ensure_indexes(db)
    path = builder(tmp_path / "upload")
    doc_id, file_id = put_document(db, "user-1", path.read_bytes(), content_type=content_type)
    job_id = insert_job(db, user_id="user-1", document_id=doc_id, gridfs_file_id=file_id)
    pipeline = FakePipeline([make_txn()])
    worker = JobWorker(config, db, lambda: pipeline, clock, confidence_fn=lambda p: 99.0)

    worker.process_one()

    job = job_of(db, job_id)
    assert job["status"] == "FAILED" and job["attempts"] == 1 and message in job["errorMessage"]
    assert pipeline.seen_paths == [] and db["transactions"].count_documents({}) == 0
    assert doc_status(db, doc_id) == "FAILED"
