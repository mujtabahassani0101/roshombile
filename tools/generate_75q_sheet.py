#!/usr/bin/env python3
"""Derive the 75-active-question vector print from the supplied 100Q PDF.

Run with the bundled PDF Python runtime (pypdf/reportlab). Guides and the QR
header are untouched; only the inactive 76..100 answer column is faded.
"""
from io import BytesIO
from pathlib import Path
import shutil
from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen.canvas import Canvas
from reportlab.lib.units import mm

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'app/src/main/assets/sheets/roshd-100q-A5.pdf'
output = ROOT / 'output/pdf/roshd-75q-A5.pdf'
output.parent.mkdir(parents=True, exist_ok=True)
reader = PdfReader(source)
page = reader.pages[0]
overlay = BytesIO()
canvas = Canvas(overlay, pagesize=(float(page.mediabox.width), float(page.mediabox.height)))
canvas.setFillColorRGB(1, 1, 1)
canvas.setFillAlpha(.82)
# Supplier map: x=16..42.75, y=57.25..189.35 mm, safely inside black rails.
canvas.rect(15.7*mm, (210-189.7)*mm, 27.35*mm, (189.7-56.95)*mm, fill=1, stroke=0)
canvas.save()
page.merge_page(PdfReader(overlay).pages[0])
writer = PdfWriter()
writer.add_page(page)
writer.add_metadata({'/Title':'Roshd A5 - 75 active questions', '/Author':'Roshd',
                     '/Subject':'T1 geometry; questions 1-75 active; 76-100 faded; black guides unchanged'})
with output.open('wb') as stream:
    writer.write(stream)
assert len(PdfReader(output).pages) == 1
shutil.copy2(output, ROOT/'app/src/main/assets/sheets/roshd-75q-A5.pdf')
print(output)
