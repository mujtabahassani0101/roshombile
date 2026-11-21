#!/usr/bin/env python3
"""Synthetic legacy variants with known locations, rotations and both supported QR forms."""
from pathlib import Path
import json,cv2,numpy as np,qrcode
r=Path(__file__).resolve().parents[1];out=r/'output/omr-speed-1.1.52/legacy';out.mkdir(parents=True,exist_ok=True);cases=[]
for family,count,file in [('T1',100,'yse_omr_a5_t1_100q_legacy_v2.json'),('T3',40,'yse_omr_a5_t3_40q_legacy_v1.json')]:
 primary=json.loads((r/'app/src/main/assets/templates'/file).read_text())
 for t in [primary]+primary.get('geometryVariants',[]):
  width=t['coordinateSystem']['referenceRender']['width'];height=t['coordinateSystem']['referenceRender']['height'];reg=t['liveDetection']['registration']
  page=np.full((height,width,3),245,np.uint8)
  for y in reg['rowY']:
   for x in [reg['leftRailX'],reg['rightRailX']]:cv2.circle(page,(round(x),round(y)),round(reg['dotRadius']),(0,0,0),-1,cv2.LINE_AA)
  for b in t['answers']['blocks']:
   step=b['yByRow'][1]-b['yByRow'][0];box=b['bbox']
   for row,y in enumerate(b['yByRow']):
    question=b['questionStart']+row
    if row%2==0:cv2.rectangle(page,(round(box['x']),round(y-step/2)),(round(box['x']+box['width']),round(y+step/2)),(75,0,182),-1)
    for option,x in b['xByOption'].items():
     center=(round(x),round(y));radius=round(b['bubbleRadius']);cv2.circle(page,center,radius,(245,245,245),-1,cv2.LINE_AA)
     if row%2:cv2.circle(page,center,radius,(75,0,182),2,cv2.LINE_AA)
     if int(option)==(question-1)%4+1:cv2.circle(page,center,round(b['bubbleRadius']*.73),(20,20,20),-1,cv2.LINE_AA)
  roi=t['liveDetection']['regions']['examQr'];x,y,w,h=[round(roi[k]) for k in ['x','y','width','height']]
  for historical in [False,True]:
   payload=('S2'+'0'*17+('1' if family=='T1' else '3')) if historical else family+':123456:rZeGOHtv'
   qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_Q,border=4,box_size=8);qr.add_data(payload);qr.make(fit=True)
   pixels=np.asarray(qr.make_image().convert('L').resize((w,h),__import__('PIL.Image',fromlist=['Image']).Resampling.NEAREST));copy=page.copy();copy[y:y+h,x:x+w]=np.repeat(pixels[:,:,None],3,axis=2)
   for rot in [0,90]:
    name=f"{t['geometryId']}-{'opaque' if historical else 'typed'}-{rot}";path=out/(name+'.jpg');image=cv2.rotate(copy,cv2.ROTATE_90_CLOCKWISE) if rot else copy;cv2.imwrite(str(path),image,[cv2.IMWRITE_JPEG_QUALITY,94]);cases.append(dict(name=name,count=count,path=str(path),expectedKnown=True,bindings=[dict(qrText=payload,sheetType=family)]))
(out/'manifest.json').write_text(json.dumps(cases,indent=2));print('Synthetic legacy fixtures:',len(cases))
