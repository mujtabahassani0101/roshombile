#!/usr/bin/env python3
"""Compare all native decisions, scores and output pixels with QR reuse off/on."""
import ctypes,hashlib,json,time
from pathlib import Path
from PIL import Image

root=Path(__file__).resolve().parents[1]
out=root/'output/omr-speed-1.1.50/barcode-parity';out.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0')
getattr(core,'_ZN2cv13setNumThreadsEi')(2)
lib=ctypes.CDLL(str(root/'output/omr-speed-1.1.50/native-build/libroshd_engine_adapter.so'))
lib.roshd_process_json.argtypes=[ctypes.c_char_p];lib.roshd_process_json.restype=ctypes.c_void_p
lib.roshd_free_string.argtypes=[ctypes.c_void_p]
templates=[str(p) for p in (root/'app/src/main/assets/templates').glob('*.json')]
cases=[]
for c in json.loads((root/'app/src/androidTest/assets/omr-speed/manifest.json').read_text())['cases']:
    cases.append((c['name'],root/'app/src/androidTest/assets/omr-speed'/(c['name']+'.jpg'),c['count']))
for name in ['T3-anchors-no-qr','T3-anchors-wrong-qr','T3-anchors-previous','T3-anchors-blank','T1','T3-anchors']:
    cases.append((name,root/'app/src/androidTest/assets/omr'/(name+'.jpg'),100 if name=='T1' else 40))
for rotation in [90,180,270]:
    path=out/f'turn-{rotation}.png'
    with Image.open(root/'app/src/androidTest/assets/omr/T1.jpg') as image:image.rotate(rotation,expand=True).save(path)
    cases.append((f'turn-{rotation}',path,100))
def semantic(value):
    if isinstance(value,dict):return {k:semantic(v) for k,v in value.items() if k not in {'processingTimeMs','headerProcessingTimeMs','normalizedImagePath','debugImagePaths'}}
    if isinstance(value,list):return [semantic(v) for v in value]
    return value
def call(path,count,enabled,label):
    folder=out/label;folder.mkdir(exist_ok=True)
    req=dict(templateJsonPaths=templates,preferCalibratedHeader=True,reuseBarcodeDecodes=enabled,maxInputDimension=2000,
             includeQuestions=True,returnNormalizedImages=True,returnAnnotationGeometry=True,outputDirectory=str(folder),
             pages=[dict(pageNumber=1,imagePath=str(path),activeQuestionCount=count)])
    start=time.monotonic();p=lib.roshd_process_json(json.dumps(req,ensure_ascii=False).encode())
    try:r=json.loads(ctypes.string_at(p))
    finally:lib.roshd_free_string(p)
    assert len(r.get("sheets",[]))==1,(label,r)
    if path.name in ("T1.jpg","T3-anchors.jpg"):assert r["sheets"][0]["success"],label
    elapsed=round((time.monotonic()-start)*1000,2)
    pixel_hashes=[hashlib.sha256(Path(s['normalizedImagePath']).read_bytes()).hexdigest() for s in r.get('sheets',[]) if s.get('normalizedImagePath')]
    return semantic(r),pixel_hashes,elapsed
rows=[]
for name,path,count in cases:
    original=hashlib.sha256(path.read_bytes()).hexdigest()
    before,before_hash,old_ms=call(path,count,False,name+'-baseline')
    after,after_hash,new_ms=call(path,count,True,name+'-reuse')
    assert before==after,(name,'recognition changed')
    assert before_hash==after_hash,(name,'normalized pixels changed')
    assert hashlib.sha256(path.read_bytes()).hexdigest()==original
    rows.append(dict(case=name,questionCount=count,exactDecisionsAndScores=True,exactNormalizedImages=True,beforeMs=old_ms,afterMs=new_ms))
    print(json.dumps(rows[-1]),flush=True)
(out/'report.json').write_text(json.dumps(dict(casesPassed=len(rows),results=rows),indent=2)+'\n')
getattr(core,'_ZN2cv13setNumThreadsEi')(1)
