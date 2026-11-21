#!/usr/bin/env python3
"""Stress the native camera path and compiled Kotlin answer policy against PDF truth."""
import argparse, ctypes, gzip, hashlib, json, math, statistics, subprocess, time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
CORE='/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0'
JAVA='/home/you/.local/share/mise/installs/java/temurin-21/bin/java'

def run_group(arguments):
    count,cases,adapter,output,classpath,scanner=arguments
    cv2.setNumThreads(1);core=ctypes.CDLL(CORE);getattr(core,'_ZN2cv13setNumThreadsEi')(2)
    library=ctypes.CDLL(adapter)
    for name in ('roshd_prepare_camera_json','roshd_process_json'):
        f=getattr(library,name);f.argtypes=[ctypes.c_char_p];f.restype=ctypes.c_void_p
    library.roshd_free_string.argtypes=[ctypes.c_void_p]
    def call(name,request):
        p=getattr(library,name)(json.dumps(request,ensure_ascii=False).encode())
        try:return json.loads(ctypes.string_at(p))
        finally:library.roshd_free_string(p)
    verdict=subprocess.Popen([JAVA,'-Xmx128m','-cp',classpath,'StressVerdict'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    def judge(sheet,case):
        verdict.stdin.write(json.dumps(dict(sheet=sheet,count=count,qr=case['qr'],expectedOptions=case['expectedOptions']),ensure_ascii=False)+'\n');verdict.stdin.flush()
        line=verdict.stdout.readline()
        if not line:raise RuntimeError('Compiled Kotlin policy runner stopped')
        return json.loads(line)
    def complete(s):
        error=s.get('reprojectionError',float('nan'));rows=20 if count==40 else 25
        return bool(s.get('success') and s.get('qrCode',{}).get('found') and s.get('qrCode',{}).get('status')=='FOUND'
            and s.get('sheetType',{}).get('detected') and s.get('sheetType',{}).get('status')=='DETECTED'
            and s.get('sheetType',{}).get('code')==('T3' if count==40 else 'T1') and s.get('activeQuestionCount')==count
            and s.get('matchedRowsUsed')==rows and s.get('homographyStrategyUsed')==f'all_{rows}_registration_rows'
            and math.isfinite(error) and 0<=error<=1.5 and s.get('normalizedImagePath') and s.get('annotationGeometry',{}).get('width',0)>0
            and sorted(q.get('question',0) for q in s.get('questions',[]))==list(range(1,count+1)))
    def prefer(current,candidate):
        a=current.get('qrCode',{});b=candidate.get('qrCode',{})
        if a.get('found') and a.get('status')=='FOUND' and b.get('found') and b.get('status')=='FOUND' and a.get('text')!=b.get('text'):return False
        return not complete(current) and (complete(candidate) or (not current.get('success') and candidate.get('success') and
            candidate.get('activeQuestionCount')==count and b.get('found') and b.get('status')=='FOUND' and b.get('text') and candidate.get('sheetType',{}).get('code')==('T3' if count==40 else 'T1')))
    names={40:['yse_omr_a5_t3_40q_v1.json'],75:['yse_omr_a5_t1_75q_20261115.json','yse_omr_a5_t1_100q_v2.json'],100:['yse_omr_a5_t1_100q_v2.json']}[count]
    rows=[];group=Path(output)/str(count);group.mkdir(parents=True,exist_ok=True)
    journal=(group/'rows.jsonl').open('w')
    try:
        for case in cases:
            folder=group/case['name'];folder.mkdir(exist_ok=True)
            source=Path(case['path']);digest=hashlib.sha256(source.read_bytes()).hexdigest();assert digest==case['sha256']
            start=time.monotonic();attempts=[]
            framed=folder/'framed.bmp';framing=call('roshd_prepare_camera_json',dict(imagePath=str(source),outputPath=str(framed.resolve())))
            request=dict(templateJsonPaths=[str(ROOT/'app/src/main/assets/templates'/n) for n in names],maxInputDimension=2000,
                preferCalibratedHeader=True,returnNormalizedImages=True,includeQuestions=True,includeRegistrationGeometry=True,outputDirectory=str(folder.resolve()))
            def read(path):
                at=time.monotonic();r=call('roshd_process_json',dict(request,pages=[dict(pageNumber=1,imagePath=str(path),activeQuestionCount=count)]))
                s=(r.get('sheets') or [{}])[0];attempts.append(dict(path=path.name,ms=(time.monotonic()-at)*1000,success=s.get('success'),complete=complete(s),error=s.get('error')))
                return s
            chosen=framed if framing.get('applied') else source
            sheet=read(chosen)
            if not complete(sheet) and not framing.get('resampled') and framing.get('reason') in ('four_printed_squares','already_framed'):
                rectified=folder/'rectified.bmp';retry_frame=call('roshd_prepare_camera_json',dict(imagePath=str(source),outputPath=str(rectified.resolve()),forceRectification=True))
                framing['rectificationRetry']=retry_frame
                if retry_frame.get('applied'):
                    retry=read(rectified)
                    if prefer(sheet,retry):sheet=retry;chosen=rectified
            if framing.get('applied') and not complete(sheet):
                retry=read(source)
                if prefer(sheet,retry):sheet=retry;chosen=source
            document=None
            if scanner and not complete(sheet):
                path=folder/'document.png'
                document=json.loads(subprocess.run([scanner,str(source),str(path.resolve())],capture_output=True,text=True,check=True).stdout)
                if document.get('applied'):
                    retry=read(path)
                    if prefer(sheet,retry):sheet=retry;chosen=path
            result=judge(sheet,case)
            assert result['geometry']==complete(sheet),'Host admission differs from compiled Android policy'
            projection_error=None
            if sheet.get('registrationGeometry') and sheet.get('annotationGeometry'):
                iw,ih=Image.open(chosen).size
                pre=np.eye(3)
                if chosen in (framed,folder/'rectified.bmp'):
                    f=framing.get('rectificationRetry') if chosen.name=='rectified.bmp' else framing
                    if f.get('resampled'):pre=np.array(f['sourceToPrepared']).reshape(3,3)
                    else:
                        x,y,_,_=f['crop'];pre=np.array([[1,0,-x],[0,1,-y],[0,0,1]])
                elif chosen.name=='document.png':
                    sw,sh=Image.open(source).size;quad=np.array(document['detection']['corners'],np.float32).reshape(4,2);center=quad.mean(axis=0)
                    quad=np.clip(center+(quad-center)*1.012,0,1)*[sw-1,sh-1]
                    pre=cv2.getPerspectiveTransform(quad.astype(np.float32),np.float32([[0,0],[iw-1,0],[iw-1,ih-1],[0,ih-1]]))
                factor=min(1,2000/max(iw,ih));rw,rh=round(iw*factor),round(ih*factor);resize=np.diag([rw/iw,rh/ih,1.]);rotation=np.eye(3)
                warnings=sheet.get('warnings',[])
                if 'input_auto_rotated_90_clockwise' in warnings:rotation=np.array([[0,-1,rh-1],[1,0,0],[0,0,1]])
                elif 'input_auto_rotated_180' in warnings:rotation=np.array([[-1,0,rw-1],[0,-1,rh-1],[0,0,1]])
                elif 'input_auto_rotated_90_counterclockwise' in warnings:rotation=np.array([[0,1,0],[-1,0,rw-1],[0,0,1]])
                h=np.array(sheet['registrationGeometry']['homography']).reshape(3,3)@rotation@resize@pre@np.array(case['mmToImage'])
                truth=json.loads(Path(case['vectorMap']).read_text());points=np.float32([o['center_mm'] for q in truth['questions'] for o in q['options']])
                normalized=cv2.perspectiveTransform(points[None],h)[0];d=np.linalg.norm(normalized-points*np.array([1750/148,2480/210]),axis=1)
                projection_error=dict(maxPx=round(float(d.max()),4),rmsPx=round(float(np.sqrt(np.mean(d*d))),4),centers=len(points))
            result.update(name=case['name'],count=count,pattern=case['pattern'],effect=case['effect'],rotationXYZ=case['rotationXYZ'],
                framing=framing,document=document,attempts=attempts,selectedPath=chosen.name,projectionError=projection_error,hostMs=round((time.monotonic()-start)*1000,2))
            with gzip.open(folder/'sheet.json.gz','wt',encoding='utf-8') as f:json.dump(sheet,f,ensure_ascii=False)
            (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            journal.write(json.dumps(result)+'\n');journal.flush();rows.append(result)
            assert hashlib.sha256(source.read_bytes()).hexdigest()==digest
            for p in folder.iterdir():
                if p.suffix.lower() in ('.jpg','.png','.bmp'):p.unlink()
            if len(rows)%10==0:print(json.dumps(dict(count=count,cases=len(rows),ready=sum(r['ready'] for r in rows),wrong=sum(r['wrong'] for r in rows),unsafe=sum(r['unsafeWrong'] for r in rows),unsafeSheets=sum(r['unsafeSheet'] for r in rows))),flush=True)
    finally:
        journal.close();verdict.stdin.close();verdict.wait();getattr(core,'_ZN2cv13setNumThreadsEi')(1)
    (group/'report.json').write_text(json.dumps(dict(count=count,cases=len(rows),checks=rows),indent=2)+'\n')
    return rows

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--adapter',type=Path,required=True)
    p.add_argument('--classpath',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--only');p.add_argument('--workers',type=int,default=3)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    cases=json.loads(a.manifest.read_text())['cases']
    if a.only:
        names=set(a.only.split(','));cases=[c for c in cases if c['name'] in names]
    groups=[(n,[c for c in cases if c['count']==n],str(a.adapter.resolve()),str(a.output.resolve()),a.classpath.read_text(),str(ROOT/'output/source-port-1.1.31/build/document-scanner/document-scanner')) for n in (40,75,100) if any(c['count']==n for c in cases)]
    with ProcessPoolExecutor(max_workers=a.workers) as pool:rows=[r for group in pool.map(run_group,groups) for r in group]
    summary=dict(platform='Linux synthetic camera photographs; native C++ adapter + actual compiled Kotlin parser/review policy; not physical phone latency',
        cases=len(rows),questions=sum(r['count'] for r in rows),ready=sum(r['ready'] for r in rows),geometry=sum(r['geometry'] for r in rows),identity=sum(r['identity'] for r in rows),
        exactSheets=sum(r['ready'] and r['wrong']==0 for r in rows),automaticExactSheets=sum(r['ready'] and r['wrong']==0 and r['review']==0 for r in rows),
        reviewSheets=sum(r['ready'] and r['review']>0 for r in rows),rejectedSheets=sum(not r['ready'] for r in rows),
        correct=sum(r['correct'] for r in rows),wrong=sum(r['wrong'] for r in rows),unsafeWrong=sum(r['unsafeWrong'] for r in rows),unsafeSheets=sum(r['unsafeSheet'] for r in rows),
        blankFalsePositive=sum(r['blankFalsePositive'] for r in rows),missedMarks=sum(r['missedMarks'] for r in rows),medianHostMs=statistics.median(r['hostMs'] for r in rows),
        checks=rows)
    (a.output/'report.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps({k:v for k,v in summary.items() if k!='checks'},indent=2),flush=True)

if __name__=='__main__':main()
