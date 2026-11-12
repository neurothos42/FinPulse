#!/usr/bin/env python3
"""
transaction_pipeline.py
=======================
Upload -> Extract text (PDF / image / SMS) -> Split -> Pre-filter -> Parse
       -> Categorise -> De-duplicate -> clean JSON array.

Follows the architecture diagram:
  PyMuPDF/pdfplumber + Tesseract  ->  regex date-boundary splitter
  -> rule-based pre-filter (drop OTP / promo / balance alerts)
  -> debit = amount * -1, credit = amount * +1
  -> SHA-256 de-duplication (+ fuzzy cross-source matching)
  -> JSON

Usage
-----
  python transaction_pipeline.py statement.pdf receipt.jpg --out clean.json
  python transaction_pipeline.py --sms "Rs.450 debited from A/c XX1234 to SWIGGY on 02-09-25"
  python transaction_pipeline.py sms_dump.txt --report report.json
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import sys
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime
from difflib import SequenceMatcher
from pathlib import Path
from typing import Optional

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff"}
SUPPORTED_EXTS = IMAGE_EXTS | {".pdf", ".txt"}

# All default paths are relative to this file, so it works from any VS Code working directory.
BASE_DIR = Path(__file__).resolve().parent
UPLOADS_DIR = BASE_DIR / "uploads"      # drop your PDFs / receipt photos / SMS .txt files here
OUTPUT_DIR = BASE_DIR / "output"


# =============================================================================
# 1. TEXT EXTRACTION  (PDF text layer -> OCR fallback, images -> OCR)
# =============================================================================
def _find_tesseract(pytesseract) -> None:
    """Windows installers don't add Tesseract to PATH, so look in the usual places.
    You can also set the TESSERACT_CMD environment variable to the full path."""
    cmd = os.environ.get("TESSERACT_CMD")
    if not cmd and not shutil.which("tesseract"):
        for guess in (r"C:\Program Files\Tesseract-OCR\tesseract.exe",
                      r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
                      "/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract"):
            if Path(guess).exists():
                cmd = guess
                break
    if cmd:
        pytesseract.pytesseract.tesseract_cmd = cmd


def ocr_image(img, cell=False) -> str:
    """OCR a PIL image with light pre-processing (greyscale, upscale, contrast)."""
    import pytesseract
    from PIL import Image, ImageOps

    _find_tesseract(pytesseract)

    img = ImageOps.exif_transpose(img).convert("L")
    if cell:
        # Segment before upscaling: descenders can touch the next OCR line.
        bands = []
        pixels = img.load()
        for y in range(img.height):
            if sum(pixels[x, y] < 140 for x in range(img.width)) > (0 if cell == "reference" else max(2, img.width * .015)):
                if not bands or y > bands[-1][-1] + 1:
                    bands.append([])
                bands[-1].append(y)
        lines = []
        for band in bands:
            if len(band) < 4:
                continue
            line = img.crop((0, max(0, band[0]-1), img.width, min(img.height, band[-1]+2)))
            line = line.resize((line.width * 4, line.height * 4), Image.Resampling.LANCZOS)
            line = ImageOps.expand(line, border=16, fill=255)
            lines.append(pytesseract.image_to_string(line, config="--psm 7", timeout=60).strip())
        return "\n".join(lines)
    if img.width < 1800:
        scale = min(4, 1800 / img.width)
        img = img.resize((int(img.width * scale), int(img.height * scale)), Image.Resampling.LANCZOS)
    img = ImageOps.autocontrast(img)
    try:
        return pytesseract.image_to_string(img, config="--psm 6 -c preserve_interword_spaces=1", timeout=60)
    except pytesseract.TesseractNotFoundError:
        raise RuntimeError(
            "Tesseract OCR is not installed, but images and scanned PDFs need it. Windows: download the "
            "installer from https://github.com/UB-Mannheim/tesseract/wiki and install to the default folder "
            "(C:\\Program Files\\Tesseract-OCR), then run again. macOS: brew install tesseract. "
            "Ubuntu: sudo apt install tesseract-ocr. Installed somewhere else? Set the TESSERACT_CMD "
            "environment variable to the full path of tesseract.exe.") from None


def extract_pdf_text(path: str, password: Optional[str] = None) -> str:
    """Use the PDF text layer; OCR any page that has (almost) no text (scanned PDFs)."""
    import pdfplumber

    pages = []
    with pdfplumber.open(path, password=password) as pdf:
        for page in pdf.pages:
            text = page.extract_text() or ""
            if len(text.strip()) < 30:
                text = ocr_image(page.to_image(resolution=300).original)
            pages.append(text)
    return "\n".join(pages)


def extract_text(path: str, password: Optional[str] = None) -> tuple[str, str]:
    """Return (text, kind) where kind is 'pdf' | 'image' | 'text'."""
    ext = Path(path).suffix.lower()
    if ext == ".pdf":
        return extract_pdf_text(path, password), "pdf"
    if ext in IMAGE_EXTS:
        from PIL import Image

        return ocr_image(Image.open(path)), "image"
    return Path(path).read_text(encoding="utf-8", errors="ignore"), "text"


# =============================================================================
# 2. REGEX BUILDING BLOCKS
# =============================================================================
MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec"
DATE_PATTERN = (
    r"\d{4}-\d{2}-\d{2}"                                  # 2025-09-02
    r"|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}"                   # 02/09/2025, 02-09-25
    rf"|\d{{1,2}}[-/\s]?(?:{MONTHS})[a-z]*[-/,\s]*\d{{2,4}}"  # 02 Sep 2025, 02-Sep-25, 02Sep25
)
DATE_RE = re.compile(rf"(?<![\d/.-])(?:{DATE_PATTERN})(?![\d/])", re.I)
LINE_START_DATE_RE = re.compile(rf"^\s*(?:{DATE_PATTERN})(?![\d/])", re.I)
# Exported SMS logs: every message starts with a bracketed sender or timestamp, then the date.
_OPEN, _CLOSE = r"[\[\(\{\|]", r"[\]\)\}\|]"
SMS_LOG_START_RE = re.compile(
    r"(?:" + _OPEN + r"[^\]\)\}\|\n]{1,30}" + _CLOSE + r"\s*|" + _OPEN + r"\s*)(?:" + DATE_PATTERN + r")(?![\d/])", re.I)
RECEIPT_HINT_RE = re.compile(
    r"\b(grand total|total amount|amount paid|tax invoice|invoice|receipt|cash memo|bill no|sub ?total)\b", re.I)

DATE_FORMATS = [
    "%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y", "%d/%m/%y", "%d-%m-%y", "%d.%m.%y",
    "%d %b %Y", "%d-%b-%Y", "%d %b %y", "%d-%b-%y", "%d%b%y", "%d%b%Y",
]

PREFIXED_AMT_RE = re.compile(r"(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)", re.I)
PLAIN_AMT_RE = re.compile(r"(?<![\w.,/-])(\d[\d,]*\.\d{2})(?!\d)")
KEYWORD_AMT_RE = re.compile(
    r"\b(?:debited|credited|spent|paid|sent|withdrawn|received|deposited)\s+(?:by|of|with|for|using)?\s*"
    r"(?:rs\.?|inr|\u20b9)?\s*([\d,]+(?:\.\d{1,2})?)", re.I)
BALANCE_RE = re.compile(r"\bbal(?:ance)?\b[^\d]{0,12}([\d,]+(?:\.\d{1,2})?)", re.I)

DEBIT_RE = re.compile(
    r"\b(debited|debit|withdrawn|withdrawal|spent|paid|purchase|sent|transferred to|trf to|dr)\b", re.I)
CREDIT_RE = re.compile(
    r"\b(credited|credit|received|deposited|deposit|refund(?:ed)?|reversal|cr)\b", re.I)
PAST_TXN_RE = re.compile(r"\b(debited|credited|withdrawn|deposited|spent|paid|received)\b", re.I)

OTP_RE = re.compile(r"\b(otp|one[- ]time (?:password|pin)|verification code|do not share|never share)\b", re.I)
PROMO_RE = re.compile(
    r"\b(offer|discount|apply now|pre-?approved|limited period|t&c|terms and conditions|"
    r"congratulations|loan up ?to|reward points|exclusive|upgrade now|win|\d+%\s*off|use code|coupon|promo code)\b",
    re.I)
BAL_WORD_RE = re.compile(r"\bbal(?:ance)?\b", re.I)
BALANCE_ROW_RE = re.compile(r"\b(opening|closing) balance\b|\bbrought forward\b|\bcarried forward\b", re.I)
NOISE_LINE_RE = re.compile(
    r"page\s+\d+\s*(?:of|/)\s*\d+|computer[- ]generated|statement of account|toll[- ]free|"
    r"customer care|registered office|^\s*date\s+(?:narration|particulars|description|transaction)",
    re.I)


SMS_STOP = (r"(?:\s+(?:on|ref(?:no|erence)?|via|using|avl|bal|upi|a/c|acct|if|call|not|txn|trf|vpa|at|dated|"
            r"from|info|id|utr|imps)\b|[.,;|(]|\s-\s|$)")
SMS_MERCHANT_RE = re.compile(
    r"\b(?:to|at|towards|from|by|on|info[:\-])\s+(?:vpa\s+)?([A-Za-z0-9@&'*_. \-]{2,50}?)(?=" + SMS_STOP + ")",
    re.I)
# Reject "merchants" that are really your own bank/card/account, a date, or filler words.
BAD_MERCHANT_RE = re.compile(
    rf"^(?:(?:your|a/?c|acct|account|the|xx|ref|upi|imps|neft|rtgs|vpa|rs|inr|date|mobile|linked)\b"
    rf"|\d{{1,2}}[-/\s]?(?:{MONTHS})[a-z]*\b)|\b(?:bank|card)\b", re.I)
ORDER_MERCHANT_RES = [
    re.compile(r"\bfor\s+(?:your\s+)?([A-Za-z][A-Za-z0-9&'. ]{1,30}?)\s+(?:order|ride|trip|booking|recharge|subscription|bill)\b", re.I),
    re.compile(r"\byour\s+([A-Za-z][A-Za-z0-9&']{1,20})\s+(?:order|ride|trip|booking)\b", re.I),
]
# Indian transactional SMS sender IDs ("VM-SWIGGY", "JM-PHRMEZ" ...). Only used when the message text never
# names the shop. Bank senders are deliberately NOT listed. Add your own as needed.
SENDER_TAG_RE = re.compile(r"^\s*" + _OPEN + r"[A-Za-z]{2}-([A-Za-z0-9]{3,10})" + _CLOSE)
SENDER_ALIASES = {
    "PHRMEZ": "PharmEasy", "BLINKI": "Blinkit", "BLINKT": "Blinkit", "UBERIN": "Uber", "SWIGGY": "Swiggy",
    "ZOMATO": "Zomato", "AMAZON": "Amazon", "FLPKRT": "Flipkart", "FLIPKT": "Flipkart", "NTFLIX": "Netflix",
    "OLACAB": "Ola", "RAPIDO": "Rapido", "ZEPTON": "Zepto", "BIGBSK": "BigBasket", "MYNTRA": "Myntra",
    "APOLLO": "Apollo", "NETMDS": "Netmeds", "IRCTCI": "IRCTC", "MKMYTR": "MakeMyTrip",
}
ORDER_STOP = {"your", "next", "the", "this", "a", "an", "my", "new", "first", "latest", "last", "recent"}

NARR_NOISE = {"upi", "neft", "imps", "rtgs", "pos", "ach", "nach", "ecs", "atm", "ib", "mb", "p2a",
              "p2m", "p2p", "payment", "txn", "ft", "mmt", "bil", "bilpay", "by", "to"}
NARR_PREFIX_RE = re.compile(r"^(?:(?:upi|neft|imps|rtgs|pos|ach|nach|ecs|atm|wdl|ib|mb)\b[\s:/-]*)+", re.I)
MASKED_NUM_RE = re.compile(r"\b\d{4}[xX*]{2,}\d{2,4}\b|\b\d{6,}\b")


# =============================================================================
# 3. SMALL HELPERS
# =============================================================================
def to_float(s: str) -> float:
    return float(s.replace(",", ""))


def parse_date(s: str) -> Optional[str]:
    """Return ISO yyyy-mm-dd or None. Indian day-first order is assumed."""
    s = re.sub(r"[,\s]+", " ", s.strip()).title().replace("Sept", "Sep")
    s = re.sub(r"([A-Za-z]{3})[a-z]+", r"\1", s)
    if re.search(r"[A-Za-z]", s):
        s = s.replace("/", "-")
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            continue
    return None


def norm_text(s: str) -> str:
    return re.sub(r"\s+", " ", s.lower()).strip()


def norm_merchant(s: str) -> str:
    s = re.sub(r"[^a-z0-9 ]", " ", s.lower())
    drop = {"upi", "to", "from", "pvt", "ltd", "india", "limited", "payment", "pay"}
    return " ".join(w for w in s.split() if w not in drop)


def similar(a: str, b: str) -> float:
    a, b = norm_merchant(a), norm_merchant(b)
    if not a or not b:
        return 0.0
    if len(min(a, b, key=len)) >= 3 and (a in b or b in a):
        return 1.0
    return SequenceMatcher(None, a, b).ratio()


MERCHANT_CONFIRMS_RE = re.compile(r"\b(?:payment|amount)\b.{0,40}?\breceived\s+for\b", re.I)


def keyword_direction(text: str) -> Optional[str]:
    """Earliest debit/credit keyword wins. 'credit card' / 'debit card' are ignored.
    'Payment of Rs X received for order ...' is a shop confirming YOUR payment -> debit."""
    if MERCHANT_CONFIRMS_RE.search(text):
        return "debit"
    t = re.sub(r"\b(credit|debit)\s+card\b", " card ", text, flags=re.I)
    d, c = DEBIT_RE.search(t), CREDIT_RE.search(t)
    if d and c:
        return "debit" if d.start() < c.start() else "credit"
    return "debit" if d else "credit" if c else None


# =============================================================================
# 4. TEXT SPLITTER  (regex date-boundary)
# =============================================================================
def split_records(text: str) -> list[str]:
    """
    * SMS log (each message starts "[SENDER] date ..." ): one record per message, wrapped lines joined.
    * Statement (each row starts with a date): the date opens a record, following lines (wrapped
      narration) are glued to it. Titles/headers/footers before the first record are dropped.
    * Otherwise (plain pasted SMS): split on blank lines, else one per line.
    """
    lines = [re.sub(r"\s{6,}\S{1,2}\s*$", "", l) for l in text.splitlines() if not NOISE_LINE_RE.search(l)]
    lines = [re.sub(r"^\s*[^\w\s]\s{6,}(?=\d)", "", l) for l in lines]
    lines = [l[match.start():] if (match := SMS_LOG_START_RE.search(l)) else l for l in lines]
    # Isolated viewer toolbar glyphs are not message continuations.
    lines = [l for l in lines if not re.fullmatch(r"\s*[^\d\s]{1,2}\s*", l)]
    sms_starts = [i for i, l in enumerate(lines) if SMS_LOG_START_RE.search(l)]
    stmt_starts = [i for i, l in enumerate(lines) if LINE_START_DATE_RE.match(l)]
    starts = sms_starts or stmt_starts
    if starts:
        bounds = starts + [len(lines)]
        return [" ".join(l.strip() for l in lines[a:b] if l.strip()) for a, b in zip(bounds, bounds[1:])]
    records, current = [], []
    for line in lines:
        line = line.strip()
        if not line:
            if current:
                records.append(" ".join(current)); current = []
            continue
        # A new independently actionable message starts a record; continuation lines
        # (reference, balance, wrapped date, merchant) remain with their parent SMS.
        new_message = (bool(PREFIXED_AMT_RE.search(line) or KEYWORD_AMT_RE.search(line))
                       and bool(PAST_TXN_RE.search(line)))
        if current and new_message and PAST_TXN_RE.search(" ".join(current)):
            records.append(" ".join(current)); current = []
        current.append(line)
    if current:
        records.append(" ".join(current))
    return records


def detect_mode(text: str, kind: str) -> str:
    """sms | statement | receipt.  A single-receipt guess now needs receipt-like wording, so an
    unrecognised multi-message document is never collapsed into one fake transaction."""
    lines = text.splitlines()
    if any(SMS_LOG_START_RE.search(l) for l in lines):
        return "sms"
    if any(LINE_START_DATE_RE.match(l) for l in lines):
        return "statement"
    if kind in ("image", "pdf") and RECEIPT_HINT_RE.search(text):
        return "receipt"
    return "sms"


# =============================================================================
# 5. PARSING (rule based)  ->  Transaction
# =============================================================================
@dataclass
class Transaction:
    date: Optional[str]        # ISO yyyy-mm-dd
    amount: float              # debit -> negative, credit -> positive
    currency: str
    direction: str             # debit | credit | unknown
    merchant: str
    category: str
    description: str           # raw text of the record
    source: str                # file name / 'sms'
    value_date: Optional[str] = None
    debit: Optional[float] = None
    credit: Optional[float] = None
    ref_no: Optional[str] = None
    balance: Optional[float] = None
    needs_review: bool = False
    hash: str = ""
    possible_duplicate_of: Optional[str] = None   # hash of an earlier row that is probably the same payment


class ParseContext:
    """Carries the running balance so statement rows can be classified debit/credit."""
    def __init__(self, prev_balance: Optional[float] = None):
        self.prev_balance = prev_balance


def extract_ref(text: str) -> Optional[str]:
    """Only labelled or payment-path references; never guess an account/phone number."""
    patterns = [
        r"\b(?:ref(?:erence)?\s*(?:no\.?|number|id)?|utr|rrn|txn\s*id|transaction\s*id|upi\s*(?:no\.?|id))\s*[:#./-]?\s*([A-Za-z0-9*][A-Za-z0-9*/-]{3,34})",
        r"\b(?:imps|neft|rtgs)\s*[:/-]\s*([A-Za-z0-9][A-Za-z0-9/-]{5,34})",
        r"\bUPI/(?:DR/|CR/)?([0-9]{8,22})(?:/|\b)",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, text, re.I):
            candidate = match.group(1).strip('./-')
            if any(c.isdigit() for c in candidate):
                return candidate.upper()
    return None


def complete_ref(ref):
    return bool(ref) and not re.search(r"[*]|X{2,}", ref, re.I)


def parse_statement_table(table, source):
    """Map header names to fields, keeping blank debit/credit cells meaningful."""
    headers = [norm_text(c or '') for c in table[0]]
    def column(*names):
        return next((i for i, h in enumerate(headers) if any(n in h for n in names)), None)
    mapping = dict(date=column('txn date', 'transaction date', 'date'),
                   value_date=column('value date'), description=column('description', 'narration', 'particular'),
                   ref_no=column('ref', 'cheque', 'chq'), debit=column('debit', 'withdrawal'),
                   credit=column('credit', 'deposit'), balance=column('balance'))
    def money(value):
        value = re.sub(r"(?:rs\.?|inr|₹|\s|,)", '', value, flags=re.I)
        if not re.search(r'[A-Za-z0-9]', value):
            return None
        # OCR can render thousands commas as dots. Only normalize a complete
        # grouped-money pattern, retaining the final two decimal digits.
        if re.fullmatch(r"\d{1,3}(?:\.\d{2,3})+\.\d{2}", value):
            whole, fraction = value.rsplit('.', 1)
            value = whole.replace('.', '') + '.' + fraction
        if not re.fullmatch(r"-?\d+(?:\.\d{1,2})?", value):
            raise ValueError('unreadable amount')
        return float(value)
    records, candidates = [], []
    for row in table[1:]:
        cells = [' '.join(str(c or '').split()) for c in row]
        raw = ' | '.join(cells)
        if not raw.strip(' |'):
            continue
        records.append(raw)
        fields = {k: cells[i] if i is not None and i < len(cells) else '' for k, i in mapping.items()}
        try:
            debit, credit, balance = (money(fields[k]) for k in ('debit', 'credit', 'balance'))
            if not debit and not credit:
                candidates.append((None, 'no_transaction_amount')); continue
            if debit and credit:
                candidates.append((None, 'ambiguous_debit_credit')); continue
            direction = 'debit' if debit else 'credit'
            date = parse_date(fields['date'])
            ref = fields['ref_no'] or extract_ref(fields['description'])
            if mapping['ref_no'] is not None and fields['ref_no']:
                raw_ref = str(row[mapping['ref_no']] or '')
                parts = []
                for line in raw_ref.splitlines():
                    line = re.sub(r"TRANSFER\s+(?:TO|FROM)", '', line, flags=re.I).strip()
                    if not line:
                        continue
                    # A line-wrapped final digit belongs to the preceding identifier.
                    if len(line) == 1 and line.isdigit() and parts:
                        parts[-1] += line
                    else:
                        parts.append(re.sub(r"(?<=[A-Z0-9])\s+(?=[0-9])", '', line))
                ref = ' '.join(dict.fromkeys(parts))
            # SBI cells may include TRANSFER TO/FROM and duplicate wrapped IDs.
            if ref:
                ref = re.sub(r"TRANSFER\s+(?:TO|FROM)\s*", '', ref, flags=re.I).strip()
                ref = re.sub(r"(?<=\d)\s+(?=\d(?:\s|$))", '', ref)
            txn = Transaction(date=date, value_date=parse_date(fields['value_date']),
                amount=-(debit or 0) if debit else credit, debit=debit or None, credit=credit or None,
                currency='INR', direction=direction, merchant=clean_narration(fields['description']),
                category='Uncategorized', description=fields['description'], source=source,
                ref_no=ref or None, balance=balance, needs_review=not bool(date))
            candidates.append((txn, ''))
        except ValueError:
            candidates.append((None, 'unreadable_table_amount'))
    return records, candidates


def extract_amounts(rec: str, mode: str) -> tuple[Optional[float], Optional[float]]:
    """Return (transaction amount, balance-after)."""
    work = DATE_RE.sub(" ", rec)
    if mode == "sms":
        balance = None
        m = BALANCE_RE.search(work)
        if m:
            balance = to_float(m.group(1))
            work = work[:m.start()] + " " + work[m.end():]
        vals = [to_float(x) for x in PREFIXED_AMT_RE.findall(work)] or \
               [to_float(x) for x in KEYWORD_AMT_RE.findall(work)] or \
               [to_float(x) for x in PLAIN_AMT_RE.findall(work)]
        return (vals[0] if vals else None), balance
    vals = [to_float(x) for x in PLAIN_AMT_RE.findall(work)] or \
           [to_float(x) for x in PREFIXED_AMT_RE.findall(work)]
    if len(vals) >= 2:                      # ... <txn amount> <balance>
        return vals[-2], vals[-1]
    return (vals[0] if vals else None), None


def clean_narration(rec: str) -> str:
    """Best-effort merchant/counterparty from a statement narration."""
    s = DATE_RE.sub(" ", rec)
    s = PLAIN_AMT_RE.sub(" ", s)
    s = PREFIXED_AMT_RE.sub(" ", s)
    s = re.sub(r"\b(?:dr|cr)\b\.?", " ", s, flags=re.I)
    for part in re.split(r"[/|-]", s):
        p = NARR_PREFIX_RE.sub("", part.strip())
        p = MASKED_NUM_RE.sub(" ", p)
        p = re.sub(r"\s+", " ", p).strip(" :.")
        if "@" in p or p.lower() in NARR_NOISE or not re.search(r"[A-Za-z]{2,}", p):
            continue
        return p[:50]
    return ""


def extract_merchant(rec: str, mode: str) -> str:
    if mode != "sms":
        return clean_narration(rec)
    pos = 0
    while True:                       # overlapping search: a rejected "on <date> to SHOP" must not hide "to SHOP"
        m = SMS_MERCHANT_RE.search(rec, pos)
        if not m:
            break
        cand = m.group(1).strip(" .-")
        if len(re.findall(r"[A-Za-z]", cand)) >= 3 and not BAD_MERCHANT_RE.search(cand):
            return cand
        pos = m.start() + 1
    for rx in ORDER_MERCHANT_RES:                     # e.g. "paid for Swiggy Order", "Your Uber trip"
        m = rx.search(rec)
        if m and m.group(1).strip().lower() not in ORDER_STOP:
            return m.group(1).strip()
    sender = SENDER_TAG_RE.match(rec)                 # e.g. "[JM-PHRMEZ] ..." -> PharmEasy
    return SENDER_ALIASES.get(sender.group(1).upper(), "") if sender else ""


def parse_receipt(text: str, source: str) -> Optional[Transaction]:
    """Photo of a bill/receipt: always a spend (debit). Take the grand total."""
    flat = " ".join(text.split())
    amount = None
    for pat in (r"grand\s*total", r"net\s*(?:amount|payable)", r"amount\s*paid", r"\btotal(?:\s*amount)?\b", r"\bamount\b"):
        found = re.findall(pat + r"\D{0,15}?([\d,]+\.\d{1,2}|[\d,]{2,})", flat, flags=re.I)
        if found:
            amount = to_float(found[-1])
            break
    if amount is None:
        vals = [to_float(x) for x in PLAIN_AMT_RE.findall(flat)]
        amount = max(vals) if vals else None
    if not amount:
        return None
    merchant = ""
    for line in text.splitlines():
        if len(re.findall(r"[A-Za-z]", line)) >= 3 and not re.search(r"invoice|receipt|bill|cash memo|gstin|tax", line, re.I):
            merchant = line.strip()[:50]
            break
    dm = DATE_RE.search(flat)
    return Transaction(
        date=parse_date(dm.group(0)) if dm else None, amount=-abs(amount), currency="INR",
        direction="debit", merchant=merchant, category="Uncategorized", description=flat,
        source=source, ref_no=extract_ref(flat), needs_review=True)


def parse_record(rec: str, mode: str, source: str, ctx: ParseContext) -> tuple[Optional[Transaction], str]:
    """Pre-filter + parse one record. Returns (transaction, '') or (None, discard_reason)."""
    has_past = bool(PAST_TXN_RE.search(rec))

    # ---- PRE-FILTERING ENGINE: discard non-financial text ------------------
    if OTP_RE.search(rec) and not has_past:
        return None, "otp"
    if PROMO_RE.search(rec) and not has_past:
        return None, "promo"
    if mode == "statement" and BALANCE_ROW_RE.search(rec):
        amt, bal = extract_amounts(rec, mode)
        if "opening" in rec.lower() and (bal or amt):
            ctx.prev_balance = bal or amt
        return None, "balance_row"
    if mode == "sms" and BAL_WORD_RE.search(rec) and not keyword_direction(rec):
        return None, "balance_alert"

    amount, balance = extract_amounts(rec, mode)
    if amount is None:
        return None, "no_amount"

    # ---- DIRECTION: balance delta (statements) -> keywords ----------------
    direction = None
    if balance is not None and ctx.prev_balance is not None:
        if abs(ctx.prev_balance + amount - balance) < 0.01:
            direction = "credit"
        elif abs(ctx.prev_balance - amount - balance) < 0.01:
            direction = "debit"
    direction = direction or keyword_direction(rec)
    if balance is not None:
        ctx.prev_balance = balance
    if direction is None:
        if mode == "sms":
            return None, "not_a_debit_or_credit"
        direction = "unknown"

    dm = DATE_RE.search(rec)
    signed = -amount if direction == "debit" else amount          # Debit * -1, Credit * +1
    return Transaction(
        date=parse_date(dm.group(0)) if dm else None, amount=round(signed, 2), currency="INR",
        direction=direction, merchant=extract_merchant(rec, mode), category="Uncategorized",
        description=rec, source=source, ref_no=extract_ref(rec), balance=balance,
        needs_review=(direction == "unknown")), ""


# =============================================================================
# 6. CATEGORISATION  (keyword rules, optional Claude fallback)
# =============================================================================
DEBIT_RULES = [
    ("Food & Dining", ["swiggy", "zomato", "dominos", "domino's", "mcdonald", "mcdonalds", "kfc", "burger king",
                       "pizza", "restaurant", "cafe", "starbucks", "chaayos", "chai", "coffee", "biryani", "bakery", "dunzo"]),
    ("Groceries", ["bigbasket", "blinkit", "zepto", "grofers", "dmart", "d-mart", "reliance fresh", "jiomart",
                   "instamart", "supermarket", "grocery", "kirana"]),
    ("Travel", ["irctc", "makemytrip", "goibibo", "redbus", "indigo", "air india", "vistara", "spicejet",
                "cleartrip", "yatra", "oyo", "airbnb", "railway"]),
    ("Transport", ["uber", "ola", "rapido", "metro", "fastag", "petrol", "fuel", "iocl", "hpcl", "bpcl",
                   "indian oil", "parking", "toll"]),
    ("Shopping", ["amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "decathlon",
                  "ikea", "shoppers stop", "snapdeal", "lifestyle"]),
    ("Bills & Utilities", ["electricity", "cesc", "bescom", "tata power", "adani electricity", "msedcl", "water bill",
                           "piped gas", "jio", "airtel", "vodafone", "vodafone idea", "bsnl", "act fibernet",
                           "broadband", "recharge", "dth", "tata play", "dish tv", "postpaid", "bbps", "billdesk"]),
    ("Entertainment", ["netflix", "spotify", "hotstar", "disney", "prime video", "youtube", "bookmyshow", "pvr",
                       "inox", "gaana", "jiocinema", "sony liv", "zee5", "steam", "playstation"]),
    ("Health", ["pharmacy", "pharmeasy", "1mg", "netmeds", "apollo", "medplus", "hospital", "clinic", "diagnostic",
                "lab", "doctor", "dental", "practo", "medical"]),
    ("Education", ["school", "college", "university", "tuition", "udemy", "coursera", "byju", "byjus", "unacademy",
                   "upgrad", "exam fee", "coaching"]),
    ("Rent & Housing", ["rent", "maintenance", "society", "nobroker", "landlord"]),
    ("Insurance", ["lic", "insurance", "premium", "hdfc life", "icici pru", "star health", "policybazaar", "acko"]),
    ("Investments", ["zerodha", "groww", "upstox", "mutual fund", "sip", "kuvera", "ppf", "nps", "indmoney",
                     "smallcase", "angel one"]),
    ("Cash Withdrawal", ["atm", "cash withdrawal", "cash wdl", "nwd", "self withdrawal"]),
    ("Fees & Charges", ["charges", "fee", "gst", "penalty", "annual fee", "sms charges", "amc", "min bal"]),
    ("Loans & Credit Cards", ["emi", "loan", "credit card", "cc payment", "cred", "mycard", "rblmycard", "bajaj finance", "bajaj finserv"]),
    ("Transfers", ["neft", "imps", "rtgs", "upi", "transfer", "trf"]),
]
CREDIT_RULES = [
    ("Salary", ["salary", "payroll", "sal credit"]),
    ("Interest", ["interest", "int pd", "int.pd", "int credit"]),
    ("Refund / Cashback", ["refund", "reversal", "cashback", "chargeback"]),
    ("Dividend", ["dividend"]),
]
ALL_CATEGORIES = [c for c, _ in CREDIT_RULES + DEBIT_RULES] + ["Uncategorized"]


def _compile(rules):
    return [(cat, re.compile(r"\b(?:" + "|".join(re.escape(k) for k in kws) + r")\b", re.I)) for cat, kws in rules]


_CREDIT, _DEBIT = _compile(CREDIT_RULES), _compile(DEBIT_RULES)


class LLMCategorizer:
    """Optional fallback for rows the keyword rules cannot place (needs `pip install anthropic`
    and ANTHROPIC_API_KEY). Account/ref numbers are masked before sending."""
    def __init__(self, model: str = "claude-haiku-4-5-20251001"):
        import anthropic
        self.client, self.model, self.cache = anthropic.Anthropic(), model, {}

    def __call__(self, text: str, direction: str) -> str:
        safe = re.sub(r"\d{6,}", "#", text)[:300]
        if safe in self.cache:
            return self.cache[safe]
        msg = self.client.messages.create(
            model=self.model, max_tokens=20, temperature=0,
            system="Classify a bank transaction. Reply with exactly one label from: "
                   + ", ".join(ALL_CATEGORIES) + ". No other text.",
            messages=[{"role": "user", "content": f"Direction: {direction}\nTransaction: {safe}"}])
        out = msg.content[0].text.strip()
        self.cache[safe] = out if out in ALL_CATEGORIES else "Uncategorized"
        return self.cache[safe]


def categorize(merchant: str, description: str, direction: str, llm=None) -> str:
    text = f"{merchant} {description}"
    rules = (_CREDIT + _DEBIT) if direction == "credit" else _DEBIT
    for cat, rx in rules:
        if rx.search(text):
            return cat
    if direction == "credit":
        return "Transfers" if re.search(r"\b(upi|neft|imps|rtgs)\b", text, re.I) else (
            llm(text, direction) if llm else "Uncategorized")
    return llm(text, direction) if llm else "Uncategorized"


# =============================================================================
# 7. DEDUPLICATION  (SHA-256 exact + fuzzy cross-source)
# =============================================================================
def make_hash(date, amount, merchant, raw_text, occurrence=0) -> str:
    base = f"{date or ''}|{amount:.2f}|{norm_merchant(merchant)}|{norm_text(raw_text)}|{occurrence}"
    return hashlib.sha256(base.encode()).hexdigest()


def _days_apart(a: Optional[str], b: Optional[str]) -> Optional[int]:
    if not a or not b:
        return None
    return abs((datetime.fromisoformat(a) - datetime.fromisoformat(b)).days)


# =============================================================================
# 8. PIPELINE
# =============================================================================
@dataclass
class PipelineResult:
    transactions: list
    duplicates: list
    discarded: list

    def summary(self) -> str:
        why = Counter(d["reason"] for d in self.discarded)
        dup = Counter(d["reason"] for d in self.duplicates)
        cats = Counter(t.category for t in self.transactions)
        flagged = sum(bool(t.possible_duplicate_of) for t in self.transactions)
        return (f"kept={len(self.transactions)}  duplicates={len(self.duplicates)} {dict(dup)}  "
                f"discarded={len(self.discarded)} {dict(why)}  possible_duplicates_flagged={flagged}\n"
                f"categories: {dict(cats)}")


class TransactionPipeline:
    def __init__(self, store_path: Optional[str] = "transactions_store.json",
                 llm_categorizer=None, pdf_password: Optional[str] = None):
        self.store_path, self.llm, self.pdf_password = store_path, llm_categorizer, pdf_password
        self.store: list[dict] = []          # every accepted transaction, persisted across uploads
        self.seen: set[str] = set()
        if store_path and Path(store_path).exists():
            self.store = json.loads(Path(store_path).read_text(encoding="utf-8"))
            self.seen = {t["hash"] for t in self.store}

    # -- public entry points --------------------------------------------------
    def process_file(self, path: str) -> PipelineResult:
        ext = Path(path).suffix.lower()
        if ext not in SUPPORTED_EXTS:
            raise ValueError(f"Unsupported file type: {ext}")
        if ext == '.txt':
            return self._run(Path(path).read_text(encoding='utf-8-sig'), 'text', Path(path).name)
        from statement_extraction import document_sections
        # Extract first, then commit the whole file to duplicate memory. A failed
        # later page must not make already-read rows disappear on the next retry.
        sections = []
        for text, kind, table in document_sections(path, self.pdf_password, ocr_image):
            if (not table and detect_mode(text, kind) != 'receipt' and sections
                    and not sections[-1][2] and detect_mode(sections[-1][0], sections[-1][1]) != 'receipt'):
                prior, prior_kind, _ = sections.pop()
                sections.append((prior + '\n' + text, 'image' if 'image' in (kind, prior_kind) else kind, None))
            else:
                sections.append((text, kind, table))
        batch = TransactionPipeline(None, self.llm, self.pdf_password)
        batch.store, batch.seen = list(self.store), set(self.seen)
        result = PipelineResult([], [], [])
        occurrences = Counter()
        for text, kind, table in sections:
            part = batch._run(text, kind, Path(path).name, table, occurrences)
            result.transactions.extend(part.transactions)
            result.duplicates.extend(part.duplicates)
            result.discarded.extend(part.discarded)
        if self.store_path:
            Path(self.store_path).parent.mkdir(parents=True, exist_ok=True)
            Path(self.store_path).write_text(json.dumps(batch.store, indent=2, ensure_ascii=False), encoding='utf-8')
        self.store, self.seen = batch.store, batch.seen
        return result

    def process_sms(self, text: str, source: str = "sms") -> PipelineResult:
        return self._run(text, "text", source)

    # -- internals ------------------------------------------------------------
    def _run(self, text: str, kind: str, source: str, table=None, occurrences=None) -> PipelineResult:
        mode = detect_mode(text, kind)
        kept, dups, dropped = [], [], []

        if table:
            mode = "statement"
            records, candidates = parse_statement_table(table, source)
        elif mode == "receipt":
            t = parse_receipt(text, source)
            candidates = [(t, "")] if t else [(None, "no_amount")]
            records = [text]
        else:
            records = split_records(text)
            ctx = ParseContext()
            if mode == "statement":
                m = re.search(r"opening balance\D{0,15}([\d,]+\.\d{2})", text, re.I)
                if m:
                    ctx.prev_balance = to_float(m.group(1))
            candidates = [parse_record(r, mode, source, ctx) for r in records]

        occurrences = Counter() if occurrences is None else occurrences
        matched = set()
        for rec, (txn, reason) in zip(records, candidates):
            if txn is None:
                dropped.append({"reason": reason, "text": rec, "source": source})
                continue
            if txn.direction == "debit":
                txn.debit = abs(txn.amount)
            elif txn.direction == "credit":
                txn.credit = abs(txn.amount)
            txn.needs_review |= kind == "image" or not txn.date or (bool(txn.ref_no) and not complete_ref(txn.ref_no))
            txn.category = categorize(txn.merchant, txn.description, txn.direction, self.llm)

            hash_text = txn.description + (f" | ref={txn.ref_no}" if txn.ref_no else "")
            key = (txn.date, txn.amount, norm_merchant(txn.merchant), norm_text(hash_text))
            occ = 0 if mode == "sms" else occurrences[key]   # statements legitimately repeat rows
            occurrences[key] += 1
            txn.hash = make_hash(txn.date, txn.amount, txn.merchant, hash_text, occ)

            verdict = self._find_duplicate(txn, mode, matched)
            if verdict:
                dups.append({"reason": verdict[0], "duplicate_of": verdict[1], "transaction": asdict(txn)})
                continue
            twin = self._find_possible_duplicate(txn, matched)
            if twin:                                  # keep it, but ask the human to look
                txn.possible_duplicate_of, txn.needs_review = twin, True
            self.store.append(asdict(txn))
            self.seen.add(txn.hash)
            kept.append(txn)

        if self.store_path:
            Path(self.store_path).parent.mkdir(parents=True, exist_ok=True)
            Path(self.store_path).write_text(json.dumps(self.store, indent=2, ensure_ascii=False), encoding="utf-8")
        return PipelineResult(kept, dups, dropped)

    def _find_possible_duplicate(self, t: Transaction, matched: set) -> Optional[str]:
        """Weaker evidence than _find_duplicate, so the row is only FLAGGED, never dropped:
        same amount to the paisa, date within 4 days (card spends often post
        1-3 days after the SMS), and the merchant is either similar or missing on one side
        (e.g. a salary SMS that never names the employer)."""
        for e in self.store:
            if e["hash"] in matched or abs(e["amount"] - t.amount) > 0.005:
                continue
            if complete_ref(t.ref_no) and complete_ref(e.get("ref_no")) and t.ref_no != e["ref_no"]:
                continue
            gap = _days_apart(t.date, e["date"])
            if gap is None or gap > 4:
                continue
            if similar(t.merchant, e["merchant"]) >= 0.6 or not t.merchant or not e["merchant"]:
                matched.add(e["hash"])
                return e["hash"]
        return None

    def _find_duplicate(self, t: Transaction, mode: str, matched: set) -> Optional[tuple[str, str]]:
        # 1) exact: same SHA-256 (same file re-uploaded, same SMS pasted twice, ...)
        if t.hash in self.seen:
            return "exact", t.hash
        for e in self.store:
            if abs(e["amount"] - t.amount) > 0.005:
                continue
            same_source = e["source"] == t.source
            same_ref = complete_ref(t.ref_no) and t.ref_no == e.get("ref_no")
            # 2) same bank/UPI reference number + same amount = same transaction. Reference numbers are
            #    unique, so this also catches two different SMS alerts for one payment. Inside a
            #    statement/receipt we skip it (rows there are never the same txn twice).
            if same_ref and (not same_source or mode == "sms"):
                return "probable", e["hash"]
            # Similar merchant/date/amount is insufficient to delete a payment.
            # Such rows are retained and flagged by _find_possible_duplicate.
        return None


# =============================================================================
# 9. CLI  (also what the VS Code "Run" button / launch.json configs call)
# =============================================================================
def expand_inputs(paths: list[str]) -> list[Path]:
    """Files are used as-is; folders are expanded to the PDFs / images / .txt files inside."""
    files: list[Path] = []
    for raw in paths:
        p = Path(raw)
        if p.is_dir():
            files += [f for f in p.iterdir() if f.is_file() and f.suffix.lower() in SUPPORTED_EXTS]
        else:
            files.append(p)
    # Statements first (they carry balances = most reliable), then receipts, then SMS. When the same
    # transaction shows up in several sources, the first one processed is the one that is kept.
    rank = {".pdf": 0, ".txt": 2}
    return sorted(files, key=lambda f: (rank.get(f.suffix.lower(), 1), f.name.lower()))


TABLE_COLUMNS = ('Date', 'Value Date', 'Description', 'Ref No./Cheque No.', 'Debit', 'Credit', 'Balance')


def table_rows(txns):
    return [[t.date or '', t.value_date or '', t.description, t.ref_no or '',
             '' if t.debit is None else f"{t.debit:.2f}",
             '' if t.credit is None else f"{t.credit:.2f}",
             '' if t.balance is None else f"{t.balance:.2f}"] for t in txns]


def write_csv(txns, path):
    with open(path, 'w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow(TABLE_COLUMNS)
        # Prevent spreadsheet formula execution from untrusted narration cells.
        for row in table_rows(txns):
            writer.writerow(["'" + v if v.startswith(('=', '+', '-', '@')) else v for v in row])


def print_table(txns):
    widths = (10, 10, 36, 24, 12, 12, 14)
    print(' | '.join(h.ljust(w) for h, w in zip(TABLE_COLUMNS, widths)))
    for row in table_rows(txns):
        print(' | '.join(v[:w].ljust(w) for v, w in zip(row, widths)))
    if any(t.needs_review for t in txns):
        print('Review flagged rows in JSON; CSV preserves the full narration and reference.')


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):          # avoid UnicodeEncodeError in Windows consoles
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(description="Extract, categorise and de-duplicate transactions.")
    ap.add_argument("inputs", nargs="*", help="PDF statements, receipt photos, .txt SMS dumps, or folders of them. "
                                              "With no inputs, everything in the uploads/ folder is processed.")
    ap.add_argument("--sms", action="append", default=[], help="raw SMS text (repeatable)")
    ap.add_argument("--out", default=str(OUTPUT_DIR / "transactions.json"), help="clean JSON array output")
    ap.add_argument("--csv", default=str(OUTPUT_DIR / "transactions.csv"), help="bank-style table output")
    ap.add_argument("--report", default=str(OUTPUT_DIR / "report.json"),
                    help="JSON listing duplicates + discarded rows for auditing ('' to skip)")
    ap.add_argument("--store", default=str(OUTPUT_DIR / "store.json"),
                    help="de-dup memory across runs ('' to disable)")
    ap.add_argument("--pdf-password", help="password for protected bank PDFs")
    ap.add_argument("--llm", action="store_true", help="use Claude Haiku for uncategorised rows (needs ANTHROPIC_API_KEY)")
    ap.add_argument("--mongo-uri", help="MongoDB connection string (or set MONGODB_URI). Enables Mongo I/O")
    ap.add_argument("--mongo-db", default="finplus", help="MongoDB database name (default: finplus)")
    ap.add_argument("--from-mongo", action="store_true", help="also read pending documents from the raw_inputs collection")
    ap.add_argument("--to-mongo", action="store_true", help="write accepted transactions + report to MongoDB")
    ap.add_argument("--user-id", help="tag/filter transactions by this user id in MongoDB")
    args = ap.parse_args(argv)

    inputs = list(args.inputs)
    if not inputs and not args.sms and not args.from_mongo:
        inputs = [str(UPLOADS_DIR)]
        if not expand_inputs(inputs):
            print(f"Nothing to process yet. Put PDF statements, receipt/SMS screenshots or .txt SMS dumps "
                  f"into:\n  {UPLOADS_DIR}\nthen run again.")
            return 0

    mongo = None
    if args.from_mongo or args.to_mongo:
        from mongo_io import MongoStore
        mongo = MongoStore(args.mongo_uri, args.mongo_db)
    store_path = None if mongo else (args.store or None)     # Mongo replaces store.json as duplicate memory
    pipe = TransactionPipeline(store_path, LLMCategorizer() if args.llm else None, args.pdf_password)
    if mongo:
        pipe.store = mongo.load_store(args.user_id)
        pipe.seen = {t["hash"] for t in pipe.store}
    kept, dups, dropped, errors = [], [], [], []
    for f in expand_inputs(inputs):
        if not f.exists():
            errors.append(f"{f}: file not found")
            continue
        try:
            r = pipe.process_file(str(f))
            print(f"read {f.name}: kept {len(r.transactions)}, duplicates {len(r.duplicates)}, discarded {len(r.discarded)}")
        except Exception as e:                        # one bad file must not stop the rest
            if "password" in repr(e).lower():              # pdfplumber wraps the real error
                msg = "PDF is password-protected - the password is missing or wrong (use --pdf-password)"
            else:
                msg = str(e) or type(e).__name__
            errors.append(f"{f.name}: {msg}")
            continue
        kept += r.transactions; dups += r.duplicates; dropped += r.discarded
    for sms in args.sms:
        r = pipe.process_sms(sms)
        kept += r.transactions; dups += r.duplicates; dropped += r.discarded

    if args.from_mongo:
        from mongo_io import temp_dir
        with temp_dir() as tmp:
            for doc in mongo.pending_inputs():
                try:
                    if doc.get("type") == "file":
                        pipe.pdf_password = doc.get("pdf_password") or args.pdf_password
                        r = pipe.process_file(str(mongo.materialize_file(doc, tmp)))
                    else:
                        r = pipe.process_sms(doc.get("text", ""), doc.get("source") or str(doc["_id"]))
                    kept += r.transactions; dups += r.duplicates; dropped += r.discarded
                    mongo.mark(doc["_id"], "processed", f"kept={len(r.transactions)}")
                except Exception as e:
                    errors.append(f"mongo:{doc['_id']}: {e}")
                    mongo.mark(doc["_id"], "error", str(e))

    if mongo and args.to_mongo:
        n = mongo.save_transactions(kept, args.user_id)
        mongo.save_report(dups, dropped, errors)
        print(f"-> MongoDB {args.mongo_db}: {n} transactions saved")

    for path in (args.out, args.report, args.csv):
        if path:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps([asdict(t) for t in kept], indent=2, ensure_ascii=False), encoding="utf-8")
    if args.csv:
        write_csv(kept, args.csv)
    if args.report:
        Path(args.report).write_text(
            json.dumps({"duplicates": dups, "discarded": dropped, "errors": errors}, indent=2, ensure_ascii=False), encoding="utf-8")

    print_table(kept)
    print("\n" + PipelineResult(kept, dups, dropped).summary())
    print(f"-> {args.out}" + (f"\n-> {args.report}" if args.report else ""))
    for e in errors:
        print(f"ERROR {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())