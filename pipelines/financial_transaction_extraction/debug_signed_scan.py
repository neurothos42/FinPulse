
from pathlib import Path
import tempfile

from evaluation import synthetic_documents as sd
from transaction_pipeline import TransactionPipeline

rows = sd.make_rows(10)

with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)

    source = tmp / "signed_source.pdf"
    scanned = tmp / "signed_scanned.pdf"

    sd.layout_signed_amount(source, rows)
    sd.render_pdf_to_image_pdf(source, scanned, dpi=200, noise=False)

    pipeline = TransactionPipeline(None)
    result = pipeline.process_file(str(scanned))

    print("\nEXPECTED TRANSACTIONS:")
    for row in sd.gold(rows):
        print(row)

    print("\nEXTRACTED TRANSACTIONS:")
    for txn in result.transactions:
        print({
            "date": txn.date,
            "amount": txn.amount,
            "merchant": txn.merchant,
            "description": txn.description,
            "needs_review": txn.needs_review,
        })
