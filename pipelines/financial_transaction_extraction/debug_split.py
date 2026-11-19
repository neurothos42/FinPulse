
from transaction_pipeline import (
    split_records,
    parse_record,
    ParseContext,
    extract_amounts_signed,
)

text = """
01/04/2026 UPI-NETFLIX-814660325134 -120.50 84,879.50
01/04/2026 UPI-SWIGGY-691937865764 -49.00 84,830.50
02/04/2026 UPI-AMAZON PAY-162632597597 -450.00 84,380.50
"""

records = split_records(text)

ctx = ParseContext(prev_balance=85000.00)

for i, record in enumerate(records, 1):
    print(f"\nRECORD {i}")
    print(record)

    print("AMOUNTS:", extract_amounts_signed(record, "statement"))

    txn, reason = parse_record(
        record,
        "statement",
        "debug.pdf",
        ctx,
    )

    if txn:
        print("DATE:", txn.date)
        print("AMOUNT:", txn.amount)
        print("MERCHANT:", txn.merchant)
        print("DIRECTION:", txn.direction)
    else:
        print("DISCARDED:", reason)
