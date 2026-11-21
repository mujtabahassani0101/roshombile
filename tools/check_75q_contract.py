#!/usr/bin/env python3
"""Verify 75-active T1 routing, roster authorization, mixed pages and inactive marks."""
import argparse
import ctypes
import json
from pathlib import Path

from PIL import Image, ImageDraw
import qrcode

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--library',type=Path,required=True)
p.add_argument('--folder',type=Path,default=ROOT/'output/random-1.1.27')
a=p.parse_args()
cases=json.loads((a.folder/'manifest.json').read_text())['cases']
sample={c['count']:next(x for x in cases if x['count']==c['count']) for c in cases}
lib=ctypes.CDLL(str(a.library.resolve()))
for name in ('omr_mobile_process_json','omr_mobile_annotate_json'):
    getattr(lib,name).argtypes=[ctypes.c_char_p];getattr(lib,name).restype=ctypes.c_void_p
lib.omr_mobile_free_string.argtypes=[ctypes.c_void_p]
def call(request,annotate=False):
    ptr=(lib.omr_mobile_annotate_json if annotate else lib.omr_mobile_process_json)(json.dumps(request,ensure_ascii=False).encode())
    try:return json.loads(ctypes.string_at(ptr))
    finally:lib.omr_mobile_free_string(ptr)
templates=[str(ROOT/'app/src/main/assets/templates'/name) for name in
           ['yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json']]
def request(count,selected=0,bindings=None,**extra):
    return dict(templateJsonPaths=templates,includeQuestions=True,returnAnnotationGeometry=True,
                pages=[dict(pageNumber=1,imagePath=sample[count]['path'],activeQuestionCount=selected)],
                qrBindings=bindings or [],maxInputDimension=2000,**extra)
results=[]
def check(name,req,expected=None,source=None,qr=None):
    response=call(req);sheets=response.get('sheets',[])
    if expected is None:
        ok=not any(s.get('success') or s.get('questions') for s in sheets)
    else:
        sheet=sheets[0] if len(sheets)==1 else {}
        qs=sheet.get('questions',[])
        ok=sheet.get('success') and sheet.get('activeQuestionCount')==expected and len(qs)==expected
        ok=ok and sheet.get('sheetType',{}).get('code')==('T3' if expected==40 else 'T1')
        if source:
            ok=ok and all(q['question']==n and q.get('selectedOptionNumber')==choice and q.get('status')=='MARKED'
                          for n,(q,choice) in enumerate(zip(qs,source['answers']),1))
        if qr:ok=ok and sheet.get('barcode',{}).get('text')==qr
        ok=ok and len(sheet.get('annotationGeometry',{}).get('questions',[]))==expected
    results.append({'name':name,'passed':bool(ok),'expectedCount':expected,'status':response.get('status')})
    print(name,'PASS' if ok else 'FAIL',flush=True)
    if not ok:(a.folder/f'contract-{name}-failure.json').write_text(json.dumps(response,indent=2))
    return response

binding={'qrText':sample[75]['qr'],'sheetType':'T1','activeQuestionCount':75}
check('explicit-75-inactive-marks-ignored',request(75,75),75,sample[75])
check('exact-roster-import-75',request(75,bindings=[binding]),75,sample[75],sample[75]['qr'])
check('selected-100-conflicts-with-roster-75',request(75,100,[binding]))
check('selected-40-conflicts-with-T1',request(100,40))
check('selected-75-conflicts-with-T3',request(40,75))
check('retired-60-cannot-be-selected',request(100,60))
check('unsupported-80-cannot-be-selected',request(100,80))
check('fractional-count-rejected',request(75,75.5))
check('negative-count-rejected',request(75,-75))
check('fixed-T1-engine-75',request(75,75,knownSheetType='T1'),75,sample[75])

root=dict(templateJsonPaths=templates,imagePath=sample[100]['path'],includeQuestions=True,
          returnAnnotationGeometry=True,maxInputDimension=2000,qrBindings=[binding])
check('unrelated-nested-binding-does-not-limit-root',root,100,sample[100])
check('explicit-root-75',dict(root,activeQuestionCount=75),75,sample[100])

def with_qr(payload,label):
    image=Image.open(sample[75]['path']).convert('RGB')
    # Sample 01 is an upright, unwarped print.
    x,y,w,h=[round(v) for v in (19*1750/148,18*2480/210,23*1750/148,23*2480/210)]
    ImageDraw.Draw(image).rectangle((x,y,x+w,y+h),fill='white')
    if payload:
        qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=6,border=4)
        qr.add_data(payload);qr.make(fit=True)
        image.paste(qr.make_image().convert('RGB').resize((w,h),Image.Resampling.NEAREST),(x,y))
    path=a.folder/f'contract-{label}.jpg';image.save(path,quality=97)
    return str(path)

opaque='1:1234567:123456789'
opaque_path=with_qr(opaque,'opaque')
opaque_binding=dict(qrText=opaque,sheetType='T1',activeQuestionCount=75)
req=request(75,bindings=[opaque_binding]);req['pages'][0]['imagePath']=opaque_path
check('opaque-roster-import-75',req,75,sample[75],opaque)
check('unbound-opaque-cannot-grade',dict(req,qrBindings=[]))
check('duplicate-opaque-cannot-grade',dict(req,qrBindings=[opaque_binding,opaque_binding]))
bad=request(75,75);bad['pages'][0]['imagePath']=with_qr(None,'no-qr')
check('75-still-requires-QR',bad)
bad=request(75,75);bad['pages'][0]['imagePath']=with_qr('T2:123456:ABCDEFGH','retired-qr')
check('retired-QR-still-rejected',bad)

mixed=request(40)
mixed['pages']=[dict(pageNumber=i+1,imagePath=sample[count]['path'],activeQuestionCount=count)
                for i,count in enumerate([40,75,100])]
response=call(mixed)
ok=response.get('success') and [s['activeQuestionCount'] for s in response.get('sheets',[])]==[40,75,100]
ok=ok and [len(s['questions']) for s in response['sheets']]==[40,75,100]
results.append({'name':'mixed-batch-independent-limits','passed':bool(ok)})

same=request(100)
same['pages']=[dict(pageNumber=i+1,imagePath=sample[100]['path'],activeQuestionCount=count)
               for i,count in enumerate([75,100])]
response=call(same)
ok=response.get('success') and [len(s['questions']) for s in response.get('sheets',[])]==[75,100]
results.append({'name':'T1-worker-does-not-retain-previous-limit','passed':bool(ok)})

saved=json.loads((a.folder/'results/75q-01.json').read_text())['sheets'][0]
target=a.folder/'invalid-inactive-mark.jpg';target.unlink(missing_ok=True)
response=call(dict(imagePath=saved['normalizedImagePath'],outputPath=str(target),width=1750,height=2480,
                   questionCount=75,marks=[dict(questionNumber=76,option=1,x=200,y=700,radius=20,verdict='correct')]),True)
results.append({'name':'question-76-cannot-be-colored','passed':not response.get('success') and not target.exists()})
report={'total':len(results),'passed':sum(r['passed'] for r in results),'results':results}
(ROOT/'qa/75q-contract-1.1.27.json').write_text(json.dumps(report,indent=2))
raise SystemExit(0 if report['passed']==report['total'] else 1)
