# FinPlus transaction extraction

Extract transactions from bank statements, receipt images, SMS screenshots and text logs.
Outputs JSON and a bank-style CSV with **Date, Value Date, Description, Ref No./Cheque No., Debit, Credit, Balance**.

## Setup (Windows)

Install Python 3.10+ and Tesseract OCR, then run:

```powershell
py -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Tesseract is only required for images and scanned PDFs. If it is not found automatically,
set `TESSERACT_CMD` to the full path of `tesseract.exe`. The usual Windows location is
`C:\Program Files\Tesseract-OCR\tesseract.exe`.

## Run

```powershell
python transaction_pipeline.py uploads --out output/transactions.json --csv output/transactions.csv
python transaction_pipeline.py bank.pdf --pdf-password YOUR_PASSWORD
python transaction_pipeline.py --sms "Rs 450 debited to SWIGGY on 03-Oct-26 Ref 427619384729"
```

Running without arguments processes `uploads/`. If it is empty, the program exits with a
message. **No demo runs and no synthetic transactions are injected.** The old demo PDF
generator has been removed. Existing `samples/` files remain as regression fixtures;
process them only when explicitly requested.

VS Code F5 configurations support the uploads folder, a selected file, a protected PDF,
and pasted SMS. Outputs default to `output/`:

- `transactions.json`: accepted records, including signed amount, debit/credit columns,
  reference, balance, category, review flag and possible-duplicate link.
- `transactions.csv`: full bank-style table, readable in Excel. Import the reference column
  as **Text** to preserve leading zeros and long identifiers. JSON always stores it as text.
- `report.json`: duplicates, rejected records with full extracted text and reasons, and file errors.
- `store.json`: local duplicate memory. Use `--store ""` for a stateless run.

## Extraction behavior

Ruled PDF tables retain their cells. Ruled statement images/scans are read cell by cell,
so blank debit/credit cells and the balance column are not confused. PDF pages are processed
individually; scanned bodies with a small text heading also trigger OCR. Multi-frame image
files are processed frame by frame. Unruled tables fall back to text/date-boundary parsing.

SMS logs retain wrapped reference numbers and dates. Supported labels include Ref/Refno,
Reference, UTR, RRN, UPI no., transaction ID and IMPS/NEFT/RTGS identifiers. Unlabelled
12-digit numbers are no longer assumed to be references. Masked references are retained
for display and review but are not used to prove duplicates.

Exact repeats and matching complete references/amounts are de-duplicated. Similar merchant,
date and amount alone only **flag** a possible duplicate; they do not delete a payment.
Different complete references are retained. OTPs, offers, balance-only messages and future
payment reminders are not recorded as completed payments.

OCR is not guaranteed to read every digit correctly, especially in blurred, tilted or
cropped images. Inspect review flags and the rejection report against the source. Keep
statements upright with clear borders for table extraction. Unknown formats may need
additional adapters; this is a local CLI/library, not an authenticated multi-user API.
The JSON store is intended for single-process use, not concurrent production writes.

Optional `--llm` categorization requires `anthropic` and `ANTHROPIC_API_KEY`. It does not
repair OCR or infer missing references.

## MongoDB input / output

```powershell
python -m pip install -r requirements.txt
$env:MONGODB_URI = "mongodb+srv://USER:PASS@cluster.mongodb.net"   # or use --mongo-uri
python transaction_pipeline.py --from-mongo --to-mongo --user-id u1
```

- **Input** - collection `raw_inputs`. Documents with no `status` (or `pending`) are read, then marked `processed` / `error`:
  - `{"type": "sms", "text": "Rs 450 debited to SWIGGY on 03-Oct-26 Ref 427619384729"}`
  - `{"type": "text", "text": "<pasted SMS log>", "source": "log1"}`
  - `{"type": "file", "filename": "statement.pdf", "data": <binary>, "pdf_password": "optional"}`
- **Output** - collection `transactions` (upserted on unique `hash`) and collection `reports`
  (duplicates, rejected rows, errors per run).
- With `--to-mongo`/`--from-mongo`, MongoDB replaces `store.json` as duplicate memory, so re-running never
  creates duplicates. Local files (`uploads/`, `--sms`) can still be mixed in the same run.
- Never commit the connection string; keep it in `MONGODB_URI`.

## Tests

```powershell
python -m unittest discover -s tests -v
```

Tests cover wrapped SMS, reference variants, dates, statement columns, repeat uploads and
conservative duplicate handling. PDF fixtures need pdfplumber. OCR tests require Tesseract.
