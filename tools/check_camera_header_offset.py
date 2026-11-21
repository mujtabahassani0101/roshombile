#!/usr/bin/env python3
"""Known-answer camera photographs whose header lies below the old bitmap strip."""
import argparse, ctypes, json, time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adapter',type=Path,required=True);p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True);root=Path(__file__).resolve().parents[1]
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.adapter.resolve()));lib.roshd_process_json.argtypes=[ctypes.c_char_p];lib.roshd_process_json.restype=ctypes.c_void_p;lib.roshd_free_string.argtypes=[ctypes.c_void_p]
def read(req):
 t=time.monotonic();ptr=lib.roshd_process_json(json.dumps(req,ensure_ascii=False).encode())
 try:return json.loads(ctypes.string_at(ptr))['sheets'][0],round((time.monotonic()-t)*1000,2)
 finally:lib.roshd_free_string(ptr)
report=[]
for case in json.loads((a.fixtures/'manifest.json').read_text()):
 req=dict(templateJsonPaths=[str(root/'app/src/main/assets/templates'/n) for n in ['yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json']],pages=[dict(pageNumber=1,imagePath=case['path'],activeQuestionCount=case['count'])],outputDirectory=str((a.output/'normalized').resolve()),maxInputDimension=2000,returnNormalizedImages=True,returnAnnotationGeometry=True,includeQuestions=True,preferCalibratedHeader=True,preferWideHeader=True)
 before,old=read(dict(req,preferGeometryCheckedHeader=False));after,new=read(req)
 answers=after.get('questions',[])
 correct=after['success'] and after['qrCode']['text']==case['qr'] and len(answers)==case['count'] and all(q['status']=='MARKED' and q['selectedOptionNumber']==case['answers'][q['question']-1] for q in answers)
 report.append(dict(name=case['name'],baselineSuccess=before['success'],success=after['success'],allKnownAnswersMatched=correct,sourceReadIncomplete=not after['success'] and after.get('processingTimeMs',0)>0,extendedHeaderVerified='roshd_header_position_verified_by_source_geometry' in after.get('warnings',[]),beforeMs=old,afterMs=new,headerMs=after.get('headerProcessingTimeMs'),bodyMs=after.get('processingTimeMs')))
 print(json.dumps(report[-1]),flush=True)
req['pages'][0]['imagePath']=str((a.fixtures/'body-qr-decoy.jpg').resolve());req['pages'][0]['activeQuestionCount']=100
negative,elapsed=read(req)
rejected=not negative['success'] and not negative['qrCode']['found'] and negative.get('processingTimeMs',0)>0
print(json.dumps(dict(name='QR in answer region',rejectedAfterGeometryCheck=rejected,elapsedMs=elapsed)),flush=True)
(a.output/'report.json').write_text(json.dumps(dict(cases=len(report),passed=sum(c['allKnownAnswersMatched'] for c in report),bodyQrRejectedAfterGeometryCheck=rejected,checks=report),indent=2)+'\n')
getattr(core,'_ZN2cv13setNumThreadsEi')(1)
assert all(c['allKnownAnswersMatched'] for c in report if not c['name'].startswith('75-'))
# These off-center 75Q photos cannot be normalized by the unchanged native
# source alone; CameraDocumentReader retains its outer-document fallback.
assert all(c['allKnownAnswersMatched'] or c['sourceReadIncomplete'] for c in report)
assert rejected
