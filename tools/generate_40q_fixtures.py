#!/usr/bin/env python3
"""QA only: render supplied print PDF and fill known answers using its independent map."""
import json, subprocess, tempfile
from pathlib import Path
from PIL import Image, ImageDraw
import qrcode
import cv2
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
geometry=json.loads((ROOT/'qa/40q-supplied-map-1.1.26.json').read_text())
out=ROOT/'app/src/androidTest/assets/omr';out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    prefix=Path(tmp)/'page'
    subprocess.run(['pdftoppm','-png','-singlefile','-scale-to-x','1750','-scale-to-y','2480',str(ROOT/'app/src/main/assets/sheets/roshd-40q-A5.pdf'),str(prefix)],check=True)
    base=Image.open(str(prefix)+'.png').convert('RGB')
sx,sy=base.width/148,base.height/210
roi=geometry['blank_qr_placeholder_mm']
def page(payload,filled):
    image=base.copy();draw=ImageDraw.Draw(image)
    x,y,w,h=[round(v) for v in (roi['x']*sx,roi['y']*sy,roi['w']*sx,roi['h']*sy)]
    draw.rectangle((x-2,y-2,x+w+2,y+h+2),fill='white')
    if payload:
        qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,border=4,box_size=6)
        qr.add_data(payload);qr.make(fit=True)
        code=qr.make_image(fill_color='black',back_color='white').convert('RGB').resize((w,h),Image.Resampling.NEAREST)
        image.paste(code,(x,y))
    if filled:
        for question in geometry['questions']:
            option=question['options'][(question['number']-1)%4]
            cx,cy=option['center_mm'];r=2.05
            draw.ellipse(((cx-r)*sx,(cy-r)*sy,(cx+r)*sx,(cy+r)*sy),fill='black')
    return image
filled=page('T3:213327:rZeGOHtv',True)
filled.save(out/'T3-anchors.jpg',quality=97)
page('T3:213327:rZeGOHtv',False).save(out/'T3-anchors-blank.jpg',quality=97)
page('T1:001234:S2000000000000000000',True).save(out/'T3-anchors-wrong-qr.jpg',quality=97)
page(None,True).save(out/'T3-anchors-no-qr.jpg',quality=97)
w,h=filled.size
matrix=cv2.getPerspectiveTransform(np.float32([[0,0],[w,0],[w,h],[0,h]]),np.float32([[130,90],[w-60,160],[w-10,h-80],[75,h-25]]))
warped=cv2.warpPerspective(np.array(filled),matrix,(w,h),borderValue=(65,65,65))
Image.fromarray(warped).save(out/'T3-anchors-perspective.jpg',quality=97)
# Offset matches Android imagefile camera HAL projection used by scanner regression.
scene=Image.new('RGB',(3400,3400),(55,60,65));small=filled.resize((round(w*.9),round(h*.9)),Image.Resampling.LANCZOS)
scene.paste(small,(60+(w-small.width)//2,460+(h-small.height)//2))
scene.save('/tmp/roshd-camera-40q-1.1.26.png')
print('Generated five new-print QA fixtures and virtual camera scene.')
