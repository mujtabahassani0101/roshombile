#!/usr/bin/env python3
"""Independent A5 mark fixtures: faint graphite, incomplete fills, blur and light.

Known choices come from the supplier maps, never from recognition output.
Linux synthetic fixtures do not establish accuracy on a physical phone.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import random
import time
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
import qrcode

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--library',required=True,type=Path)
p.add_argument('--folder',type=Path,default=ROOT/'output/mark-accuracy-1.1.30/fixtures')
p.add_argument('--report',required=True,type=Path)
p.add_argument('--generate',action='store_true')
a=p.parse_args();a.folder.mkdir(parents=True,exist_ok=True)
patterns=['dark','graphite-205','graphite-214','graphite-218','partial','offset','blue-scribble','purple-partial','two-marks','erased-previous','blank']
if a.generate:
    cases=[]
    for count in [40,75,100]:
        supplier=json.loads((ROOT/f'qa/{40 if count==40 else 100}q-supplied-map-1.1.26.json').read_text())
        blank=Image.open(ROOT/f'output/random-1.1.27/blank-{count}.png').convert('RGB')
        for pattern in patterns:
            im=blank.copy();draw=ImageDraw.Draw(im)
            expected=[];rng=random.Random(30100+count)
            for question in supplier['questions'][:count]:
                choice=rng.randint(1,4)
                expected.append(None if pattern=='blank' or question['number']%7==0 else choice)
                if expected[-1] is None: continue
                point=question['options'][choice-1]
                cx,cy=point['center_mm'];r=point['radius_mm']
                sx,sy=1750/148,2480/210
                def ellipse(x,y,rx,ry,color): draw.ellipse(((x-rx)*sx,(y-ry)*sy,(x+rx)*sx,(y+ry)*sy),fill=color)
                if pattern.startswith('graphite-'):
                    value=int(pattern.split('-')[1]);ellipse(cx,cy,r*.80,r*.80,(value,)*3)
                elif pattern=='partial': ellipse(cx,cy,r*.82,r*.43,(45,)*3)
                elif pattern=='offset': ellipse(cx+r*.24,cy-r*.12,r*.72,r*.72,(55,)*3)
                elif pattern=='purple-partial': ellipse(cx,cy,r*.86,r*.31,(130,45,140))
                elif pattern=='blue-scribble':
                    points=[((cx-r*.62+r*1.24*i/7)*sx,(cy+(-1 if i%2 else 1)*r*.60)*sy) for i in range(8)]
                    draw.line(points,fill=(25,65,150),width=round(r*sx*.23),joint='curve')
                elif pattern in ['two-marks','erased-previous']:
                    ellipse(cx,cy,r*.82,r*.82,(35,)*3)
                    other=question['options'][choice%4];x,y=other['center_mm']
                    if pattern=='two-marks': ellipse(x,y,r*.75,r*.60,(65,)*3)
                    else: ellipse(x,y,r*.8,r*.8,(232,)*3)
                else: ellipse(cx,cy,r*.82,r*.82,(35,)*3)
            qr_text=f'{"T3" if count==40 else "T1"}:{count}314:A1234567'
            qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=6,border=4)
            qr.add_data(qr_text);qr.make(fit=True)
            x,y,w,h=[supplier['blank_qr_placeholder_mm'][k] for k in ['x','y','w','h']]
            im.paste(qr.make_image().convert('RGB').resize((round(w*1750/148),round(h*2480/210)),Image.Resampling.NEAREST),
                     (round(x*1750/148),round(y*2480/210)))
            for effect in ['clear','shadow','soft-blur','perspective','noise']:
                pixels=np.array(im).astype(np.float32)
                if effect=='shadow': pixels=pixels*np.linspace(.43,1.,pixels.shape[1])[None,:,None]
                if effect=='noise':
                    pixels=pixels+np.random.default_rng(30100+count).normal(0,3.5,pixels.shape[:2])[:,:,None]
                if effect=='perspective':
                    h,w=pixels.shape[:2]
                    matrix=cv2.getPerspectiveTransform(np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]]),
                        np.float32([[155,45],[w+70,120],[w+10,h+170],[40,h+60]]))
                    pixels=cv2.warpPerspective(pixels,matrix,(w+220,h+240),borderValue=(65,65,65))
                image=Image.fromarray(np.uint8(np.clip(pixels,0,255)))
                if effect=='soft-blur': image=image.filter(ImageFilter.GaussianBlur(1.1))
                name=f'{count}-{pattern}-{effect}';path=a.folder/(name+'.jpg');image.save(path,quality=92)
                cases.append(dict(name=name,path=str(path.resolve()),count=count,pattern=pattern,effect=effect,
                                  expected=expected,qr=qr_text,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (a.folder/'manifest.json').write_text(json.dumps({'cases':cases},indent=2))
else: cases=json.loads((a.folder/'manifest.json').read_text())['cases']
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0')
getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.library.resolve()));lib.omr_mobile_process_json.argtypes=[ctypes.c_char_p]
lib.omr_mobile_process_json.restype=ctypes.c_void_p;lib.omr_mobile_free_string.argtypes=[ctypes.c_void_p]
lib.omr_mobile_engine_revision.restype=ctypes.c_char_p
rows=[]
for i,c in enumerate(cases):
    template=ROOT/'app/src/main/assets/templates'/('yse_omr_a5_t3_40q_v1.json' if c['count']==40 else 'yse_omr_a5_t1_100q_v2.json')
    req=dict(templateJsonPaths=[str(template)],includeQuestions=True,maxInputDimension=2600,
             pages=[dict(pageNumber=1,imagePath=c['path'],activeQuestionCount=c['count'])])
    at=time.perf_counter();ptr=lib.omr_mobile_process_json(json.dumps(req,ensure_ascii=False).encode())
    try: response=json.loads(ctypes.string_at(ptr))
    finally: lib.omr_mobile_free_string(ptr)
    s=(response.get('sheets') or [{}])[0];qs=s.get('questions',[])
    incorrect=[];unsafe=[];correct=0;flagged=0
    for number,(expected,q) in enumerate(zip(c['expected'],qs),1):
        confidence=q.get('confidence',1)
        needs_review=confidence<.85
        if c['pattern']=='two-marks' and expected is not None:
            ok=needs_review
        else: ok=(q.get('status')=='BLANK' if expected is None else q.get('status')=='MARKED' and q.get('selectedOptionNumber')==expected)
        correct+=int(ok);flagged+=int(needs_review)
        if not ok:
            error=dict(question=number,expected=expected,status=q.get('status'),actual=q.get('selectedOptionNumber'),
                       confidence=confidence,fills=q.get('rawFillRatios'))
            incorrect.append(error)
            if not needs_review: unsafe.append(error)
    passed=bool(s.get('success')) and len(qs)==c['count'] and not incorrect and s.get('barcode',{}).get('text')==c['qr']
    rows.append(dict(name=c['name'],pattern=c['pattern'],effect=c['effect'],count=c['count'],passed=passed,
                     correct=correct,review=flagged,wrong=len(incorrect),unsafeWrong=len(unsafe),errors=incorrect,
                     status=s.get('status'),error=s.get('error'),ms=round((time.perf_counter()-at)*1000,2)))
    assert hashlib.sha256(Path(c['path']).read_bytes()).hexdigest()==c['sha256']
    if (i+1)%15==0: print(f'{i+1}/{len(cases)} passed={sum(x["passed"] for x in rows)}',flush=True)
report=dict(platform='Linux synthetic A5 photos, not a physical phone',engine=lib.omr_mobile_engine_revision().decode(),
            manifestSha256=hashlib.sha256((a.folder/'manifest.json').read_bytes()).hexdigest(),
            cases=len(rows),passed=sum(r['passed'] for r in rows),
            questions=sum(r['count'] for r in rows),correct=sum(r['correct'] for r in rows),unsafeWrong=sum(r['unsafeWrong'] for r in rows),
            byPattern={pattern:dict(cases=sum(r['pattern']==pattern for r in rows),passed=sum(r['passed'] and r['pattern']==pattern for r in rows),
                                   wrong=sum(r['wrong'] for r in rows if r['pattern']==pattern)) for pattern in patterns},checks=rows)
a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},indent=2))
