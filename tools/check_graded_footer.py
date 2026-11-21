#!/usr/bin/env python3
"""Native check of the 1.1.58 graded sheet: green key ring on wrong answers and the score footer.

Reads real Roshd 40/100 fixture sheets through the adapter, builds marks the way
GradedSheet.kt does from a synthetic key, then renders with and without the
app-drawn footer (premultiplied RGBA from GradedSheetFooterTest). Checks that
only the footer box changes, the counts match, and unsafe footers are refused.

  python3 tools/check_graded_footer.py --library output/graded-1.1.58/host/libroshd_engine_adapter.so \
      --footer app/build/graded-footer/footer.rgba
"""
import argparse, ctypes, json, math, shutil
from pathlib import Path
import numpy as np
import cv2

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--library', type=Path, required=True)
p.add_argument('--footer', type=Path, required=True, help='1330 px wide premultiplied RGBA from GradedSheetFooterTest')
p.add_argument('--output', type=Path, default=ROOT / 'output/graded-1.1.58/render')
a = p.parse_args()
shutil.rmtree(a.output, ignore_errors=True); a.output.mkdir(parents=True)
lib = ctypes.CDLL(str(a.library.resolve()))
for name in ['roshd_process_json', 'roshd_annotate_json']:
    getattr(lib, name).argtypes = [ctypes.c_char_p]; getattr(lib, name).restype = ctypes.c_void_p
lib.roshd_free_string.argtypes = [ctypes.c_void_p]


def call(name, request):
    pointer = getattr(lib, name)(json.dumps(request, ensure_ascii=False).encode())
    try: return json.loads(ctypes.string_at(pointer))
    finally: lib.roshd_free_string(pointer)


def footer_box(width, height, rows):
    """Same formula as GradedSheet.footerBox."""
    bottom_of_grid = max(o['y'] + o['radius'] * 1.5 for r in rows for o in r['options'])
    left, right = round(width * .12), round(width * .88)
    bottom = int(height * .975)
    top = max(math.ceil(bottom_of_grid + height * .006), bottom - int(height * .075))
    return dict(left=left, top=top, pixelWidth=right - left, pixelHeight=bottom - top)


FOOTER_WIDTH = 1330
raw = a.footer.read_bytes(); footer_height = len(raw) // (FOOTER_WIDTH * 4)
assert len(raw) == FOOTER_WIDTH * footer_height * 4
report = []
for name, count, template in [('40-black-mixed-artwork', 40, 'yse_omr_a5_t3_40q_v1.json'),
                              ('100-black-mixed-artwork', 100, 'yse_omr_a5_t1_100q_v2.json')]:
    folder = a.output / name; folder.mkdir()
    sheet = call('roshd_process_json', dict(templateJsonPaths=[str(ROOT / 'app/src/main/assets/templates' / template)],
        includeQuestions=True, returnAnnotationGeometry=True, returnNormalizedImages=True, maxInputDimension=2000,
        outputDirectory=str(folder / 'normalized'), pages=[dict(pageNumber=1, imagePath=str(ROOT / 'output/colors-1.1.26' / f'{name}.jpg'))]))['sheets'][0]
    assert sheet.get('success'), sheet.get('error')
    geometry = sheet['annotationGeometry']; rows = {r['questionNumber']: r for r in geometry['questions']}
    marks = []; kinds = dict(correct=0, incorrect=0, missed_correct=0, blank_correct=0)
    for q in sheet['questions']:
        n = q['question']; key = (n - 1) % 4 + 1 if n % 3 else n % 4 + 1   # every third question's key differs
        point = {o['option']: o for o in rows[n]['options']}
        def add(option, verdict):
            marks.append(dict(questionNumber=n, option=option, x=point[option]['x'], y=point[option]['y'], radius=point[option]['radius'], verdict=verdict))
            kinds[verdict] += 1
        if q['status'] == 'MARKED' and q.get('confidence', 1) >= .8:
            chosen = q['selectedOptionNumber']
            if chosen == key: add(chosen, 'correct')
            else: add(chosen, 'incorrect'); add(key, 'missed_correct')
        elif q['status'] == 'BLANK':
            add(key, 'blank_correct')
    box = footer_box(geometry['width'], geometry['height'], geometry['questions'])
    assert box['pixelWidth'] == FOOTER_WIDTH, box
    # The test footer may be a pixel taller or shorter than this sheet's box: keep it bottom-aligned.
    box['top'] += box['pixelHeight'] - footer_height; box['pixelHeight'] = footer_height
    (folder / 'footer.rgba').write_bytes(raw)
    base = dict(imagePath=sheet['normalizedImagePath'], width=geometry['width'], height=geometry['height'], questionCount=count, marks=marks)
    plain = call('roshd_annotate_json', dict(base, outputPath=str(folder / 'plain.jpg')))
    scored = call('roshd_annotate_json', dict(base, outputPath=str(folder / 'graded.jpg'), footer=dict(box, path=str(folder / 'footer.rgba'))))
    assert plain['success'] and scored['success'] and scored['footer'] is True and plain['footer'] is False, (plain, scored)
    assert (scored['correctMarks'], scored['incorrectMarks'], scored['missedCorrectMarks'], scored['blankCorrectMarks']) == \
        (kinds['correct'], kinds['incorrect'], kinds['missed_correct'], kinds['blank_correct']), (scored, kinds)
    one, two = cv2.imread(str(folder / 'plain.jpg')), cv2.imread(str(folder / 'graded.jpg'))
    changed = np.argwhere(np.abs(one.astype(int) - two.astype(int)).max(axis=2) > 24)
    inside = ((changed[:, 0] >= box['top'] - 8) & (changed[:, 0] < box['top'] + box['pixelHeight'] + 8) &
              (changed[:, 1] >= box['left'] - 8) & (changed[:, 1] < box['left'] + FOOTER_WIDTH + 8))
    assert len(changed) > 5000 and inside.all(), (len(changed), int((~inside).sum()))
    centre = two[box['top'] + footer_height // 2, box['left'] + 30]
    assert centre.min() > 240, centre   # opaque white box over the printed warning strip
    # Unsafe footers are refused instead of covering answers or reading past the file.
    refused = {}
    for label, change in [('over-bubbles', dict(top=int(geometry['questions'][-1]['options'][0]['y']))),
                          ('outside-image', dict(top=geometry['height'] - footer_height + 1)),
                          ('short-file', dict(pixelHeight=footer_height + 1)),
                          ('missing-file', dict(path=str(folder / 'missing.rgba')))]:
        result = call('roshd_annotate_json', dict(base, outputPath=str(folder / f'refused-{label}.jpg'), footer={**box, 'path': str(folder / 'footer.rgba'), **change}))
        refused[label] = result.get('success') is False and not (folder / f'refused-{label}.jpg').exists()
    assert all(refused.values()), refused
    report.append(dict(sheet=name, marks=kinds, footerBox=box, changedPixelsAllInsideFooter=True, refused=refused))
    print(name, kinds, box)
(a.output / 'report.json').write_text(json.dumps(dict(platform='Linux host, synthetic key on fixture sheets', checks=report), indent=2) + '\n')
print('passed', len(report))
