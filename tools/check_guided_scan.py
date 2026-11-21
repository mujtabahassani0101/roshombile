#!/usr/bin/env python3
"""Benchmark the shipped native reader against independently known PDF answers.

Synthetic scenes and Linux timings are not physical-phone accuracy/latency claims.
Original fixtures and their answer manifests are never modified.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import time
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--library', required=True, type=Path)
p.add_argument('--report', required=True, type=Path)
p.add_argument('--max-dimension', type=int, default=2600)
p.add_argument('--stress-folder', type=Path, default=ROOT/'output/guided-scan-1.1.29/scenes')
p.add_argument('--opencv-lib', type=Path, default=Path('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0'))
args = p.parse_args()
cv2.setNumThreads(2)
core = ctypes.CDLL(str(args.opencv_lib.resolve()))
getattr(core, '_ZN2cv13setNumThreadsEi')(4)
lib = ctypes.CDLL(str(args.library.resolve()))
lib.omr_mobile_process_json.argtypes=[ctypes.c_char_p]
lib.omr_mobile_process_json.restype=ctypes.c_void_p
lib.omr_mobile_free_string.argtypes=[ctypes.c_void_p]
lib.omr_mobile_engine_revision.restype=ctypes.c_char_p
base = json.loads((ROOT/'output/random-1.1.27/manifest.json').read_text())['cases']
args.stress_folder.mkdir(parents=True,exist_ok=True)
cases = [dict(c, effect='original') for c in base]
for case in base:
    if int(case['name'].split('-')[-1]) >= 6:
        continue
    image=cv2.imread(case['path'])
    for effect in ['dim','shadow']:
        out=args.stress_folder/f'{case["name"]}-{effect}.jpg'
        if not out.exists():
            gain=.5 if effect=='dim' else np.linspace(.32,1.,image.shape[1])[None,:,None]
            pixels=np.uint8(np.clip(image.astype(np.float32)*gain+5,0,255))
            assert cv2.imwrite(str(out),pixels,[cv2.IMWRITE_JPEG_QUALITY,95])
        cases.append(dict(case,name=case['name']+'-'+effect,path=str(out.resolve()),effect=effect,
                          sha256=hashlib.sha256(out.read_bytes()).hexdigest()))
rows=[]
for i,case in enumerate(cases):
    template='yse_omr_a5_t3_40q_v1.json' if case['count']==40 else 'yse_omr_a5_t1_100q_v2.json'
    request={'templateJsonPaths':[str(ROOT/'app/src/main/assets/templates'/template)],
             'includeQuestions':True,'maxInputDimension':args.max_dimension,
             'pages':[{'pageNumber':1,'imagePath':case['path'],'activeQuestionCount':case['count']}]}
    start=time.perf_counter()
    pointer=lib.omr_mobile_process_json(json.dumps(request,ensure_ascii=False).encode())
    try: response=json.loads(ctypes.string_at(pointer))
    finally: lib.omr_mobile_free_string(pointer)
    elapsed=(time.perf_counter()-start)*1000
    sheet=(response.get('sheets') or [{}])[0]
    questions=sheet.get('questions',[])
    wrong=[]
    for number,(expected,actual) in enumerate(zip(case['answers'],questions),1):
        if actual.get('status')!='MARKED' or actual.get('selectedOptionNumber')!=expected:
            wrong.append(dict(question=number,expected=expected,status=actual.get('status'),actual=actual.get('selectedOptionNumber')))
    passed=bool(sheet.get('success')) and len(questions)==case['count'] and not wrong and sheet.get('barcode',{}).get('text')==case['qr']
    assert hashlib.sha256(Path(case['path']).read_bytes()).hexdigest()==case['sha256']
    rows.append(dict(name=case['name'],count=case['count'],effect=case['effect'],passed=passed,ms=round(elapsed,2),
                     status=sheet.get('status'),error=sheet.get('error',response.get('error')),wrong=wrong))
    if (i+1)%25==0: print(f'{i+1}/{len(cases)}; passes={sum(r["passed"] for r in rows)}',flush=True)
report={'platform':'Linux x86_64, synthetic PDF photos; not a physical phone','engine':lib.omr_mobile_engine_revision().decode(),
        'maxDimension':args.max_dimension,'threads':4,'cases':len(rows),'passed':sum(r['passed'] for r in rows),
        'medianMs':round(float(np.median([r['ms'] for r in rows])),2),'p95Ms':round(float(np.percentile([r['ms'] for r in rows],95)),2),
        'byEffect':{e:{'cases':sum(r['effect']==e for r in rows),'passed':sum(r['passed'] and r['effect']==e for r in rows)} for e in ['original','dim','shadow']},'checks':rows}
args.report.parent.mkdir(parents=True,exist_ok=True)
args.report.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},indent=2))
