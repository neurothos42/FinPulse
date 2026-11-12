"""Preserve ruled statement cells before OCR/text flattening loses column meaning."""
from pathlib import Path


def is_statement_header(row):
    text = ' '.join(str(c or '') for c in row).lower()
    return ('date' in text and 'balance' in text
            and any(word in text for word in ('debit', 'withdrawal'))
            and any(word in text for word in ('credit', 'deposit')))


def _centres(values):
    groups = []
    for value in values:
        if not groups or value > groups[-1][-1] + 2:
            groups.append([])
        groups[-1].append(value)
    return [round(sum(group) / len(group)) for group in groups]


def image_table(image, ocr):
    """Locate a ruled table using line projections; OCR each cell independently.

    No bank-specific pixel coordinates. Unruled/rotated tables fall back to text.
    """
    from PIL import ImageOps, ImageDraw
    image = ImageOps.exif_transpose(image).convert('L')
    probe = image.copy()
    probe.thumbnail((1200, 1800))
    w, h = probe.size
    pixels = probe.load()
    ys = _centres([y for y in range(h)
                   if sum(pixels[x, y] < 120 for x in range(w)) > w * .60])
    if len(ys) < 3:
        return None
    top, bottom = ys[0], ys[-1]
    xs = _centres([x for x in range(w)
                   if sum(pixels[x, y] < 120 for y in range(top, bottom + 1))
                   > (bottom - top) * .80])
    if not 5 <= len(xs) <= 15:
        return None
    sx, sy = image.width / w, image.height / h
    image = image.copy()
    draw = ImageDraw.Draw(image)
    for x in xs:
        draw.line((int(x*sx), int(top*sy), int(x*sx), int(bottom*sy)), fill=255, width=max(2, round(sx*2)))
    for y in ys:
        draw.line((int(xs[0]*sx), int(y*sy), int(xs[-1]*sx), int(y*sy)), fill=255, width=max(2, round(sy*2)))
    def cells(a, b, header=None):
        return [ocr(image.crop((int((l + 1) * sx), int((a + 1) * sy),
                                int((r - 1) * sx), int((b - 1) * sy))), cell=("reference" if header and any(k in header[i].lower() for k in ("ref", "cheque", "chq")) else True)).strip()
                for i, (l, r) in enumerate(zip(xs, xs[1:]))]
    header = cells(ys[0], ys[1])
    if not is_statement_header(header):
        return None
    return [header] + [cells(a, b, header) for a, b in zip(ys[1:], ys[2:])]


def document_sections(path, password, ocr):
    """Yield (text, kind, table rows) per page/frame, including mixed PDFs."""
    from PIL import Image, ImageSequence
    ext = Path(path).suffix.lower()
    if ext == '.pdf':
        import pdfplumber
        with pdfplumber.open(path, password=password) as pdf:
            for page in pdf.pages:
                tables = [t for t in page.extract_tables()
                          if t and is_statement_header(t[0])]
                if tables:
                    for table in tables:
                        yield '', 'pdf', table
                    # Keep content outside tables (e.g. SMS pasted below a statement).
                    boxes = [t.bbox for t in page.find_tables()
                             if t.extract() and is_statement_header(t.extract()[0])]
                    outside = page.filter(lambda obj: not any(
                        box[0] <= obj.get('x0', -1) <= box[2]
                        and box[1] <= obj.get('top', -1) <= box[3] for box in boxes))
                    text = outside.extract_text() or ''
                    if text.strip():
                        yield text, 'text', None
                    continue
                text = page.extract_text(layout=False) or ''
                # Some scans have only a searchable title, with the body as an image.
                image_area = sum((im['x1'] - im['x0']) * (im['bottom'] - im['top'])
                                 for im in page.images)
                if len(text.strip()) < 30 or image_area > page.width * page.height * .5:
                    image = page.to_image(resolution=300).original
                    table = image_table(image, ocr)
                    yield ('' if table else ocr(image)), 'image', table
                else:
                    yield text, 'pdf', None
    else:
        with Image.open(path) as image:
            for frame in ImageSequence.Iterator(image):
                table = image_table(frame, ocr)
                yield ('' if table else ocr(frame)), 'image', table
