#!/usr/bin/env python3
"""Prove internal circle geometry before bypassing dewarping; reject central curvature."""
import ctypes,json,hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[1]
folder=root/'output/omr-speed-1.1.50/fast-print';folder.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(2)
lib=ctypes.CDLL(str(root/'output/omr-speed-1.1.50/native-build/libroshd_engine_adapter.so'))
for name in ['roshd_process_json','roshd_prepare_camera_json']:
 f=getattr(lib,name);f.argtypes=[ctypes.c_char_p];f.restype=ctypes.c_void_p
lib.roshd_free_string.argtypes=[ctypes.c_void_p]
def call(name,req):
 p=getattr(lib,name)(json.dumps(req,ensure_ascii=False).encode())
 try:return json.loads(ctypes.string_at(p))
 finally:lib.roshd_free_string(p)
cases=json.loads((root/'app/src/androidTest/assets/omr-speed/manifest.json').read_text())['cases']
cases += [dict(name=n,count=c,expected=[i%4+1 for i in range(c)]) for n,c in [('T1',100),('T3-anchors',40)]]
cases += [dict(name=n,count=40,negative=True) for n in ['central-bend','T3-anchors-no-qr','T3-anchors-wrong-qr']]
results=[]
for c in cases:
 name=c['name'];count=c['count'];asset='omr-speed' if name.startswith('nov15-') or name=='central-bend' else 'omr'
 source=root/f'app/src/androidTest/assets/{asset}/{name}.jpg';original=hashlib.sha256(source.read_bytes()).hexdigest()
 framed=folder/(name+'.bmp')
 framing=call('roshd_prepare_camera_json',dict(imagePath=str(source),outputPath=str(framed),forceRectification=True))
 assert framing.get('applied'),(name,'anchors not found')
 request=dict(templateJsonPaths=[str(p) for p in (root/'app/src/main/assets/templates').glob('*.json')],preferCalibratedHeader=True,
              fastHeaderOnly=True,requirePrintedCircleGeometry=True,returnNormalizedImages=True,returnAnnotationGeometry=True,includeQuestions=True,
              maxInputDimension=2000,outputDirectory=str(folder/name),pages=[dict(imagePath=str(framed),pageNumber=1,activeQuestionCount=count)])
 result=call('roshd_process_json',request);assert len(result.get('sheets',[]))==1,(name,result)
 sheet=result['sheets'][0];success=sheet['success']
 if c.get('negative'):assert not success,(name,'unproved geometry admitted')
 else:
  assert success,(name,sheet.get('error'),sheet.get('reviewReasons'))
  observed=[q.get('selectedOptionNumber') if q.get('status')=='MARKED' else None for q in sheet['questions']]
  assert observed==c['expected'],(name,'known choices changed')
  assert 'roshd_printed_circle_geometry_verified' in sheet['warnings']
 assert hashlib.sha256(source.read_bytes()).hexdigest()==original
 results.append(dict(case=name,fastPrintAccepted=success,expectedAnswersVerified=0 if c.get('negative') else count,
                     invalidGeometryOrQrRejected=bool(c.get('negative')),originalPreserved=True))
 print(json.dumps(results[-1]),flush=True)
(folder/'report.json').write_text(json.dumps(dict(casesPassed=len(results),results=results),indent=2)+'\n')
getattr(core,'_ZN2cv13setNumThreadsEi')(1)
