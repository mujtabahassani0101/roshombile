#!/usr/bin/env python3
"""Render UI previews from the exact packaged 40/75/100-question print PDFs."""
from pathlib import Path
import subprocess
import tempfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
for code, count in [('T1', 100), ('T3', 40), ('75', 75)]:
    with tempfile.TemporaryDirectory(prefix='roshd-sheet-preview-') as tmp:
        prefix = Path(tmp) / 'sheet'
        subprocess.run(['pdftoppm', '-scale-to-x', '1750', '-scale-to-y', '2480',
                        '-png', '-singlefile',
                        str(ROOT / f'app/src/main/assets/sheets/roshd-{count}q-A5.pdf'),
                        str(prefix)], check=True)
        with Image.open(str(prefix) + '.png') as image:
            image.thumbnail((430, 610), Image.Resampling.LANCZOS)
            output = ROOT / f'app/src/main/assets/images/omr-{code.lower()}-preview.png'
            image.save(output, optimize=True)
        print(output.relative_to(ROOT))
