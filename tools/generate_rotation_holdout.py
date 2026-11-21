#!/usr/bin/env python3
"""Known-answer PDF sheets projected by a pinhole camera, Rz @ Ry @ Rx.

X/Y are rotations out of the page plane, Z is image roll, in degrees.
The manifest retains the actual 3D projection, page corners and source hashes.
No native OMR outputs are used to construct expected answers or geometry.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random

import cv2
import numpy as np
from PIL import Image, ImageDraw
import qrcode

ROOT = Path(__file__).resolve().parents[1]


def projection(x, y, z, distance=330., focal=2750., center=(1200., 1300.)):
    x, y, z = np.deg2rad([x, y, z])
    rx = np.array([[1, 0, 0], [0, np.cos(x), -np.sin(x)], [0, np.sin(x), np.cos(x)]])
    ry = np.array([[np.cos(y), 0, np.sin(y)], [0, 1, 0], [-np.sin(y), 0, np.cos(y)]])
    rz = np.array([[np.cos(z), -np.sin(z), 0], [np.sin(z), np.cos(z), 0], [0, 0, 1]])
    rotation = rz @ ry @ rx
    intrinsic = np.array([[focal, 0, center[0]], [0, focal, center[1]], [0, 0, 1]])
    # Input is page coordinates in mm, with its rotation origin at page center.
    homography = intrinsic @ np.column_stack((rotation[:, 0], rotation[:, 1],
                                              [0, 0, distance]-rotation @ np.array([74., 105., 0])))
    return homography / homography[2, 2]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--seed', type=int, default=391115)
    p.add_argument('--random-poses', type=int, default=0, help='Fresh independent poses instead of the axis grid')
    p.add_argument('--prefix', default='xyz')
    a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(2)
    poses = [(0, 0, 0)]
    for axis in (0, 1):
        for angle in (-50, -40, -25, -10, 10, 25, 40, 50):
            xyz = [0, 0, 0]; xyz[axis] = angle; poses.append(tuple(xyz))
    poses += [(0, 0, z) for z in (-135, -90, -67, -45, -23, -10, 10, 23, 45, 67, 90, 135, 180)]
    poses += [(x, y, z) for x, y in ((-30, -30), (-30, 30), (30, -30), (30, 30))
              for z in (0, 23, 45, 113)]
    if a.random_poses:
        pose_rng=random.Random(a.seed)
        poses=[tuple(round(pose_rng.uniform(-limit,limit),1) for limit in (42,42,180)) for _ in range(a.random_poses)]
    corners = np.float32([[0, 0], [148, 0], [148, 210], [0, 210]])
    cases = []
    for count in (40, 75, 100):
        supplier = json.loads((ROOT/f'qa/{40 if count == 40 else 100}q-supplied-map-1.1.26.json').read_text())
        im = Image.open(ROOT/f'output/random-1.1.27/blank-{count}.png').convert('RGB')
        draw = ImageDraw.Draw(im); rng = random.Random(a.seed+count); expected = []
        for question in supplier['questions']:
            number = question['number']; choice = rng.randint(1, 4)
            empty = number % 11 == 0
            if number <= count: expected.append(None if empty else choice)
            if empty: continue
            option = question['options'][choice-1]; cx, cy = option['center_mm']; r = option['radius_mm']*.80
            ink = [(25, 25, 25), (25, 65, 150), (20, 100, 65), (100, 100, 100)][number % 4]
            draw.ellipse(((cx-r)*1750/148, (cy-r)*2480/210, (cx+r)*1750/148, (cy+r)*2480/210), fill=ink)
        qr_text = f'{"T3" if count == 40 else "T1"}:{count}{a.seed}:XYZ00001'
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
        qr.add_data(qr_text); qr.make(fit=True)
        box = supplier['blank_qr_placeholder_mm']
        im.paste(qr.make_image().convert('RGB').resize((round(box['w']*1750/148), round(box['h']*2480/210)), Image.Resampling.NEAREST),
                 (round(box['x']*1750/148), round(box['y']*2480/210)))
        source = np.asarray(im)
        source_path = a.output/f'source-{count}.png'; im.save(source_path)
        scenarios = [(xyz, ('clear','shadow','blur','far','warm')[i%5] if a.random_poses else 'clear') for i,xyz in enumerate(poses)]
        if not a.random_poses:scenarios += [(xyz, effect) for xyz in ((25, -25, 23), (-30, 30, -45), (40, 0, 67), (0, -40, 135))
                      for effect in ('shadow', 'blur', 'warm', 'far')]
        for index, ((x, y, z), effect) in enumerate(scenarios):
            distance = 330.
            while True:
                h = projection(x, y, z, distance=distance)
                quad = cv2.perspectiveTransform(corners[None], h)[0]
                if np.all(quad >= [110, 110]) and np.all(quad <= [2290, 2490]): break
                distance += 5
            if effect == 'far': distance *= 1.35; h = projection(x, y, z, distance=distance)
            quad = cv2.perspectiveTransform(corners[None], h)[0]
            pixels_to_mm = np.diag([148/1750, 210/2480, 1.])
            pixels = cv2.warpPerspective(source, h @ pixels_to_mm, (2400, 2600), borderValue=(65, 70, 75))
            if effect == 'shadow':
                pixels = np.uint8(pixels.astype(np.float32) * np.linspace(.48, .96, 2400)[None, :, None])
            if effect == 'blur':
                pixels = cv2.GaussianBlur(pixels, (0, 0), .85)
                noise = np.random.default_rng(a.seed+count+index).normal(0, 2, (2600, 2400, 1))
                pixels = np.uint8(np.clip(pixels.astype(float)+noise, 0, 255))
            if effect == 'warm': pixels = np.uint8(pixels * np.array([.97, .84, .67])[None, None, :])
            name = f'{a.prefix}-{count}-x{x}-y{y}-z{z}-{effect}'
            path = (a.output/f'{name}.jpg').resolve(); Image.fromarray(pixels).save(path, quality=95)
            cases.append(dict(name=name, path=str(path), count=count, qr=qr_text, expected=expected,
                              rotationXYZ=[x, y, z], effect=effect, distanceMm=distance, focalPx=2750,
                              pageQuad=quad.tolist(), mmToImage=h.tolist(),
                              sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        print(f'Generated {count}Q 3D projections', flush=True)
    (a.output/'manifest.json').write_text(json.dumps(dict(seed=a.seed, convention='Rz @ Ry @ Rx; degrees; pinhole camera; mm', cases=cases), indent=2)+'\n')


if __name__ == '__main__': main()
