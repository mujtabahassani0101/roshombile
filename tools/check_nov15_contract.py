#!/usr/bin/env python3
"""Identity, geometry, wrong-layout, legacy archive and blank-print boundaries."""
import argparse,ctypes,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--adapter',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.adapter.resolve()));lib.roshd_process_json.argtypes=[ctypes.c_char_p];lib.roshd_process_json.restype=ctypes.c_void_p;lib.roshd_free_string.argtypes=[ctypes.c_void_p]
checks=[]
def read(name,path,count,bindings=None,persist=True):
 before=hashlib.sha256(path.read_bytes()).hexdigest()
 req=dict(templateJsonPaths=[str(ROOT/'app/src/main/assets/templates'/n) for n in ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json','yse_omr_a5_t1_75q_20261115.json')],preferCalibratedHeader=True,maxInputDimension=2000,returnNormalizedImages=persist,outputDirectory=str(a.output.resolve()),pages=[dict(pageNumber=1,imagePath=str(path.resolve()),activeQuestionCount=count)],qrBindings=bindings or [])
 ptr=lib.roshd_process_json(json.dumps(req,ensure_ascii=False).encode())
 try:s=json.loads(ctypes.string_at(ptr))['sheets'][0]
 finally:lib.roshd_free_string(ptr)
 assert hashlib.sha256(path.read_bytes()).hexdigest()==before
 (a.output/f'{name}.json').write_text(json.dumps(s,indent=2)+'\n')
 return s
for index,count in enumerate((100,75,40),1):
 source=ROOT/f'tmp/pdfs/new-sheets/full-{index}.png';family='T3' if count==40 else 'T1';qr='S2XMHKMQ7J33Y0NDB1XN'
 s=read(f'supplied-{count}-unbound',source,count)
 assert not s['success'] and not s['questions'] and s['qrCode']['text']==qr
 checks.append(f'{count}Q sample old S2 QR cannot authorize answers without a roster binding')
 for selected in (count,0):
  b=dict(qrText=qr,sheetType=family)
  if count==75:b['activeQuestionCount']=75
  s=read(f'supplied-{count}-bound-{selected}',source,selected,[b])
  assert s['success'] and s['activeQuestionCount']==count and len(s['questions'])==count,(count,selected,s['error'])
  assert all(q['status']=='BLANK' for q in s['questions']),(count,[(q['question'],q['status']) for q in s['questions'] if q['status']!='BLANK'])
  assert len(s['annotationGeometry']['questions'])==count
  assert s['matchedRowsUsed']==(20 if count==40 else 25)
  checks.append(f'{count}Q supplied unmodified blank: exact roster binding, no printed-digit false marks; selected={selected}')
 blank=ROOT/f'output/new-sheets-1.1.41/fixtures/blank-{count}.png'
 s=read(f'new-{count}-import-auto',blank,0)
 assert s['success'] and s['activeQuestionCount']==count and len(s['questions'])==count
 assert all(q['status']=='BLANK' for q in s['questions'])
 checks.append(f'{count}Q prefixed QR import selects actual printed layout without a selected camera count')
 for kind in ['no-qr','missing-rows','qr-in-answers']:
  im=Image.open(blank).convert('RGB');draw=ImageDraw.Draw(im);m=json.loads((ROOT/f'output/new-sheets-1.1.41/calibration/{count}q-vector-map.json').read_text());w,h=m['pagePt'];sx,sy=1750/w,2480/h
  x,y,bw,bh=m['qrWhitePt'];box=(round(x*sx),round(y*sy),round((x+bw)*sx),round((y+bh)*sy))
  if kind=='no-qr' or kind=='qr-in-answers':
   patch=im.crop(box);draw.rectangle(box,fill='white')
   if kind=='qr-in-answers':im.paste(patch,(700,1200))
  else:
   for side in ['left','right']:
    for x,y in m['railsPt'][side]:draw.rectangle(((x-9)*sx,(y-9)*sy,(x+9)*sx,(y+9)*sy),fill='white')
  path=a.output/f'{count}-{kind}.png';im.save(path);s=read(f'{count}-{kind}',path,count)
  assert not s['success'] and not s['questions'],(count,kind,s['error'])
  checks.append(f'{count}Q {kind} does not produce answers')
new75=ROOT/'output/new-sheets-1.1.41/fixtures/source-75.png'
s=read('new-75-selected-as100',new75,100)
assert not s['success'] and not s['questions'] and s['error']=='printed_answer_layout_unconfirmed'
checks.append('Three-column 75Q cannot silently be read with four-column 100Q geometry despite identical T1 QR/rails')
s=read('new-75-without-persist',new75,75,persist=False)
assert s['success'] and not s['normalizedImagePath'] and not s['inkVerification']['applied'] and len(s['questions'])==75
assert not list(a.output.glob('.unmirror*'))
checks.append('Non-persisted 75Q read returns 75 actual answers and no temporary normalized image')
for f in a.output.glob('omr-normalized-*'):f.unlink()
(a.output/'report.json').write_text(json.dumps(dict(passed=True,checks=checks,sourceImagesUnchanged=True),indent=2)+'\n');print(len(checks),'contract checks passed',flush=True)
getattr(core,'_ZN2cv13setNumThreadsEi')(1)
