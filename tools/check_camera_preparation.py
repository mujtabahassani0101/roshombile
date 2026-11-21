#!/usr/bin/env python3
"""Exercise the production crop ABI then the unchanged OMR on known-answer frames."""
import argparse,ctypes,hashlib,json,time
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

p=argparse.ArgumentParser();p.add_argument('--adapter',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
core=ctypes.CDLL('libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
lib=ctypes.CDLL(str(a.adapter.resolve()))
for name in ('roshd_prepare_camera_json','roshd_process_json'):
    f=getattr(lib,name);f.argtypes=[ctypes.c_char_p];f.restype=ctypes.c_void_p
lib.roshd_free_string.argtypes=[ctypes.c_void_p]
def call(name,request):
    t=time.monotonic();ptr=getattr(lib,name)(json.dumps(request,ensure_ascii=False).encode())
    try:return json.loads(ctypes.string_at(ptr)),round((time.monotonic()-t)*1000,2)
    finally:lib.roshd_free_string(ptr)
def prepare(path,name):
    output=a.output/(name+'.bmp')
    result,elapsed=call('roshd_prepare_camera_json',dict(imagePath=str(path),outputPath=str(output.resolve())))
    if result['applied']:
        original=cv2.imread(str(path));framed=cv2.imread(str(output))
        if result.get('resampled'):
            w,h=result['width'],result['height'];matrix=np.array(result['sourceToPrepared']).reshape(3,3)
            expected=cv2.warpPerspective(original,matrix,(w,h),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT,borderValue=(255,255,255))
            mx,my=max(1,round(.7*w/148)),max(1,round(.7*h/210))
            expected[:my]=255;expected[-my:]=255;expected[:,:mx]=255;expected[:,-mx:]=255
            # Matrix JSON rounding can move interpolation boundaries by <1e-8.
            assert np.max(np.abs(framed.astype(int)-expected.astype(int)))<=8,name+' unexpected resampling'
            assert not result['printedPixelsPreserved'] and result['losslessEncoding']
        else:
            x,y,w,h=result['crop'];mask=np.zeros((h,w),np.uint8)
            cv2.fillConvexPoly(mask,np.array(result['protectedPolygon'],np.int32),255)
            assert np.array_equal(framed[mask>0],original[y:y+h,x:x+w][mask>0]),name+' changed printed pixels'
            assert np.all(framed[mask==0]==255),name+' retained outside background'
    return output if result['applied'] else path,result,elapsed
def engine(path,count):
    result,elapsed=call('roshd_process_json',dict(templateJsonPaths=[str(root/'app/src/main/assets/templates'/n) for n in ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json')],pages=[dict(pageNumber=1,imagePath=str(path),activeQuestionCount=count)],maxInputDimension=2000,returnNormalizedImages=True,returnAnnotationGeometry=True,includeQuestions=True,preferCalibratedHeader=True,outputDirectory=str((a.output/'normalized').resolve())))
    return result.get('sheets',[{}])[0],elapsed
cases=json.loads((root/'output/scan-latency-1.1.34/offset-fixtures/manifest.json').read_text())
random=json.loads((root/'output/random-1.1.27/manifest.json').read_text())['cases']
cases+= [c for c in random if int(c['name'].split('-')[-1])<=10]
for count in (40,75,100):
    reference=next(c for c in random if c['count']==count and c['name'].endswith('-01'))
    image=cv2.imread(reference['path']);h,w=image.shape[:2]
    transform=cv2.getPerspectiveTransform(np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]]),np.float32([[320,350],[1840,520],[1750,2700],[430,2550]]))
    path=a.output/f'{count}-camera-perspective.png'
    cv2.imwrite(str(path),cv2.warpPerspective(image,transform,(2200,3000),borderValue=(70,70,70)))
    cases.append(dict(reference,name=f'{count}-camera-perspective',path=str(path.resolve())))
report=[]
for c in cases:
    path=Path(c['path']);digest=hashlib.sha256(path.read_bytes()).hexdigest()
    prepared,meta,ms=prepare(path,c['name'])
    s,elapsed=engine(prepared,c['count'])
    baseline,baseline_ms=engine(path,c['count']) if meta['applied'] else (s,elapsed)
    questions=s.get('questions',[])
    matched=sum(q['status']=='MARKED' and q['selectedOptionNumber']==c['answers'][q['question']-1] for q in questions)
    baseline_matched=sum(q['status']=='MARKED' and q['selectedOptionNumber']==c['answers'][q['question']-1] for q in baseline.get('questions',[]))
    correct=s.get('success') and s.get('qrCode',{}).get('text')==c['qr'] and len(questions)==c['count'] and all(q['status']=='MARKED' and q['selectedOptionNumber']==c['answers'][q['question']-1] for q in questions)
    strong=s.get('matchedRowsUsed')==(20 if c['count']==40 else 25) and s.get('homographyStrategyUsed') in ('all_20_registration_rows','all_25_registration_rows') and 0<=s.get('reprojectionError',99)<=1.5
    assert digest==hashlib.sha256(path.read_bytes()).hexdigest()
    row=dict(name=c['name'],count=c['count'],framingApplied=meta['applied'],resampled=meta.get('resampled',False),framingReason=meta['reason'],framingMsHost=ms,engineMsHost=elapsed,baselineEngineMsHost=baseline_ms,matchedAnswers=matched,baselineMatchedAnswers=baseline_matched,allKnownAnswersMatched=bool(correct),fullRegistration=strong)
    report.append(row);print(json.dumps(row),flush=True)
    assert matched>=baseline_matched,c['name']+' answer regression'
    if 'shifted' in c['name'] or 'camera-perspective' in c['name']:assert correct and strong,c['name']
# Framing rejects incomplete, outlined, wrong-size, and competing registrations.
base=cv2.imread(str(root/'output/scan-latency-1.1.34/offset-fixtures/75-shifted-False.jpg'))
negative={}
missing=base.copy();missing[480:610,350:510]=255;negative['missing-square']=missing
blank=np.full(base.shape,230,np.uint8);negative['blank']=blank
outlined=blank.copy()
for x,y in ((390,515),(1740,515),(1740,2515),(390,2515)):
    cv2.rectangle(outlined,(x,y),(x+73,y+73),(0,0,0),4)
negative['square-outlines']=outlined
wrong=blank.copy()
for x,y in ((390,515),(1740,515),(1740,2515),(390,2515)):
    cv2.rectangle(wrong,(x,y),(x+40,y+40),(0,0,0),-1)
negative['wrong-size-squares']=wrong
negative['two-pages']=np.concatenate((base,base),axis=1)
negative['noise']=np.random.default_rng(33).integers(0,256,base.shape,np.uint8)
rejected=[]
for name,image in negative.items():
    path=a.output/(name+'.png');cv2.imwrite(str(path),image)
    _,meta,ms=prepare(path,name)
    rejected.append(dict(name=name,rejected=not meta['applied'],reason=meta['reason'],msHost=ms))
    print(json.dumps(rejected[-1]),flush=True)
    assert not meta['applied'],name
# Read EXIF orientation exactly as the production helper, without a JPEG rotation pass.
exif_path=a.output/'exif6.jpg';rgb=cv2.cvtColor(base,cv2.COLOR_BGR2RGB)
im=Image.fromarray(np.rot90(rgb,1));exif=im.getexif();exif[274]=6;im.save(exif_path,exif=exif,quality=98)
prepared,meta,ms=prepare(exif_path,'exif6');s,elapsed=engine(prepared,75)
expected=next(c for c in cases if c['name']=='75-shifted-False.jpg')
exif_ok=meta['applied'] and s['success'] and all(q['selectedOptionNumber']==expected['answers'][q['question']-1] for q in s['questions']) and len(s['questions'])==75
assert exif_ok
decoy=root/'output/scan-latency-1.1.34/offset-fixtures/body-qr-decoy.jpg'
prepared,meta,_=prepare(decoy,'body-qr-decoy');s,_=engine(prepared,100)
decoy_rejected=not s.get('success') and not s.get('qrCode',{}).get('found')
assert decoy_rejected
result=dict(cases=report,knownAnswers=sum(c['count'] for c in report),matchedAnswers=sum(c['matchedAnswers'] for c in report),negativeCases=rejected,exif6KnownAnswersMatched=exif_ok,bodyQrRejected=decoy_rejected,losslessCropPixelsPreserved=True,rectificationMatchesDeclaredTransform=True,sourceFilesUnchanged=True)
(a.output/'report.json').write_text(json.dumps(result,indent=2)+'\n')
getattr(core,'_ZN2cv13setNumThreadsEi')(1)
