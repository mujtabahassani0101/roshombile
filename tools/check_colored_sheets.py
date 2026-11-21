#!/usr/bin/env python3
"""Full native C ABI regression on both supplied PDFs, ten print inks and valid header QR.
Raster fixtures use the independent supplier maps; camera evidence is never edited.
"""
import argparse,ctypes,json,time,subprocess,hashlib
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFilter
import qrcode
import cv2
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--library',type=Path)
p.add_argument('--generate-only',action='store_true')
p.add_argument('--selected-template',action='store_true',help='Exercise app-selected family, persisted images and annotation geometry')
p.add_argument('--run-only',action='store_true',help='Reuse the saved fixture manifest')
p.add_argument('--fixtures',type=Path,default=ROOT/'output/colors-1.1.26')
p.add_argument('--report',type=Path,default=ROOT/'qa/colors-1.1.26.json')
a=p.parse_args();a.fixtures.mkdir(parents=True,exist_ok=True)
PALETTE={'magenta':'#b5004d','red':'#c62828','orange':'#ef6c00','yellow':'#ffc107','green':'#208038',
         'teal':'#008577','blue':'#1565c0','purple':'#7b1fa2','brown':'#795548','black':'#222222'}
cases=[];sx,sy=1750/148,2480/210
if not a.run_only:
    for count,family in [(40,'T3'),(100,'T1')]:
        geometry=json.loads((ROOT/f'qa/{count}q-supplied-map-1.1.26.json').read_text())
        pdf=ROOT/f'app/src/main/assets/sheets/roshd-{count}q-A5.pdf'
        prefix=a.fixtures/f'blank-{count}'
        subprocess.run(['pdftoppm','-png','-singlefile','-scale-to-x','1750','-scale-to-y','2480',str(pdf),str(prefix)],check=True)
        original=Image.open(str(prefix)+'.png').convert('RGB');pixels=np.array(original).astype(float)
        # Pink artwork coverage, including antialiasing. White paper is untouched.
        pink=(pixels[:,:,0]>pixels[:,:,1]+2)&(pixels[:,:,2]>pixels[:,:,1]+2)
        coverage=1-pixels[:,:,1:2]/255
        payload='T3:213327:rZeGOHtv' if count==40 else 'T1:001234:S2000000000000000000'
        box=geometry['blank_qr_placeholder_mm']
        def make(color,all_ink=False,mode='filled',qr_payload=payload,shift=(0,0),body_qr=False):
            rgb=np.array([int(color[i:i+2],16) for i in (1,3,5)])
            if all_ink:
                weight=1-np.min(pixels,axis=2,keepdims=True)/255
                colored=255*(1-weight)+rgb[None,None,:]*weight
            else:
                colored=pixels.copy();replacement=255*(1-coverage)+rgb[None,None,:]*coverage
                colored[pink]=replacement[pink]
            im=Image.fromarray(np.clip(colored,0,255).astype('uint8'));draw=ImageDraw.Draw(im)
            # User's registration ink is always black, independent of content ink.
            for anchor in geometry['corner_anchor_rectangles_mm']:
                bounds=(round((anchor['x']-.5)*sx),round((anchor['y']-.5)*sy),
                        round((anchor['x']+anchor['w']+.5)*sx),round((anchor['y']+anchor['h']+.5)*sy))
                im.paste(original.crop(bounds),bounds[:2])
            for dot in geometry['row_marks']:
                x,y=dot['center_mm'];r=dot['radius_mm']+.5
                bounds=(round((x-r)*sx),round((y-r)*sy),round((x+r)*sx),round((y+r)*sy))
                im.paste(original.crop(bounds),bounds[:2])
            expected=[]
            if qr_payload:
                qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=6,border=4)
                qr.add_data(qr_payload);qr.make(fit=True)
                code=qr.make_image(fill_color=tuple(rgb.tolist()) if all_ink else 'black',back_color='white').convert('RGB')
                w,h=round(box['w']*sx),round(box['h']*sy)
                code=code.resize((w,h),Image.Resampling.NEAREST)
                im.paste(code,(round((box['x']+shift[0])*sx),round((110 if body_qr else box['y']+shift[1])*sy)))
            for q in geometry['questions']:
                n=q['number'];choice=(n-1)%4+1
                choices=[] if mode=='blank' or (mode=='mixed' and n%5==0) else ([choice,choice%4+1] if mode=='mixed' and n%11==0 else [choice])
                expected.append({'number':n,'state':'BLANK' if not choices else 'MULTI_MARK' if len(choices)>1 else 'MARKED','option':choice if len(choices)==1 else None})
                for choice in choices:
                    option=q['options'][choice-1];cx,cy=option['center_mm'];r=option['radius_mm']*.81
                    ink=(20,45,145) if mode=='blue-pen' else (14,14,14)
                    draw.ellipse(((cx-r)*sx,(cy-r)*sy,(cx+r)*sx,(cy+r)*sy),fill=ink)
            return im,expected
        def save(name,image,expected,qr=payload,negative=False):
            path=a.fixtures/f'{count}-{name}.jpg';image.save(path,quality=94)
            cases.append({'name':f'{count}-{name}','count':count,'family':family,'path':str(path),'qr':qr,'expected':expected,'negative':negative})
        for idx,(name,color) in enumerate(PALETTE.items()):
            for mode,all_ink in [('filled',False),('blank',False),('blue-pen',False),('mixed',False),('filled',True),('blank',True)]:
                im,expected=make(color,all_ink,mode)
                save(f'{name}-{mode}-'+('all-content' if all_ink else 'artwork'),im,expected)
            im,expected=make(color,True,'filled',shift=(1.7,-1.5))
            # Every ink also sees tilted registration, QR shift, JPEG and a smooth shadow.
            w,h=im.size;source=np.float32([[0,0],[w,0],[w,h],[0,h]])
            target=np.float32([[100,95],[w-55,145],[w-20,h-65],[60,h-20]])
            matrix=cv2.getPerspectiveTransform(source,target)
            image=cv2.warpPerspective(np.array(im),matrix,(w,h),borderValue=(70,75,80)).astype(float)
            image*=np.linspace(.60,1.0,w)[None,:,None]
            tilted=Image.fromarray(np.clip(image,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(.35))
            save(f'{name}-tilt-shadow-qr-shift',tilted.rotate(90*(idx%4),expand=True),expected)
        # Malformed identity and body-only QR must never yield answers.
        for label,qr_payload,body in [('no-qr',None,False),('wrong-family','T1:001234:S2000000000000000000' if count==40 else 'T3:213327:rZeGOHtv',False),('body-qr',payload,True)]:
            im,expected=make(PALETTE['magenta'],qr_payload=qr_payload,body_qr=body)
            save(label,im,expected,qr_payload,True)
        # Update canonical Android QA artwork only, never shipped inside release APK.
        im,_=make(PALETTE['magenta']);im.save(ROOT/f'app/src/androidTest/assets/omr/{"T3-anchors" if count==40 else "T1"}.jpg',quality=97)
        if count==40:
            for mode in ['blank']:
                im,_=make(PALETTE['magenta'],mode=mode);im.save(ROOT/f'app/src/androidTest/assets/omr/T3-anchors-{mode}.jpg',quality=97)
            for label,qr_payload in [('wrong-qr','T1:001234:S2000000000000000000'),('no-qr',None)]:
                im,_=make(PALETTE['magenta'],qr_payload=qr_payload);im.save(ROOT/f'app/src/androidTest/assets/omr/T3-anchors-{label}.jpg',quality=97)
            im,_=make(PALETTE['magenta']);w,h=im.size
            matrix=cv2.getPerspectiveTransform(np.float32([[0,0],[w,0],[w,h],[0,h]]),np.float32([[130,90],[w-60,160],[w-10,h-80],[75,h-25]]))
            Image.fromarray(cv2.warpPerspective(np.array(im),matrix,(w,h),borderValue=(65,65,65))).save(ROOT/'app/src/androidTest/assets/omr/T3-anchors-perspective.jpg',quality=97)
    previous=ROOT/'app/src/androidTest/assets/omr/T3-anchors-previous.jpg'
    cases.append({'name':'40-previous-grid','count':40,'family':'T3','path':str(previous),
                  'qr':'T3:213327:rZeGOHtv','expected':[],'negative':True})
    manifest={'palette':PALETTE,'cases':cases}
    (a.fixtures/'manifest.json').write_text(json.dumps(manifest,indent=2))
else:
    cases=json.loads((a.fixtures/'manifest.json').read_text())['cases']
if a.generate_only:print(f'Generated {len(cases)} independent cases.');raise SystemExit()
if not a.library:p.error('--library is required unless --generate-only')
lib=ctypes.CDLL(str(a.library.resolve()))
lib.omr_mobile_process_json.argtypes=[ctypes.c_char_p];lib.omr_mobile_process_json.restype=ctypes.c_void_p
lib.omr_mobile_free_string.argtypes=[ctypes.c_void_p]
lib.omr_mobile_detect_printed_anchors.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_char_p];lib.omr_mobile_detect_printed_anchors.restype=ctypes.c_void_p
paths=[str(ROOT/'app/src/main/assets/templates'/n) for n in ['yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json']]
def parse(ptr):
    try:return json.loads(ctypes.string_at(ptr))
    finally:lib.omr_mobile_free_string(ptr)
results=[]
if a.selected_template:
    cases=[c for c in cases if c['negative'] or 'filled-artwork' in c['name']]
for index,case in enumerate(cases):
    req={'templateJsonPaths':paths,'includeQuestions':True,'returnAnnotationGeometry':True,'maxInputDimension':2000,'returnNormalizedImages':False,'pages':[{'pageNumber':1,'imagePath':case['path']}]}
    if a.selected_template:
        req['templateJsonPaths']=[paths[1 if case['count']==40 else 0]]
        req['returnNormalizedImages']=True
        req['outputDirectory']=str(a.fixtures/'selected-normalized')
    original=hashlib.sha256(Path(case['path']).read_bytes()).hexdigest()
    start=time.monotonic();response=parse(lib.omr_mobile_process_json(json.dumps(req,ensure_ascii=False).encode()));elapsed=(time.monotonic()-start)*1000
    sheets=response.get('sheets',[]);s=sheets[0] if len(sheets)==1 else {};qs=s.get('questions',[])
    if case['negative']:
        passed=len(sheets)==1 and not s.get('success',False) and not qs;errors=[]
    else:
        errors=[]
        for e,q in zip(case['expected'],qs):
            matches=q.get('question')==e['number']
            if e['state']=='MULTI_MARK':
                option=(e['number']-1)%4+1
                matches=matches and q.get('status')=='MARKED' and q.get('selectedOptionNumber') in (option,option%4+1) and q.get('confidence',1)<.8 and s.get('reviewRequired',False)
            else:
                matches=matches and q.get('status')==e['state'] and (e['option'] is None or q.get('selectedOptionNumber')==e['option'])
            if not matches:errors.append({'expected':e,'actual':q})
        # Exact identity is required; finding a payload elsewhere in diagnostics is insufficient.
        qr=s.get('barcode',{}).get('text')
        passed=s.get('success',False) and len(qs)==case['count'] and not errors and s.get('sheetType',{}).get('code')==case['family'] and qr==case['qr']
    annotationError=None
    if a.selected_template and not case['negative']:
        geometry=s.get('annotationGeometry',{})
        source=json.loads((ROOT/f"qa/{case['count']}q-supplied-map-1.1.26.json").read_text())
        points={q['questionNumber']:q['options'] for q in geometry.get('questions',[])}
        try:
            assert geometry.get('width')==1750 and geometry.get('height')==2480
            assert len(points)==case['count']
            normalized=Path(s['normalizedImagePath'])
            assert normalized.is_file() and Image.open(normalized).size==(1750,2480)
            for q in source['questions']:
                found={o['option']:o for o in points[q['number']]}
                assert len(found)==4
                for number,option in enumerate(q['options'],1):
                    x,y=option['center_mm'];actual=found[number]
                    assert abs(actual['x']-x*sx)<4 and abs(actual['y']-y*sy)<4
                    assert actual['radius']>0
        except (AssertionError,KeyError,TypeError) as error:
            annotationError=str(error) or 'persisted image or bubble coordinates did not match supplier geometry'
            passed=False
    preview=None
    if not case['negative'] and ('filled' in case['name'] or 'tilt' in case['name']):
        gray=cv2.imread(case['path'],cv2.IMREAD_GRAYSCALE);gray=cv2.resize(gray,None,fx=960/max(gray.shape),fy=960/max(gray.shape),interpolation=cv2.INTER_AREA);gray=np.ascontiguousarray(gray)
        template=Path(paths[1 if case['count']==40 else 0]).read_bytes()
        preview=parse(lib.omr_mobile_detect_printed_anchors(gray.ctypes.data,gray.shape[1],gray.shape[0],gray.size,template))
        passed=passed and preview.get('found',False)
    unchanged=hashlib.sha256(Path(case['path']).read_bytes()).hexdigest()==original
    passed=passed and unchanged
    results.append({'name':case['name'],'passed':passed,'originalUnchanged':unchanged,'elapsedMs':round(elapsed,2),'annotationError':annotationError,'status':s.get('status'),'error':s.get('error'),'questionCount':len(qs),'answerErrors':errors[:3],'preview':preview})
    if not passed:(a.fixtures/(case['name']+'-failure.json')).write_text(json.dumps(response,indent=2))
    print(f'{index+1}/{len(cases)} {"PASS" if passed else "FAIL"} {case["name"]} {elapsed:.0f} ms',flush=True)
    a.report.write_text(json.dumps({'platform':'Linux x86_64; real C ABI; synthetic PDF-derived photos, not physical phone','selectedTemplate':a.selected_template,'palette':PALETTE,'passed':sum(r['passed'] for r in results),'total':len(results),'results':results},indent=2))
raise SystemExit(0 if all(r['passed'] for r in results) else 1)
