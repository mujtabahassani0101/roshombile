#!/usr/bin/env python3
"""Exercise the preview C ABI on PDF-based positives and unsafe negatives."""
import argparse
import ctypes
import json
import statistics
import time
from pathlib import Path

import cv2

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--fixtures', type=Path, default=root / 'output/corrected-sheet-1.1.21')
parser.add_argument('--output', type=Path, default=root / 'qa/live-anchors-1.1.23.json')
args = parser.parse_args()
lib = ctypes.CDLL(str(args.library.resolve()))
detect = lib.omr_mobile_detect_printed_anchors
detect.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_char_p]
detect.restype = ctypes.c_void_p
lib.omr_mobile_free_string.argtypes = [ctypes.c_void_p]
template = (root / 'app/src/main/assets/templates/yse_omr_a5_t1_100q_v2.json').read_bytes()

def call(image):
    image = image.copy()
    started = time.perf_counter()
    pointer = detect(image.ctypes.data, image.shape[1], image.shape[0], image.size, template)
    try:
        response = json.loads(ctypes.string_at(pointer))
    finally:
        lib.omr_mobile_free_string(pointer)
    return response, (time.perf_counter() - started) * 1000

positive = ['blank', 'filled', 'perspective', 'skew-shadow', 'grayscale']
negative = ['missing-anchor-0', 'missing-anchor-1', 'missing-anchor-2', 'missing-anchor-3',
            'missing-rows', 'previous-layout', 'two-pages']
checks = []
measurements = []
for edge in (640, 960):
    for name in positive + negative:
        source = cv2.imread(str(args.fixtures / f'{name}.jpg'), cv2.IMREAD_GRAYSCALE)
        assert source is not None, name
        image = cv2.resize(source, None, fx=edge / max(source.shape), fy=edge / max(source.shape), interpolation=cv2.INTER_AREA)
        for rotation in range(4):
            response, duration = call(image)
            assert response['found'] == (name in positive), (edge, name, rotation, response)
            if response['found']:
                assert response['matchedRows'] >= 18 and len(response['corners']) == 8
                measurements.append(duration)
            checks.append({'edge': edge, 'fixture': name, 'rotation': rotation * 90,
                           'found': response['found'], 'wallMs': round(duration, 3)})
            image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
report = {'platform': 'Linux x86_64 host; not Android or a physical phone',
          'cases': len(checks), 'falseAccepts': 0, 'misses': 0,
          'positiveMedianMs': round(statistics.median(measurements), 3),
          'positiveP95Ms': round(sorted(measurements)[int(len(measurements) * .95)], 3),
          'checks': checks}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: value for key, value in report.items() if key != 'checks'}, indent=2))
