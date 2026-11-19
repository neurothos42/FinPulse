"""Cheap sanity checks on a downloaded document BEFORE the expensive pipeline runs.

Uploaded files are untrusted. A 20 KB PNG can declare 400 million pixels (a "decompression bomb") and make OCR
exhaust memory; a PDF can have thousands of pages, be password-protected, or be corrupt. Each case fails the job
immediately and permanently with a fixed, safe message (retrying cannot help), instead of burning attempts."""
from __future__ import annotations

from pathlib import Path

from .errors import PermanentJobError

DEFAULT_MAX_IMAGE_PIXELS = 40_000_000       # ~ 8000 x 5000
DEFAULT_MAX_PDF_PAGES = 100


def check_document(path: Path, content_type: str, *, max_image_pixels: int = DEFAULT_MAX_IMAGE_PIXELS,
                   max_pdf_pages: int = DEFAULT_MAX_PDF_PAGES) -> None:
    if content_type == "application/pdf":
        _check_pdf(path, max_pdf_pages)
    elif content_type in ("image/png", "image/jpeg"):
        _check_image(path, max_image_pixels)


def _check_image(path: Path, max_pixels: int) -> None:
    import warnings

    from PIL import Image, UnidentifiedImageError
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", Image.DecompressionBombWarning)     # we apply our own, stricter limit
            with Image.open(path) as image:      # reads the header only: nothing is decoded here
                width, height = image.size
    except Image.DecompressionBombError:         # PIL's own hard stop (> ~178 million pixels)
        raise PermanentJobError("The image is too large to process") from None
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise PermanentJobError("The file is not a readable image") from None
    if width * height > max_pixels:
        raise PermanentJobError("The image is too large to process")


def _check_pdf(path: Path, max_pages: int) -> None:
    import pypdfium2 as pdfium
    try:
        pdf = pdfium.PdfDocument(str(path))
    except pdfium.PdfiumError as exc:
        if "password" in str(exc).lower():
            raise PermanentJobError("The PDF is password-protected") from None
        raise PermanentJobError("The file is not a readable PDF") from None
    try:
        pages = len(pdf)
    finally:
        pdf.close()
    if pages > max_pages:
        raise PermanentJobError("The PDF has too many pages to process")
