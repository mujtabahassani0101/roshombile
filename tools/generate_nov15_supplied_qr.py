#!/usr/bin/env python3
"""Camera scenes preserving the supplied rounded S2 QR, authorized by exact fixture roster bindings."""
from pathlib import Path
import json,hashlib
import cv2,numpy as np
from PIL import Image
from generate_rotation_holdout import projection
ROOT=Path(__file__).resolve().parents[1];out=ROOT/'output/new-sheets-1.1.41/supplied-qr-fixtures';out.mkdir(exist_ok=True)
base=json.loads((ROOT/'output/new-sheets-1.1.41/fixtures/manifest.json').read_text())['cases'];cases=[];cv2.setNumThreads(2)
poses=[(0,0,0),(40,0,0),(-40,0,0),(0,40,0),(0,-40,0),(0,0,45),(0,0,90),(0,0,180),(30,-30,113),(-30,30,-45),(25,-25,23),(-30,30,-45)]
for i,count in enumerate([100,75,40],1):
 c=next(c for c in base if c['count']==count);plane=np.asarray(Image.open(ROOT/f'output/new-sheets-1.1.41/fixtures/source-{count}.png').convert('RGB')).copy();header=np.asarray(Image.open(ROOT/f'tmp/pdfs/new-sheets/full-{i}.png').convert('RGB'));plane[:650]=header[:650]
 for index,xyz in enumerate(poses):
  distance=330.
  while True:
   h=projection(*xyz,distance=distance);q=cv2.perspectiveTransform(np.float32([[[0,0],[148,0],[148,210],[0,210]]]),h)[0]
   if np.all(q>=[100,100]) and np.all(q<=[2300,2500]):break
   distance+=5
  pixels=cv2.warpPerspective(plane,h@np.diag([148/1750,210/2480,1.]),(2400,2600),borderValue=(65,70,75));effect='clear'
  if index==10:pixels=np.uint8(pixels*np.linspace(.48,.96,2400)[None,:,None]);effect='shadow'
  if index==11:pixels=cv2.GaussianBlur(pixels,(0,0),.85);effect='blur'
  name=f'nov15-supplied-s2-{count}-{index}-{effect}';path=(out/f'{name}.jpg').resolve();Image.fromarray(pixels).save(path,quality=95)
  qr='S2XMHKMQ7J33Y0NDB1XN';binding=dict(qrText=qr,sheetType='T3' if count==40 else 'T1')
  if count==75:binding['activeQuestionCount']=75
  cases.append(dict(c,name=name,path=str(path),qr=qr,qrBindings=[binding],rotationXYZ=xyz,effect=effect,mmToImage=h.tolist(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
(out/'manifest.json').write_text(json.dumps(dict(cases=cases),indent=2)+'\n');print(len(cases),'scenes with original S2 artwork')
