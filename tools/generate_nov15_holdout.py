#!/usr/bin/env python3
"""Photograph the supplied print using independent PDF vector truth and seeded answers."""
from pathlib import Path
import argparse,hashlib,json,random,subprocess,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFilter
import qrcode
from generate_rotation_holdout import projection
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--quick',action='store_true');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
cv2.setNumThreads(2);cases=[];neg=[]
poses=[(0,0,0),(30,-30,23),(-30,30,-45),(0,0,90),(0,0,180),(0,0,270)]
if not a.quick:
 for axis in (0,1):
  for angle in (-50,-40,-25,25,40,50):
   xyz=[0,0,0];xyz[axis]=angle;poses.append(tuple(xyz))
 poses += [(0,0,z) for z in (-135,-67,-23,23,45,67,135)]
 rng=random.Random(411115)
 poses += [tuple(round(rng.uniform(-lim,lim),1) for lim in (42,42,180)) for _ in range(20)]
for index,count in enumerate((100,75,40),1):
 truth=ROOT/f'output/new-sheets-1.1.41/calibration/{count}q-vector-map.json';m=json.loads(truth.read_text());W,H=m['pagePt'];sx,sy=1750/W,2480/H
 blank=Image.open(ROOT/f'tmp/pdfs/new-sheets/full-{index}.png').convert('RGB')
 qrtext=f'{"T3" if count==40 else "T1"}:{count}411115:NEW00001'
 code=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=8,border=4);code.add_data(qrtext);code.make(fit=True)
 x,y,w,h=m['qrWhitePt'];blank.paste(code.make_image().convert('RGB').resize((round(w*sx),round(h*sy)),Image.Resampling.NEAREST),(round(x*sx),round(y*sy)))
 # Native QR tests also retain the unmodified supplied sample header separately.
 blank.save(a.output/f'blank-{count}.png')
 rng=random.Random(811000+count);answers=[rng.randint(1,4) if q%11 else None for q in range(1,count+1)]
 styles=['normal'] if a.quick else ['normal','black','blue','green','red','purple','pencil','light-pencil','blank','two-marks','gray-print']
 for style in styles:
  expected=[None]*count if style=='blank' else list(answers)
  im=blank.copy();draw=ImageDraw.Draw(im)
  for q in m['questions']:
   choice=expected[q['number']-1]
   if choice is None:continue
   choices=[choice,choice%4+1] if style=='two-marks' else [choice]
   for choice in choices:
    o=next(o for o in q['options'] if o['value']==choice);x,y=o['center_pt'];r=o['radius_pt']*.80
    ink={'black':(20,20,20),'blue':(24,60,155),'green':(25,95,60),'red':(175,30,35),'purple':(100,30,125),'pencil':(105,105,105),'light-pencil':(160,160,160)}.get(style,[(25,25,25),(25,65,150),(20,100,65),(100,100,100)][q['number']%4])
    draw.ellipse(((x-r)*sx,(y-r)*sy,(x+r)*sx,(y+r)*sy),fill=ink)
  if style=='gray-print':im=im.convert('L').convert('RGB')
  if style=='normal':im.save(a.output/f'source-{count}.png')
  scenarios=[(xyz,'clear') for xyz in poses] if style=='normal' else [(poses[i],e) for i,e in [(0,'clear'),(1,'shadow'),(2,'blur')]]
  if style=='normal' and not a.quick:scenarios += [(xyz,e) for xyz in [(25,-25,23),(-30,30,-45),(0,-40,135)] for e in ['shadow','blur','warm','far']]
  for i,(xyz,effect) in enumerate(scenarios):
   distance=330.
   while True:
    matrix=projection(*xyz,distance=distance);corners=cv2.perspectiveTransform(np.float32([[[0,0],[148,0],[148,210],[0,210]]]),matrix)[0]
    if np.all(corners>=[100,100]) and np.all(corners<=[2300,2500]):break
    distance+=5
   if effect=='far':distance*=1.3;matrix=projection(*xyz,distance=distance)
   pixels=cv2.warpPerspective(np.asarray(im),matrix@np.diag([148/1750,210/2480,1.]),(2400,2600),borderValue=(65,70,75))
   if effect=='shadow':pixels=np.uint8(pixels.astype(float)*np.linspace(.48,.96,2400)[None,:,None])
   if effect=='blur':pixels=cv2.GaussianBlur(pixels,(0,0),.85)
   if effect=='warm':pixels=np.uint8(pixels*np.array([.97,.84,.67])[None,None,:])
   name=f'nov15-{count}-{style}-{i}-{effect}';path=(a.output/f'{name}.jpg').resolve();Image.fromarray(pixels).save(path,quality=95)
   cases.append(dict(name=name,path=str(path),count=count,qr=qrtext,expected=expected,pattern=style,rotationXYZ=xyz,effect=effect,mmToImage=matrix.tolist(),vectorMap=str(truth),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
 print(count,'generated',flush=True)
(a.output/'manifest.json').write_text(json.dumps(dict(seed=411115,cases=cases),indent=2)+'\n')
print(len(cases),'cases')
