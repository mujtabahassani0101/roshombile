#!/usr/bin/env python3
"""Exercise the copied document->OMR path and compare every answer with upstream.

All answer choices are independently known PDF fixtures; host timings are not
physical-phone measurements. Source libraries are never modified by this tool.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import subprocess
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adapter',type=Path,required=True)
p.add_argument('--source',type=Path,required=True)
p.add_argument('--scanner',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--limit',type=int,default=0)
p.add_argument('--only-failures-from',type=Path,help='Recheck only previously failed scenes after an integration change')
p.add_argument('--compare-original-entry',action='store_true',help='Also measure the original mixed-layout entry point; its header-dot orientation ranking differs on the new Roshd prints')
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0')
getattr(core,'_ZN2cv13setNumThreadsEi')(4)
source=ctypes.CDLL(str(a.source.resolve()));adapter=ctypes.CDLL(str(a.adapter.resolve()))
def call(lib,name,free,request):
    function=getattr(lib,name);function.argtypes=[ctypes.c_char_p];function.restype=ctypes.c_void_p
    release=getattr(lib,free);release.argtypes=[ctypes.c_void_p]
    ptr=function(json.dumps(request,ensure_ascii=False).encode())
    try:return json.loads(ctypes.string_at(ptr))
    finally:release(ptr)
cases=json.loads((ROOT/'output/random-1.1.27/manifest.json').read_text())['cases']
for original in list(cases):
    if int(original['name'].split('-')[-1])>=6:continue
    for effect in ['dim','shadow']:
        path=ROOT/'output/guided-scan-1.1.29/scenes'/(original['name']+'-'+effect+'.jpg')
        cases.append(dict(original,name=original['name']+'-'+effect,path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
if a.only_failures_from:
    names={c['name'] for c in json.loads(a.only_failures_from.read_text())['checks'] if not c['passed']}
    cases=[c for c in cases if c['name'] in names]
if a.limit:cases=cases[:a.limit]
results=[]
def normalized_answers(sheet,count):
    return [{k:q.get(k) for k in ['question','status','selectedOption','selectedOptionNumber','selectedOptions','confidence','rawFillRatios']}
            for q in sheet.get('questions',[]) if q.get('question',0)<=count]
for index,c in enumerate(cases):
    directory=a.output/c['name'];directory.mkdir(exist_ok=True)
    photo=Path(c['path']);prepared=directory/'document.png'
    scan=json.loads(subprocess.run([str(a.scanner.resolve()),str(photo),str(prepared)],text=True,capture_output=True,check=True).stdout)
    cropped=scan.get('applied',False)
    template=ROOT/'app/src/main/assets/templates'/('yse_omr_a5_t3_40q_v1.json' if c['count']==40 else 'yse_omr_a5_t1_100q_v2.json')
    request=dict(templateJsonPaths=[str(template)],includeQuestions=True,maxInputDimension=2000,returnNormalizedImages=True,
                 outputDirectory=str(directory/'normalized'),pages=[dict(pageNumber=1,imagePath=str(prepared if cropped else photo),activeQuestionCount=c['count'])])
    def read(req):return (call(adapter,'roshd_process_json','roshd_free_string',req).get('sheets') or [{}])[0]
    sheet=read(request);fallback=False
    if not sheet.get('success') and cropped:
        fallback=True;request['pages'][0]['imagePath']=str(photo);sheet=read(request)
    parity=None
    if a.compare_original_entry:
        upstream_request=dict(request,returnNormalizedImages=False)
        upstream=(call(source,'omr_mobile_process_json','omr_mobile_free_string',upstream_request).get('sheets') or [{}])[0]
        parity=normalized_answers(sheet,c['count'])==normalized_answers(upstream,c['count']) and sheet.get('success')==upstream.get('success')
    errors=[]
    for number,(choice,q) in enumerate(zip(c['answers'],sheet.get('questions',[])),1):
        if q.get('status')!='MARKED' or q.get('selectedOptionNumber')!=choice:
            errors.append(dict(question=number,expected=choice,actual=q.get('selectedOptionNumber'),status=q.get('status'),confidence=q.get('confidence'),
                               needsReview=q.get('needsReview',False) or q.get('confidence',1)<.85))
    geometry=sheet.get('annotationGeometry',{});geometry_ok=False;annotation_ok=False;max_error=None
    if sheet.get('success') and geometry.get('questions'):
        supplier=json.loads((ROOT/f'qa/{40 if c["count"]==40 else 100}q-supplied-map-1.1.26.json').read_text())
        distances=[];marks=[]
        for row in geometry['questions']:
            for option in row['options']:
                expected=supplier['questions'][row['questionNumber']-1]['options'][option['option']-1]['center_mm']
                distances.append(float(np.hypot(option['x']-expected[0]*1750/148,option['y']-expected[1]*2480/210)))
            if row['questionNumber']<=10:
                choice=c['answers'][row['questionNumber']-1];point=next(o for o in row['options'] if o['option']==choice)
                marks.append(dict(point,questionNumber=row['questionNumber'],verdict='correct'))
        max_error=max(distances);geometry_ok=len(geometry['questions'])==c['count'] and max_error<7
        rendered=directory/'colored.jpg'
        annotation=call(adapter,'roshd_annotate_json','roshd_free_string',dict(imagePath=sheet['normalizedImagePath'],outputPath=str(rendered),
            width=geometry['width'],height=geometry['height'],questionCount=c['count'],marks=marks))
        annotation_ok=annotation.get('success') and annotation.get('correctMarks')==len(marks) and rendered.is_file()
    preserved=hashlib.sha256(photo.read_bytes()).hexdigest()==c['sha256']
    passed=bool(sheet.get('success')) and len(sheet.get('questions',[]))==c['count'] and not errors and preserved and geometry_ok and annotation_ok
    results.append(dict(name=c['name'],passed=passed,originalMixedEntryPointMatches=parity,rectified=cropped,originalFallback=fallback,
                        originalPreserved=preserved,geometryValid=geometry_ok,geometryMaximumErrorPx=max_error,annotationValid=bool(annotation_ok),
                        status=sheet.get('status'),error=sheet.get('error'),answerErrors=errors,
                        unsafeErrors=sum(not e['needsReview'] for e in errors)))
    (a.output/'progress.json').write_text(json.dumps(results,indent=2)+'\n')
    if (index+1)%15==0:print(f'{index+1}/{len(cases)} passed={sum(r["passed"] for r in results)}',flush=True)
report=dict(platform='Linux synthetic PDF photographs; not a physical phone',cases=len(results),passed=sum(r['passed'] for r in results),
            comparedOriginalEntryPoint=a.compare_original_entry,originalsPreserved=all(r['originalPreserved'] for r in results),
            unsafeErrors=sum(r['unsafeErrors'] for r in results),checks=results)
(a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},indent=2))
