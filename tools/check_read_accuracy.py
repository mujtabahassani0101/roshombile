#!/usr/bin/env python3
"""Replay known answers through the production Roshd adapter, without a phone.

Answers come from independent fixture manifests. Originals are hash checked.
Live-phone accuracy or latency cannot be inferred from this synthetic corpus.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import time
import math
import subprocess
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adapter', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--only', help='Comma-separated case names')
    parser.add_argument('--manifest', type=Path, help='Use a separate holdout manifest')
    parser.add_argument('--disable-verification', action='store_true')
    parser.add_argument('--scanner', type=Path, help='Exercise the unchanged document fallback too')
    parser.add_argument('--legacy-selection', action='store_true', help='Reproduce pre-1.1.39 retry replacement')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    core = ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0')
    getattr(core, '_ZN2cv13setNumThreadsEi')(4)
    library = ctypes.CDLL(str(args.adapter.resolve()))
    for name in ('roshd_prepare_camera_json', 'roshd_process_json'):
        function = getattr(library, name)
        function.argtypes = [ctypes.c_char_p]
        function.restype = ctypes.c_void_p
    library.roshd_free_string.argtypes = [ctypes.c_void_p]

    def call(name, request):
        pointer = getattr(library, name)(json.dumps(request, ensure_ascii=False).encode())
        try:
            return json.loads(ctypes.string_at(pointer))
        finally:
            library.roshd_free_string(pointer)

    if args.manifest:
        cases = json.loads(args.manifest.read_text())['cases']
    else:
        random = json.loads((ROOT/'output/random-1.1.27/manifest.json').read_text())['cases']
        cases = list(random)
        for case in random:
            if int(case['name'].split('-')[-1]) >= 6:
                continue
            for effect in ('dim', 'shadow'):
                photo = ROOT/'output/guided-scan-1.1.29/scenes'/f'{case["name"]}-{effect}.jpg'
                cases.append(dict(case, name=f'{case["name"]}-{effect}', path=str(photo), effect=effect,
                                  sha256=hashlib.sha256(photo.read_bytes()).hexdigest()))
        cases += json.loads((ROOT/'output/mark-accuracy-1.1.30/fixtures/manifest.json').read_text())['cases']
    if args.only:
        names = set(args.only.split(','))
        cases = [case for case in cases if case['name'] in names]
    assert cases, 'No fixtures selected'
    baseline = {row['name']: row for row in json.loads(args.baseline.read_text())['checks']} if args.baseline else {}
    rows = []
    journal = (args.output/'rows.jsonl').open('w')
    for case in cases:
        source = Path(case['path'])
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        assert case.get('sha256', digest) == digest, case['name']
        folder = args.output/case['name'];folder.mkdir(exist_ok=True)
        framed = folder/'framed.bmp'
        at = time.monotonic()
        framing = call('roshd_prepare_camera_json', dict(imagePath=str(source), outputPath=str(framed.resolve())))
        request = dict(
            templateJsonPaths=[str(ROOT/'app/src/main/assets/templates'/name) for name in
                               ('yse_omr_a5_t1_100q_v2.json', 'yse_omr_a5_t3_40q_v1.json','yse_omr_a5_t1_75q_20261115.json')],
            maxInputDimension=2000, preferCalibratedHeader=True, returnNormalizedImages=True,
            includeQuestions=True, includeRegistrationGeometry=True, enableInkVerification=not args.disable_verification,
            outputDirectory=str(folder.resolve()))
        if case.get('qrBindings'):request['qrBindings']=case['qrBindings']

        def read(path):
            result = call('roshd_process_json', dict(request, pages=[dict(pageNumber=1, imagePath=str(path), activeQuestionCount=case['count'])]))
            return (result.get('sheets') or [{}])[0]

        chosen_path=framed if framing.get('applied') else source
        sheet = read(chosen_path)
        required = 20 if case['count'] == 40 else 25
        def complete(s):
            error=s.get('reprojectionError',float('nan'))
            return bool(s.get('success') and s.get('qrCode',{}).get('found') and s.get('qrCode',{}).get('status')=='FOUND'
                and s.get('sheetType',{}).get('detected') and s.get('sheetType',{}).get('status')=='DETECTED'
                and s.get('sheetType',{}).get('code')==('T3' if case['count']==40 else 'T1')
                and s.get('activeQuestionCount')==case['count'] and s.get('matchedRowsUsed')==required
                and s.get('homographyStrategyUsed')==f'all_{required}_registration_rows'
                and math.isfinite(error) and 0<=error<=1.5 and s.get('normalizedImagePath')
                and s.get('annotationGeometry',{}).get('width',0)>0
                and sorted(q.get('question',0) for q in s.get('questions',[]))==list(range(1,case['count']+1)))
        def prefer(current,candidate):
            current_qr=current.get('qrCode',{});candidate_qr=candidate.get('qrCode',{})
            if current_qr.get('found') and current_qr.get('status')=='FOUND' and candidate_qr.get('found') and candidate_qr.get('status')=='FOUND' and current_qr.get('text')!=candidate_qr.get('text'):return False
            return not complete(current) and (complete(candidate) or (not current.get('success') and candidate.get('success')
                and candidate.get('activeQuestionCount')==case['count'] and candidate.get('qrCode',{}).get('text')
                and candidate.get('sheetType',{}).get('code')==('T3' if case['count']==40 else 'T1')))
        if not args.legacy_selection and not complete(sheet) and not framing.get('resampled') and framing.get('reason') in ('four_printed_squares','already_framed'):
            rectified=folder/'rectified.bmp'
            retry_frame=call('roshd_prepare_camera_json',dict(imagePath=str(source),outputPath=str(rectified.resolve()),forceRectification=True))
            framing['rectificationRetry']=retry_frame
            if retry_frame.get('applied'):
                retry=read(rectified)
                if prefer(sheet,retry):sheet=retry;chosen_path=rectified
        if framing.get('applied') and not complete(sheet):
            retry=read(source)
            if args.legacy_selection or prefer(sheet,retry): sheet=retry;chosen_path=source
        scanner=None
        if args.scanner and not complete(sheet):
            document=folder/'document.png'
            scanner=json.loads(subprocess.run([str(args.scanner.resolve()),str(source),str(document)],capture_output=True,text=True,check=True).stdout)
            if scanner.get('applied'):
                retry=read(document)
                if (retry.get('success') if args.legacy_selection else prefer(sheet,retry)):
                    sheet=retry;chosen_path=document
        questions = {q['question']: q for q in sheet.get('questions', [])}
        expected = case.get('expected', case.get('answers'))
        errors=[];correct=review=unsafe=blank_false_positive=0
        for number, choice in enumerate(expected, 1):
            question=questions.get(number,{})
            flagged=bool(question) and (question.get('needsReview',False) or question.get('confidence',0)<.85 or not complete(sheet))
            double=case.get('pattern')=='two-marks' and choice is not None
            ok=bool(question) and ((question.get('status')=='MULTI' or flagged) if double else (
                question.get('status')=='BLANK' if choice is None else
                question.get('status')=='MARKED' and question.get('selectedOptionNumber')==choice))
            correct+=int(ok);review+=int(flagged)
            if not ok:
                unsafe+=int(bool(question) and not flagged)
                blank_false_positive+=int(choice is None and question.get('status')=='MARKED')
                errors.append(dict(question=number,expected=choice,status=question.get('status'),actual=question.get('selectedOptionNumber'),review=flagged))
        projection_error=None
        if case.get('mmToImage') and sheet.get('registrationGeometry'):
            source_image=cv2.imread(str(source));sh,sw=source_image.shape[:2]
            ih,iw=cv2.imread(str(chosen_path)).shape[:2]
            pre=np.eye(3)
            if chosen_path in (framed,folder/'rectified.bmp'):
                selected_frame=framing.get('rectificationRetry') if chosen_path.name=='rectified.bmp' else framing
                if selected_frame.get('resampled'):pre=np.array(selected_frame['sourceToPrepared']).reshape(3,3)
                else:
                    x,y,_,_=selected_frame['crop'];pre=np.array([[1,0,-x],[0,1,-y],[0,0,1]])
            elif scanner and chosen_path.name=='document.png':
                quad=np.array(scanner['detection']['corners'],np.float32).reshape(4,2)
                center=quad.mean(axis=0);quad=np.clip(center+(quad-center)*1.012,0,1)*[sw-1,sh-1]
                pre=cv2.getPerspectiveTransform(quad.astype(np.float32),np.float32([[0,0],[iw-1,0],[iw-1,ih-1],[0,ih-1]]))
            factor=min(1,2000/max(iw,ih));rw,rh=round(iw*factor),round(ih*factor)
            resize=np.diag([rw/iw,rh/ih,1.]);rotation=np.eye(3)
            warnings=sheet.get('warnings',[])
            if 'roshd_mirrored_image_corrected' in warnings:pre=np.array([[-1,0,iw-1],[0,1,0],[0,0,1]])@pre
            if 'input_auto_rotated_90_clockwise' in warnings:rotation=np.array([[0,-1,rh-1],[1,0,0],[0,0,1]])
            elif 'input_auto_rotated_180' in warnings:rotation=np.array([[-1,0,rw-1],[0,-1,rh-1],[0,0,1]])
            elif 'input_auto_rotated_90_counterclockwise' in warnings:rotation=np.array([[0,1,0],[-1,0,rw-1],[0,0,1]])
            matrix=np.array(sheet['registrationGeometry']['homography']).reshape(3,3)@rotation@resize@pre@np.array(case['mmToImage'])
            supplier=json.loads(Path(case['vectorMap']).read_text()) if case.get('vectorMap') else json.loads((ROOT/f'qa/{40 if case["count"]==40 else 100}q-supplied-map-1.1.26.json').read_text())
            points=np.float32([o['center_mm'] for q in supplier['questions'][:case['count']] for o in q['options']])
            projected=cv2.perspectiveTransform(points[None],matrix)[0]
            distances=np.linalg.norm(projected-points*np.array([1750/148,2480/210]),axis=1)
            projection_error=dict(maxPx=round(float(distances.max()),4),rmsPx=round(float(np.sqrt(np.mean(distances**2))),4),bubbleCenters=len(points))
        row=dict(name=case['name'],count=case['count'],success=bool(sheet.get('success')),
                 geometry=bool(complete(sheet)),identity=sheet.get('qrCode',{}).get('text')==case['qr'],
                 correct=correct,wrong=len(errors),review=review,unsafeWrong=unsafe,
                 blankFalsePositive=blank_false_positive,errors=errors,
                 verification=sheet.get('inkVerification'),framing=framing,scanner=scanner,
                 rotationXYZ=case.get('rotationXYZ'),projectionError=projection_error,
                 selectedPath=chosen_path.name,error=sheet.get('error'),
                 hostMs=round((time.monotonic()-at)*1000,2))
        if case['name'] in baseline:
            before=baseline[case['name']]
            row['regressedQuestions']=[error['question'] for error in errors if error['question'] not in {e['question'] for e in before['errors']}]
        rows.append(row)
        journal.write(json.dumps(row)+'\n');journal.flush()
        assert digest == hashlib.sha256(source.read_bytes()).hexdigest()
        # Each scene has its own normalized output and there are no unreviewed
        # test artifacts in the APK. Keep results, remove large derived images.
        for image in folder.glob('*'):
            if image.suffix.lower() in ('.jpg','.png','.bmp'):image.unlink()
        if len(rows)%15==0:print(json.dumps(dict(cases=len(rows),wrong=sum(r['wrong'] for r in rows),unsafe=sum(r['unsafeWrong'] for r in rows))),flush=True)
    report=dict(platform='Linux synthetic photographs; no phone',cases=len(rows),
                questions=sum(r['count'] for r in rows),correct=sum(r['correct'] for r in rows),
                wrong=sum(r['wrong'] for r in rows),unsafeWrong=sum(r['unsafeWrong'] for r in rows),
                blankFalsePositive=sum(r['blankFalsePositive'] for r in rows),
                completeGeometry=sum(r['geometry'] for r in rows),identity=sum(r['identity'] for r in rows),
                regressedQuestions=sum(len(r.get('regressedQuestions',[])) for r in rows),checks=rows)
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    journal.close()
    print(json.dumps({key:value for key,value in report.items() if key!='checks'},indent=2),flush=True)


if __name__=='__main__':
    main()
