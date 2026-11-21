#!/usr/bin/env python3
"""Couple raw preview admission to full-resolution known-answer captures."""
import hashlib,json
from pathlib import Path
from zipfile import ZipFile,ZIP_STORED
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'output/wide-guides-1.1.40/known-captures';OUT.mkdir(parents=True,exist_ok=True)
cv2.setNumThreads(2)
resource=ROOT/'app/src/test/resources/wide-guide-preview.zip'
with ZipFile(resource) as z:
    entries={n:z.read(n) for n in z.namelist() if n!='manifest.json' and not n.startswith('known-')}
    preview=[c for c in json.loads(z.read('manifest.json')) if not c['file'].startswith('known-')]
reference=json.loads((ROOT/'output/rotation-1.1.39/fixtures/manifest.json').read_text())['cases']
captures=[]
for count in (40,75,100):
    truth=next(c for c in reference if c['count']==count)
    source=cv2.imread(str(ROOT/f'output/rotation-1.1.39/fixtures/source-{count}.png'))
    w,h=2000,2667;ph=h*.90;pw=ph*148/210;left=(w-pw)/2;top=(h-ph)/2
    nominal=np.array([[pw/148,0,left],[0,ph/210,top],[0,0,1.]])
    for index,(dx,dy,roll,scale) in enumerate(((0,0,0,1),(6,0,0,1),(-6,0,0,1),(4,-4,0,1),(-4,4,0,1),(0,0,2,1),(0,0,0,.96))):
        motion=np.eye(3);motion[:2]=cv2.getRotationMatrix2D((w/2,h/2),roll,scale)
        motion[0,2]+=dx*pw/148;motion[1,2]+=dy*ph/210
        matrix=motion@nominal
        raw=cv2.warpPerspective(source,matrix@np.diag([148/1750,210/2480,1]),(w,h),borderValue=(65,70,75))
        for effect in ('clear','dim'):
            image=np.uint8(raw*.55) if effect=='dim' else raw
            name=f'known-{count}-pose{index}-{effect}';path=(OUT/f'{name}.jpg').resolve()
            cv2.imwrite(str(path),image,[cv2.IMWRITE_JPEG_QUALITY,96])
            # Preview is derived from the same saved JPEG, with no rectification.
            sensor=cv2.imread(str(path),0);sensor=cv2.resize(sensor,(960,1280),interpolation=cv2.INTER_AREA)
            filename=name+'.png';entries[filename]=cv2.imencode('.png',sensor)[1].tobytes()
            for rotation in (0,90,180,270):
                for target in (22,26):preview.append(dict(file=filename,count=count,kind='known-capture',rotation=rotation,targetMm=target,capture=True))
            captures.append(dict(name=name,path=str(path),count=count,qr=truth['qr'],expected=truth['expected'],mmToImage=matrix.tolist(),
                                 sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
with ZipFile(resource,'w',compression=ZIP_STORED) as z:
    for name,data in entries.items():z.writestr(name,data)
    z.writestr('manifest.json',json.dumps(preview))
(OUT/'manifest.json').write_text(json.dumps(dict(cases=captures),indent=2)+'\n')
print(json.dumps(dict(knownCaptures=len(captures),totalPreviewCases=len(preview))))
