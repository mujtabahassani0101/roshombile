#!/usr/bin/env python3
"""Deterministic camera-like fixtures with known corners; no claim of phone timing."""
import argparse
import ctypes
import json
import time
from pathlib import Path

import cv2
import numpy as np

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--library', type=Path, required=True)
p.add_argument('--mode', choices=['old', 'new'], required=True)
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
cv2.setNumThreads(2)
lib = ctypes.CDLL(str(args.library.resolve()))
detect = lib.omr_mobile_detect_printed_anchors
detect.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_char_p]
detect.restype = ctypes.c_void_p
lib.omr_mobile_free_string.argtypes = [ctypes.c_void_p]
tpl = (root / 'app/src/main/assets/templates/yse_omr_a5_t1_100q_v2.json').read_bytes()
source = cv2.imread(str(root / 'output/corrected-sheet-1.1.23/filled.jpg'), 0)
assert source is not None
src = np.float32([[0, 0], [source.shape[1], 0], [source.shape[1], source.shape[0]], [0, source.shape[0]]])
poses = {
    'center': [[115, 95], [845, 95], [845, 1185], [115, 1185]],
    'perspective': [[180, 120], [860, 220], [810, 1170], [75, 1110]],
    'offcenter': [[30, 55], [730, 70], [755, 1140], [55, 1125]],
    'tilt': [[285, 120], [815, 270], [840, 1170], [65, 970]],
}
rows = []
rng = np.random.default_rng(4517)
for pose, target in poses.items():
    target = np.float32(target)
    base = cv2.warpPerspective(source, cv2.getPerspectiveTransform(src, target), (960, 1280), borderValue=70)
    for effect in ['clear', 'soft', 'dim', 'shadow', 'hard-shadow', 'noise', 'motion']:
        frame = base.astype(np.float32)
        if effect == 'dim': frame = frame * .48 + 8
        if effect in ['shadow', 'hard-shadow']:
            light = np.linspace(.28, 1., 960)[None, :]
            if effect == 'hard-shadow': light[:, :420] = .32
            frame *= light
        if effect == 'noise': frame += rng.normal(0, 5, frame.shape)
        if effect in ['soft', 'dim', 'shadow', 'hard-shadow', 'noise']:
            frame = cv2.GaussianBlur(frame, (5, 5), .9)
        if effect == 'motion': frame = cv2.filter2D(frame, -1, np.ones((1, 5), np.float32) / 5)
        frame = np.uint8(np.clip(frame, 0, 255))
        frame = cv2.imdecode(cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])[1], 0)
        for rotation in range(4):
            original = np.ascontiguousarray(np.rot90(frame, -rotation))
            expected = target.copy()
            w, h = 960, 1280
            for _ in range(rotation):
                expected = np.column_stack([h - 1 - expected[:, 1], expected[:, 0]])
                w, h = h, w
            if args.mode == 'old':
                image = np.ascontiguousarray(original[::2, ::2])
                expected *= .5
            else:
                image = cv2.resize(original, None, fx=.75, fy=.75, interpolation=cv2.INTER_AREA)
                expected *= .75
            start = time.perf_counter()
            ptr = detect(image.ctypes.data, image.shape[1], image.shape[0], image.size, tpl)
            try: response = json.loads(ctypes.string_at(ptr))
            finally: lib.omr_mobile_free_string(ptr)
            ms = (time.perf_counter() - start) * 1000
            error = None
            if response['found']:
                corners = np.array(response['corners']).reshape(4, 2) * [image.shape[1] - 1, image.shape[0] - 1]
                error = float(np.max(np.linalg.norm(corners - expected, axis=1)))
            rows.append(dict(pose=pose, effect=effect, rotation=rotation * 90, found=response['found'], maxCornerErrorPx=error, ms=ms))
            if not response['found'] and rotation == 0:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                cv2.imwrite(str(args.output.parent / f'{args.mode}-{pose}-{effect}.png'), image)
report = dict(platform='Linux host synthetic camera-like scenes, not a physical phone', mode=args.mode,
              cases=len(rows), found=sum(r['found'] for r in rows),
              wrongCorners=sum(r['found'] and r['maxCornerErrorPx'] > 8 for r in rows),
              maxCornerErrorPx=max((r['maxCornerErrorPx'] or 0) for r in rows),
              medianMs=float(np.median([r['ms'] for r in rows])), p95Ms=float(np.percentile([r['ms'] for r in rows], 95)), checks=rows)
negatives=[]
for name in ['missing-anchor-0','missing-anchor-1','missing-anchor-2','missing-anchor-3','missing-rows','previous-layout','two-pages']:
    damaged=cv2.imread(str(root / f'output/corrected-sheet-1.1.23/{name}.jpg'),0)
    for shadow in [False,True]:
        image=cv2.resize(damaged,None,fx=960/max(damaged.shape),fy=960/max(damaged.shape),interpolation=cv2.INTER_AREA)
        if shadow: image=np.uint8(image.astype(float)*np.linspace(.28,1.,image.shape[1])[None,:])
        for rotation in range(4):
            image=np.ascontiguousarray(image)
            ptr=detect(image.ctypes.data,image.shape[1],image.shape[0],image.size,tpl)
            try: response=json.loads(ctypes.string_at(ptr))
            finally: lib.omr_mobile_free_string(ptr)
            negatives.append(dict(fixture=name,shadow=shadow,rotation=rotation*90,found=response['found']))
            image=cv2.rotate(image,cv2.ROTATE_90_CLOCKWISE)
report['negativeCases']=len(negatives)
report['falseAccepts']=sum(n['found'] for n in negatives)
report['negativeChecks']=negatives
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k not in ['checks','negativeChecks']}, indent=2))
assert report['falseAccepts']==0 and report['wrongCorners']==0
if args.mode=='new': assert report['found']==report['cases']
