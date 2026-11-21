#!/usr/bin/env python3
"""Corpus for tools/compare_engine_builds.py (1.1.54).

Real camera photos and one Moji page from the private 1.1.52 set, the legacy and new-print
fixtures, negative QR cases, and synthetic bends/rotations of the real photos. Derived
images of private photos stay in the git-ignored private folder.
"""
import json, math
from pathlib import Path
import cv2, numpy as np

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / 'qa/omr-speed-1.1.52/private/input'
OUT = ROOT / 'qa/omr-speed-1.1.54/private'


def paper_box(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    _, mask = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    return cv2.boundingRect(max(contours, key=cv2.contourArea))


def bend(image, kind, amount):
    h, w = image.shape[:2]
    x0, y0, bw, bh = paper_box(image)
    xs, ys = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    u = np.clip((xs - x0) / bw, 0, 1); v = np.clip((ys - y0) / bh, 0, 1)
    cx, cy = x0 + bw / 2, y0 + bh / 2
    if kind == 'cyl-v':
        mx = xs - amount * bw * 0.5 * np.sin(2 * math.pi * u) / math.pi
        my = cy + (ys - cy) / (1 + amount * np.sin(math.pi * u))
    elif kind == 'cyl-h':
        my = ys - amount * bh * 0.5 * np.sin(2 * math.pi * v) / math.pi
        mx = cx + (xs - cx) / (1 + amount * np.sin(math.pi * v))
    elif kind == 'fold-v':
        lift = 1 - np.abs(2 * u - 1)
        mx = xs - amount * bw * 0.25 * np.sign(u - 0.5) * lift
        my = cy + (ys - cy) / (1 + amount * lift)
    else:
        my = ys + amount * bh * 0.08 * np.sin(2 * math.pi * u); mx = xs
    return cv2.remap(image, mx.astype(np.float32), my.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def main():
    derived = OUT / 'corpus'; derived.mkdir(parents=True, exist_ok=True)
    bindings = json.loads((PRIVATE / 'bindings.json').read_text())
    corpus = [dict(name='real-02-moji', count=40, path=str(PRIVATE / 'case-02/normalized.jpg'), bindings=bindings)]
    for n in ['02', '06', '07', '09', '11']:
        corpus.append(dict(name=f'real-{n}-source', count=40, path=str(PRIVATE / f'case-{n}/source.jpg'), bindings=bindings))
        moji = OUT / f'moji-documents/case-{n}.jpg'
        if moji.exists():
            corpus.append(dict(name=f'real-{n}-moji-1154', count=40, path=str(moji), bindings=bindings))
    for e in json.loads((ROOT / 'output/omr-speed-1.1.52/legacy/manifest.json').read_text()):
        corpus.append(dict(name='legacy-' + e['name'], count=e['count'], path=e['path'], bindings=e.get('bindings', [])))
    for e in json.loads((ROOT / 'app/src/androidTest/assets/omr-speed/manifest.json').read_text())['cases']:
        corpus.append(dict(name='speed-' + e['name'], count=e['count'], path=str(ROOT / f"app/src/androidTest/assets/omr-speed/{e['name']}.jpg")))
    for n, count in [('T1', 100), ('T3-anchors', 40), ('T3-anchors-no-qr', 40), ('T3-anchors-wrong-qr', 40)]:
        corpus.append(dict(name='omr-' + n, count=count, path=str(ROOT / f'app/src/androidTest/assets/omr/{n}.jpg')))
    corpus.append(dict(name='central-bend', count=40, path=str(ROOT / 'app/src/androidTest/assets/omr-speed/central-bend.jpg')))
    for n in ['02', '06', '07', '09']:
        source = cv2.imread(str(PRIVATE / f'case-{n}/source.jpg'))
        scale = 2000 / max(source.shape[:2])
        source = cv2.resize(source, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        for kind in ['cyl-v', 'cyl-h', 'fold-v', 'wave']:
            for amount in [0.01, 0.04, 0.10]:
                path = derived / f'{n}-{kind}-{amount}.jpg'
                if not path.exists():
                    cv2.imwrite(str(path), bend(source, kind, amount), [cv2.IMWRITE_JPEG_QUALITY, 95])
                corpus.append(dict(name=f'bend-{n}-{kind}-{amount}', count=40, path=str(path), bindings=bindings))
        for degrees, code in [(90, cv2.ROTATE_90_CLOCKWISE), (180, cv2.ROTATE_180)]:
            path = derived / f'{n}-rot{degrees}.jpg'
            if not path.exists():
                cv2.imwrite(str(path), cv2.rotate(source, code), [cv2.IMWRITE_JPEG_QUALITY, 95])
            corpus.append(dict(name=f'rot-{n}-{degrees}', count=40, path=str(path), bindings=bindings))
    missing = [c['name'] for c in corpus if not Path(c['path']).is_file()]
    assert not missing, missing
    (OUT / 'corpus.json').write_text(json.dumps(corpus, ensure_ascii=False, indent=1) + '\n')
    print(len(corpus), 'inputs ->', OUT / 'corpus.json')


main()
