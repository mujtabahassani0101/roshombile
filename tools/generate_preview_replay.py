#!/usr/bin/env python3
"""Deterministic camera-like luminance frames. No rectification before preview QA."""
from pathlib import Path
import cv2
import json
import numpy as np
from zipfile import ZipFile, ZIP_STORED

root=Path(__file__).resolve().parents[1]
out=root/'output/auto-capture-1.1.35/preview-replay'
out.mkdir(parents=True,exist_ok=True)
cases=[]
for count in (40,75,100):
    page=cv2.imread(str(root/f'app/src/androidTest/assets/preview/{count}q.jpg'),0)
    base=np.full((1280,960),70,np.uint8)
    base[64:1216,74:886]=cv2.resize(page,(812,1152),interpolation=cv2.INTER_AREA)
    rng=np.random.default_rng(count)
    noise=rng.normal(0,5,base.shape)
    variants={
        'clear':(base,True),
        'dim':((base*.45).astype('uint8'),True),
        'faint':((base*.15+200).astype('uint8'),True),
        'shadow':((base*(.45+.55*np.linspace(0,1,960)[None,:])).astype('uint8'),True),
        'isp-soft':(cv2.GaussianBlur(base,(0,0),.7),True),
        'sharp-noise':(np.clip(base.astype(float)+noise*4,0,255).astype('uint8'),True),
        'blur':(cv2.GaussianBlur(base,(0,0),2.5),False),
        'blur-noise':(np.clip(cv2.GaussianBlur(base,(0,0),2.5).astype(float)+noise,0,255).astype('uint8'),False),
        'blur-heavy-noise':(np.clip(cv2.GaussianBlur(base,(0,0),2.5).astype(float)+noise*4,0,255).astype('uint8'),False),
        'motion-blur':(cv2.filter2D(base,-1,np.ones((1,17),np.float32)/17),False),
        'dark':((base*.12).astype('uint8'),False),
        'shift-x':(cv2.warpAffine(base,np.float32([[1,0,30],[0,1,0]]),(960,1280),borderValue=70),False),
        'shift-y':(cv2.warpAffine(base,np.float32([[1,0,0],[0,1,35]]),(960,1280),borderValue=70),False),
        'noise':(np.clip(210+noise,0,255).astype('uint8'),False),
    }
    missing=base.copy();missing[90:150,100:160]=255
    variants['missing-square']=(missing,False)
    partial=base.copy();partial[640:1000,480:850]=cv2.GaussianBlur(base,(0,0),2.5)[640:1000,480:850]
    variants['partly-blurred-answers']=(partial,False)
    for name,(scene,accepted) in variants.items():
        for rotation in (0,90,180,270):
            raw=np.rot90(scene,rotation//90).copy()
            file=f'{count}-{name}-{rotation}.png'
            cv2.imwrite(str(out/file),raw)
            cases.append(dict(file=file,count=count,kind=name,rotation=rotation,capture=accepted))
    for offset in (1,2,-2,3):
        scene=cv2.warpAffine(base,np.float32([[1,0,offset],[0,1,-offset]]),(960,1280),borderValue=70)
        file=f'{count}-jitter-{offset}.png';cv2.imwrite(str(out/file),scene)
        cases.append(dict(file=file,count=count,kind='jitter',rotation=0,capture=True))
(out/'manifest.json').write_text(json.dumps(cases,indent=2)+'\n')
# Keep only upright frames in the JVM test resource. Sensor rotations are
# generated in memory by the test, so a clean checkout needs no Python runtime.
resource=root/'app/src/test/resources/preview-replay.zip'
with ZipFile(resource,'w',compression=ZIP_STORED) as archive:
    archive.writestr('manifest.json',json.dumps(cases))
    for c in cases:
        if c['rotation']==0:archive.write(out/c['file'],c['file'])
print(json.dumps(dict(frames=len(cases),positive=sum(c['capture'] for c in cases),negative=sum(not c['capture'] for c in cases))))
