#!/usr/bin/env python3
"""Compare original header routing with calibrated-first routing, using identical engines."""
import argparse
import ctypes
import json
from pathlib import Path
import time

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adapter',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--wide',action='store_true')
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0')
getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.adapter.resolve()))
lib.roshd_process_json.argtypes=[ctypes.c_char_p];lib.roshd_process_json.restype=ctypes.c_void_p
lib.roshd_free_string.argtypes=[ctypes.c_void_p]
cases=json.loads((root/'output/random-1.1.27/manifest.json').read_text())['cases']
cases=[c for c in cases if int(c['name'].split('-')[-1])<=4]
for count in [40,75,100]:
    c=next(c for c in cases if c['count']==count)
    for effect in ['dim','shadow']:
        path=root/'output/guided-scan-1.1.29/scenes'/(c['name']+'-'+effect+'.jpg')
        cases.append(dict(c,name=c['name']+'-'+effect,path=str(path)))
for file in ['T3-anchors-no-qr.jpg','T3-anchors-wrong-qr.jpg','T3-anchors-previous.jpg','T1.jpg','T3.jpg']:
    cases.append(dict(name=file,count=100 if file=='T1.jpg' else 40,path=str(root/'app/src/androidTest/assets/omr'/file)))
for file in sorted((root/'output/source-port-1.1.31/identity').glob('*')):
    if file.suffix in ['.png','.jpg']:
        cases.append(dict(name='identity-'+file.stem,count=100 if file.stem.startswith('100') else 40,path=str(file)))
results=[]
def call(request):
    started=time.monotonic();ptr=lib.roshd_process_json(json.dumps(request,ensure_ascii=False).encode())
    try:return json.loads(ctypes.string_at(ptr)),round((time.monotonic()-started)*1000,2)
    finally:lib.roshd_free_string(ptr)
def decisions(sheet):
    return {k:sheet.get(k) for k in ['success','status','error','sheetType','qrCode','questions','annotationGeometry','reviewRequired','reviewReasons']}
for c in cases:
    request=dict(templateJsonPaths=[str(root/'app/src/main/assets/templates'/n) for n in ['yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json']],
                 pages=[dict(pageNumber=1,imagePath=c['path'],activeQuestionCount=c['count'])],maxInputDimension=2000,
                 outputDirectory=str((a.output/'normalized').resolve()),returnNormalizedImages=True,returnAnnotationGeometry=True,includeQuestions=True)
    before,old_ms=call(request)
    after,new_ms=call(dict(request,preferCalibratedHeader=True,preferWideHeader=a.wide))
    assert before.get('sheets') and after.get('sheets'),(c['name'],before,after)
    before=before['sheets'][0];after=after['sheets'][0]
    match=decisions(before)==decisions(after)
    results.append(dict(name=c['name'],identical=match,beforeMs=old_ms,afterMs=new_ms,status=after.get('status')))
    print(json.dumps(results[-1]),flush=True)
(a.output/'report.json').write_text(json.dumps(dict(cases=len(results),passed=sum(r['identical'] for r in results),checks=results),indent=2)+'\n')
assert all(r['identical'] for r in results),'Calibrated routing changed recognition decisions'

# Shut down the host OpenCV worker pool before ctypes unloads its DSOs.
getattr(core, '_ZN2cv13setNumThreadsEi')(1)
