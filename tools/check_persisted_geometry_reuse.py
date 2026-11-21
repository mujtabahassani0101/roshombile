#!/usr/bin/env python3
"""Persisted geometry reuse must preserve exact pixels and every reading on real and known sheets."""
import ctypes,json,time,hashlib,argparse
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--library',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--legacy-only',action='store_true');a=p.parse_args()
r=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
private=r/'qa/omr-speed-1.1.52/private';private.mkdir(parents=True,exist_ok=True)
data=r/'qa/phone-accuracy-20261118/private/accuracy-evidence-1150'
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.library.resolve()));lib.roshd_process_json.argtypes=[ctypes.c_char_p];lib.roshd_process_json.restype=ctypes.c_void_p;lib.roshd_free_string.argtypes=[ctypes.c_void_p];lib.roshd_prepare_camera_json.argtypes=[ctypes.c_char_p];lib.roshd_prepare_camera_json.restype=ctypes.c_void_p
manifest=json.loads((r/'app/src/androidTest/assets/omr-speed/manifest.json').read_text())['cases']
cases=[dict(name=c['name'],count=c['count'],path=r/'app/src/androidTest/assets/omr-speed'/(c['name']+'.jpg'),framed=True,bindings=[dict(qrText=c['qr'],sheetType='T3' if c['count']==40 else 'T1')]) for c in manifest]
for d in sorted(data.glob('case-*')):
 for filename in ['source.jpg','normalized.jpg']:
  if (d/filename).exists():cases.append(dict(name=d.name+'-'+filename.split('.')[0],count=40,path=d/filename,private=True))
for name in ['T1','T3','T3-anchors','T3-anchors-no-qr','T3-anchors-wrong-qr']:
 cases.append(dict(name=name,count=100 if name=='T1' else 40,path=r/'app/src/androidTest/assets/omr'/(name+'.jpg')))
if a.legacy_only:
 cases=json.loads((r/'output/omr-speed-1.1.52/legacy/manifest.json').read_text())
bindings=json.loads((data/'bindings.json').read_text())
allowed_warning_changes={'primary_layout_geometry_verified','normalization_probe_reused','header_orientation_geometry_verified'}
def scrub(x):
 if isinstance(x,dict):return {k:scrub(v) for k,v in x.items() if k not in {'processingTimeMs','headerProcessingTimeMs','normalizedImagePath'}}
 if isinstance(x,list):return [scrub(v) for v in x if not (isinstance(v,str) and v in allowed_warning_changes)]
 return x
reports=[]
for c in cases:
 out=[];times=[];pixels=[]
 if c.get('framed'):
  framed=a.output/(c['name']+'-framed.bmp')
  ptr=lib.roshd_prepare_camera_json(json.dumps(dict(imagePath=str(c['path']),outputPath=str(framed),forceRectification=True),ensure_ascii=False).encode())
  try:framing=json.loads(ctypes.string_at(ptr))
  finally:lib.roshd_free_string(ptr)
  assert framing.get('applied'),c['name'];c['path']=framed
 for enabled in [False,True]:
  folder=(private if c.get('private') else a.output)/c['name']/str(enabled);folder.mkdir(parents=True,exist_ok=True)
  req=dict(templateJsonPaths=[str(v) for v in (r/'app/src/main/assets/templates').glob('*.json')],preferCalibratedHeader=True,qrBindings=bindings if c.get('private') else c.get('bindings',[]),reuseNormalizationGeometry=enabled,preferLegacyGeometry=enabled,includeQuestions=True,returnNormalizedImages=True,returnAnnotationGeometry=True,maxInputDimension=2000,outputDirectory=str(folder),pages=[dict(pageNumber=1,imagePath=str(c['path']),activeQuestionCount=c['count'])])
  started=time.monotonic();ptr=lib.roshd_process_json(json.dumps(req,ensure_ascii=False).encode())
  try:result=json.loads(ctypes.string_at(ptr))
  finally:lib.roshd_free_string(ptr)
  assert len(result.get('sheets',[]))==1,c['name'];sheet=result['sheets'][0]
  (folder/'result.json').write_text(json.dumps(result,indent=2));out.append(sheet);times.append(round((time.monotonic()-started)*1000,2))
  path=sheet.get('normalizedImagePath');pixels.append(hashlib.sha256(Path(path).read_bytes()).hexdigest() if path and Path(path).is_file() else None)
 if c.get('framed') or c.get('expectedKnown'):assert out[0]['success'] and out[1]['success'],c['name']
 if c.get('expectedKnown'):
  for sheet in out:
   assert len(sheet['questions'])==c['count'] and all(q['status']=='MARKED' and q['selectedOptionNumber']==(q['question']-1)%4+1 for q in sheet['questions']),c['name']
 a0=scrub(out[0]);b0=scrub(out[1]);differences=[k for k in set(a0)|set(b0) if a0.get(k)!=b0.get(k)]
 report=dict(case=c['name'],exactReadings=not differences,exactNormalizedImage=pixels[0]==pixels[1],differentFields=differences,beforeMs=times[0],afterMs=times[1],success=out[1]['success'])
 reports.append(report);print(json.dumps(report),flush=True)
(a.output/'report.json').write_text(json.dumps(reports,indent=2));getattr(core,'_ZN2cv13setNumThreadsEi')(1)
assert all(c['exactReadings'] and c['exactNormalizedImage'] for c in reports),'Reuse changed pixels or reading fields'
