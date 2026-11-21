#!/usr/bin/env python3
"""Exercise the shipping C ABI on the supplied PDF artwork (no phone/emulator)."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import subprocess
import time
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
import qrcode

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library',type=Path,required=True)
parser.add_argument('--output',type=Path,default=root/'qa/corrected-sheet-1.1.21.json')
parser.add_argument('--fixtures',type=Path,default=root/'output/corrected-sheet-1.1.21')
args=parser.parse_args()
args.fixtures.mkdir(parents=True,exist_ok=True)
subprocess.run(['pdftoppm','-scale-to-x','1750','-scale-to-y','2480','-png','-singlefile',
    str(root/'app/src/main/assets/sheets/roshd-100q-A5.pdf'),str(args.fixtures/'blank')],check=True)
blank=Image.open(args.fixtures/'blank.png').convert('RGB')
template=json.loads((root/'app/src/main/assets/templates/yse_omr_a5_t1_100q_v2.json').read_text())
lib=ctypes.CDLL(str(args.library.resolve()))
lib.omr_mobile_process_json.argtypes=[ctypes.c_char_p];lib.omr_mobile_process_json.restype=ctypes.c_void_p
lib.omr_mobile_free_string.argtypes=[ctypes.c_void_p]
def call(request):
    pointer=lib.omr_mobile_process_json(json.dumps(request,ensure_ascii=False).encode())
    try:return json.loads(ctypes.string_at(pointer))
    finally:lib.omr_mobile_free_string(pointer)
def qr(image,payload='T1:001234:S2000000000000000000'):
    out=image.copy();box=template['examCode']['qrCode']['roi']
    code=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=5,border=4)
    code.add_data(payload);code.make(fit=True)
    bitmap=code.make_image().convert('RGB').resize((round(box['width']),round(box['height'])),Image.Resampling.NEAREST)
    out.paste(bitmap,(round(box['x']),round(box['y'])))
    return out
def marked(image,mode='all'):
    out=image.copy();draw=ImageDraw.Draw(out)
    for block in template['answers']['blocks']:
        for row,y in enumerate(block['yByRow']):
            question=block['questionStart']+row
            if mode=='mixed' and question%5==0:continue
            option=(question-1)%4+1
            choices=[option,option%4+1] if mode=='mixed' and question%11==0 else [option]
            for choice in choices:
                x=block['xByOption'][str(choice)];r=block['bubbleRadius']*.84
                draw.ellipse((x-r,y-r,x+r,y+r),fill=(14,14,14))
    return out
def perspective(image,points):
    src=np.array([[0,0],[image.width,0],[image.width,image.height],[0,image.height]],float)
    matrix=[];rhs=[]
    for (x,y),(u,v) in zip(points,src):
        matrix.extend([[x,y,1,0,0,0,-u*x,-u*y],[0,0,0,x,y,1,-v*x,-v*y]])
        rhs.extend([u,v])
    coeff=np.linalg.solve(np.array(matrix),np.array(rhs))
    return image.transform((2100,2900),Image.Transform.PERSPECTIVE,coeff,Image.Resampling.BICUBIC,fillcolor=(85,90,94))
base=qr(blank);filled=marked(base)
# Canonical positive fixture is shared by Android JNI and continuous queue checks.
filled.save(root/'app/src/androidTest/assets/omr/T1.jpg',quality=97)
cases={'blank':base,'filled':filled,'mixed':marked(base,'mixed')}
for angle in (90,180,270):cases[f'rotate-{angle}']=filled.rotate(angle,expand=True)
cases['perspective']=perspective(filled,[(220,160),(1840,80),(1980,2650),(130,2780)])
cases['skew-shadow']=perspective(filled,[(160,230),(1880,140),(1770,2790),(310,2600)])
pixels=np.array(cases['skew-shadow']).astype(float)
gradient=np.linspace(.62,1.0,pixels.shape[1])[None,:,None]
cases['skew-shadow']=Image.fromarray(np.clip(pixels*gradient,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(.5))
cases['grayscale']=filled.convert('L').convert('RGB')
cases['grayscale-blank']=base.convert('L').convert('RGB')
cases['no-qr']=blank
withoutRows=filled.copy();draw=ImageDraw.Draw(withoutRows)
registration=template['liveDetection']['registration']
for x in (registration['leftRailX'],registration['rightRailX']):
    r=registration['dotRadius']+5
    draw.rectangle((x-r,registration['rowY'][0]-r,x+r,registration['rowY'][-1]+r),fill='white')
cases['missing-rows']=withoutRows
legacy=json.loads((root/'native/omr_engine/omr-mobile-engine/cpp/tests/fixtures/legacy-t1-test-only.json').read_text())
previous=Image.new('RGB',(1750,2480),'white');draw=ImageDraw.Draw(previous)
for x in (legacy['liveDetection']['registration']['leftRailX'],legacy['liveDetection']['registration']['rightRailX']):
    for y in legacy['liveDetection']['registration']['rowY']:
        r=legacy['liveDetection']['registration']['dotRadius'];draw.ellipse((x-r,y-r,x+r,y+r),fill='black')
cases['previous-layout']=qr(previous)
two=Image.new('RGB',(3500,2480),(90,90,90));two.paste(filled,(0,0));two.paste(filled,(1750,0))
cases['two-pages']=two
misplaced=blank.copy();roi=template['examCode']['qrCode']['roi']
patch=base.crop((round(roi['x']),round(roi['y']),round(roi['x']+roi['width']),round(roi['y']+roi['height'])))
misplaced.paste(patch,(650,1100));cases['qr-in-answers']=misplaced
for i,box in enumerate(template['cornerAnchors']):
    hidden=filled.copy();draw=ImageDraw.Draw(hidden)
    draw.rectangle((box['x']-5,box['y']-5,box['x']+box['width']+5,box['y']+box['height']+5),fill='white')
    cases[f'missing-anchor-{i}']=hidden
results=[]
for name,image in cases.items():
    path=args.fixtures/f'{name}.jpg';image.save(path,quality=92)
    original=hashlib.sha256(path.read_bytes()).hexdigest()
    started=time.perf_counter()
    result=call({'templateJsonPaths':[str(root/'app/src/main/assets/templates'/n) for n in
        ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json')],
        'outputDirectory':str(args.fixtures/'normalized'),'includeQuestions':True,'compactResult':False,
        'returnNormalizedImages':True,'returnAnnotationGeometry':True,'maxInputDimension':2000,
        'pages':[{'pageNumber':1,'imagePath':str(path)}]})
    elapsed=round((time.perf_counter()-started)*1000,2)
    (args.fixtures/f'{name}.json').write_text(json.dumps(result,indent=2))
    assert result.get('sheets'), str(result)
    sheet=result['sheets'][0]
    assert hashlib.sha256(path.read_bytes()).hexdigest()==original,'original evidence changed'
    negative=name in ('no-qr','missing-rows','previous-layout','two-pages','qr-in-answers') or name.startswith('missing-anchor')
    if negative:
        assert not sheet.get('success'),f'{name}: incomplete registration/identity accepted'
        checked=0
    else:
        assert sheet.get('success'),f"{name}: {sheet.get('status')} {sheet.get('error')}"
        assert sheet.get('homographyStrategyUsed')=='four_square_anchors_verified_rows_v1',f'{name}: wrong rectifier'
        questions=sheet['questions'];assert len(questions)==100
        for q in questions:
            number=q['question'];expected='BLANK' if name in ('blank','grayscale-blank') or (name=='mixed' and number%5==0) else 'MARKED'
            assert q['status']==expected,f"{name}: Q{number} expected {expected}, got {q['status']}"
            if expected=='MARKED':
                option=(number-1)%4+1
                if name=='mixed' and number%11==0:
                    # Preserve the existing OMR tie policy: a low-confidence
                    # provisional choice requires human review, never certainty.
                    assert q['selectedOptionNumber'] in (option,option%4+1) and q['confidence']<.8
                    assert sheet['reviewRequired']
                else:assert q['selectedOptionNumber']==option,f'{name}: Q{number} wrong choice'
        checked=len(questions)
    results.append({'case':name,'passed':True,'questionsChecked':checked,'elapsedMs':elapsed,'originalUnchanged':True})
    print(json.dumps(results[-1]),flush=True)
report={'platform':'Linux host OpenCV 4.13; Android ships 4.12. No emulator or physical camera used.',
    'pdfSha256':template['debug']['sourcePdfSha256'],'cases':results}
args.output.write_text(json.dumps(report,indent=2)+'\n')
