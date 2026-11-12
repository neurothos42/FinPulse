import csv
import tempfile
import unittest
from pathlib import Path
from transaction_pipeline import (TransactionPipeline, extract_ref, parse_date,
                                  parse_statement_table, write_csv)


class PipelineTests(unittest.TestCase):
    def test_reference_formats(self):
        for text, expected in [
            ('Refno 426363998765', '426363998765'),
            ('UPI no. 426363998765', '426363998765'),
            ('IMPS: 4324103892', '4324103892'),
            ('Ref No. XX114XXXXX393', 'XX114XXXXX393'),
            ('UTR: HDFCABC12345', 'HDFCABC12345'),
            ('UPI/DR/427619384729/SHOP', '427619384729'),
            ('Ref No 001234', '001234'),
            ('A/c 123456789012 Call 18002586161', None),
        ]:
            with self.subTest(text=text):
                self.assertEqual(extract_ref(text), expected)

    def test_dates(self):
        for text in ['13/Oct/2026', '13 October, 2026', '13Oct26', '13-10-2026']:
            self.assertEqual(parse_date(text), '2026-10-13')

    def test_wrapped_sms(self):
        text = ('Rs. 450 debited from A/c XX1234 to SWIGGY on 03-Oct-26\n'
                'UPI Ref no\n427619384729.\n'
                'Rs. 650 credited to A/c XX1234 on 04-Oct-26\n'
                'Refno 427619384730.')
        result = TransactionPipeline(None).process_sms(text)
        self.assertEqual([t.amount for t in result.transactions], [-450, 650])
        self.assertEqual([t.ref_no for t in result.transactions], ['427619384729', '427619384730'])

    def test_distinct_references_not_dropped(self):
        pipe = TransactionPipeline(None)
        pipe.process_sms('Rs 450 debited to SWIGGY on 03-Oct-26 Ref 111111111111', 'one')
        result = pipe.process_sms('Rs 450 debited to SWIGGY on 03-Oct-26 Ref 222222222222', 'two')
        self.assertEqual(len(result.transactions), 1)
        self.assertFalse(result.transactions[0].possible_duplicate_of)

    def test_fuzzy_only_flags(self):
        pipe = TransactionPipeline(None)
        pipe.process_sms('Rs 450 debited to SWIGGY on 03-Oct-26', 'bank')
        result = pipe.process_sms('Rs 450 paid to SWIGGY on 03-Oct-26', 'shop')
        self.assertEqual(len(result.transactions), 1)
        self.assertTrue(result.transactions[0].possible_duplicate_of)

    def test_reference_duplicate(self):
        pipe = TransactionPipeline(None)
        pipe.process_sms('Rs 450 debited to SWIGGY on 03-Oct-26 Ref 111111111111')
        result = pipe.process_sms('Rs 450 paid to SWIGGY on 03-Oct-26 Ref 111111111111')
        self.assertEqual(len(result.duplicates), 1)

    def test_table_blank_credit_and_zero_debit(self):
        table = [['Txn Date','Value Date','Description','Ref No./Cheque No.','Debit','Credit','Balance'],
                 ['3 May 2018','4 May 2018','Charges','001234','88.50','','75,134.21'],
                 ['4 May 2018','4 May 2018','Salary','ABCD123','0.00','1,000.00','76,134.21']]
        _, candidates = parse_statement_table(table, 'test')
        txns = [t for t, _ in candidates]
        self.assertEqual([t.amount for t in txns], [-88.5, 1000])
        self.assertEqual(txns[0].ref_no, '001234')
        self.assertEqual(txns[0].value_date, '2018-05-04')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'table.csv'
            write_csv(txns, path)
            with path.open(encoding='utf-8-sig') as handle:
                rows = list(csv.reader(handle))
            self.assertEqual(rows[1][4:7], ['88.50', '', '75134.21'])

    def test_single_statement_row(self):
        r = TransactionPipeline(None).process_sms('03/10/2026 UPI SHOP debit 450.00 5000.00')
        self.assertEqual(len(r.transactions), 1)
        self.assertEqual(r.transactions[0].amount, -450)

    def test_sample_pdf_all_messages_accounted_for(self):
        result = TransactionPipeline(None).process_file('samples/extended_statement.pdf')
        self.assertEqual(len(result.transactions), 9)
        self.assertEqual(len(result.discarded), 3)
        refs = [t.ref_no for t in result.transactions]
        self.assertIn('4324103892', refs)
        self.assertIn('XX114XXXXX393', refs)

    def test_repeat_upload(self):
        pipe = TransactionPipeline(None)
        first = pipe.process_file('samples/statement.pdf')
        second = pipe.process_file('samples/statement.pdf')
        self.assertEqual(len(first.transactions), 7)
        self.assertEqual(len(second.transactions), 0)
        self.assertEqual(len(second.duplicates), 7)




class OCRRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import importlib.util
        import os
        import shutil
        if not shutil.which('tesseract') or not importlib.util.find_spec('pytesseract'):
            raise unittest.SkipTest('Tesseract and pytesseract required for image regression tests')
        os.environ.setdefault('OMP_THREAD_LIMIT', '1')

    def test_bank_image_all_rows(self):
        result = TransactionPipeline(None).process_file('samples/1 (1).webp')
        self.assertEqual(len(result.transactions), 14)
        self.assertFalse(result.discarded)
        self.assertEqual([t.amount for t in result.transactions],
                         [-88.5, -32652, -21739, 70006.68, -10000, 40000, 203.1,
                          -12, -10000, -35000, 820, 1200, -10000, -2714])
        self.assertTrue(all(t.date and t.needs_review for t in result.transactions))
        self.assertEqual(result.transactions[0].ref_no, '38976288')
        self.assertEqual(result.transactions[3].ref_no, 'MAB000116714875')
        self.assertEqual(result.transactions[11].ref_no, 'MAC000133344873')
        self.assertEqual(result.transactions[-1].balance, 65246.99)

    def test_screenshot_visible_sms(self):
        result = TransactionPipeline(None).process_file('samples/Screenshot 2026-10-05 173242.png')
        self.assertEqual(len(result.transactions), 8)
        self.assertEqual(result.transactions[0].ref_no, '123456789012')
        self.assertEqual(result.transactions[1].ref_no, '426363998765')
        self.assertEqual(result.transactions[2].ref_no, '4324103892')

    def test_receipt_total(self):
        result = TransactionPipeline(None).process_file('samples/1.webp')
        self.assertEqual(len(result.transactions), 1)
        self.assertEqual(result.transactions[0].amount, -3150)


class DocumentBoundaryTests(unittest.TestCase):
    def test_sms_reference_continues_on_next_page(self):
        from unittest.mock import patch
        sections = [('Rs 50 debited to SHOP on 03-Oct-26 Ref', 'pdf', None),
                    ('123456789012', 'pdf', None)]
        with patch('statement_extraction.document_sections', return_value=iter(sections)):
            result = TransactionPipeline(None).process_file('bank.pdf')
        self.assertEqual(len(result.transactions), 1)
        self.assertEqual(result.transactions[0].ref_no, '123456789012')

    def test_failed_page_does_not_change_store(self):
        from unittest.mock import patch
        def broken(*args):
            yield 'Rs 50 debited on 03-Oct-26', 'pdf', None
            raise RuntimeError('page failure')
        pipe = TransactionPipeline(None)
        with patch('statement_extraction.document_sections', side_effect=broken):
            with self.assertRaises(RuntimeError):
                pipe.process_file('bank.pdf')
        self.assertEqual(pipe.store, [])


if __name__ == '__main__':
    unittest.main()
