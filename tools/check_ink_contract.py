#!/usr/bin/env python3
"""Check native verification boundaries and strict JSON against real fixtures."""
import argparse,ctypes,hashlib,json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def strict_pairs(pairs):
    result={}
    for key,value in pairs:
        assert key not in result,'Duplicate JSON key: '+key
        result[key]=value
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adapter',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    core=ctypes.CDLL('/home/you/.local/opt/yse-opencv4/usr/lib/libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
    lib=ctypes.CDLL(str(a.adapter.resolve()));lib.roshd_process_json.argtypes=[ctypes.c_char_p]
    lib.roshd_process_json.restype=ctypes.c_void_p;lib.roshd_free_string.argtypes=[ctypes.c_void_p]
    random=json.loads((ROOT/'output/random-1.1.27/manifest.json').read_text())['cases']
    def read(case,enabled=True,persist=True,count=None):
        source=Path(case['path']);before=hashlib.sha256(source.read_bytes()).hexdigest()
        request=dict(templateJsonPaths=[str(ROOT/'app/src/main/assets/templates'/n) for n in ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json')],
            preferCalibratedHeader=True,maxInputDimension=2000,enableInkVerification=enabled,returnNormalizedImages=persist,
            outputDirectory=str(a.output.resolve()),pages=[dict(pageNumber=1,imagePath=str(source),activeQuestionCount=count or case['count'])])
        pointer=lib.roshd_process_json(json.dumps(request,ensure_ascii=False).encode())
        try:result=json.loads(ctypes.string_at(pointer),object_pairs_hook=strict_pairs)['sheets'][0]
        finally:lib.roshd_free_string(pointer)
        assert before==hashlib.sha256(source.read_bytes()).hexdigest()
        return result
    checks=[]
    case=next(c for c in random if c['name']=='40q-05')
    original=read(case,enabled=False);verified=read(case)
    assert original['questions'][30]['status']=='BLANK'
    recovered=verified['questions'][30]
    assert recovered['status']=='MARKED' and recovered['selectedOptionNumber']==case['answers'][30]
    assert recovered['needsReview'] and recovered['reviewReason']=='ink_visibility_recovered'
    assert recovered['sourceOriginalStatus']=='BLANK' and recovered['verificationPasses']>=2
    assert original['qrCode']==verified['qrCode'] and original['annotationGeometry']==verified['annotationGeometry']
    for before,after in zip(original['questions'],verified['questions']):
        if before['status']=='MARKED':assert before['selectedOptionNumber']==after['selectedOptionNumber']
    checks.append('recovered choice requires review; identity, geometry and existing selected choices retained')
    no_image=read(case,persist=False)
    assert not no_image['inkVerification']['applied']
    assert [(q['status'],q['selectedOptionNumber']) for q in no_image['questions']]==[(q['status'],q['selectedOptionNumber']) for q in original['questions']]
    checks.append('no normalized image keeps the original reading without a partial verification')
    mismatch=read(case,count=75)
    assert not mismatch['success'] and not mismatch['questions'] and not mismatch['inkVerification']['applied']
    checks.append('selected family mismatch cannot be rescued by ink verification')
    seventy_five=read(next(c for c in random if c['name']=='75q-01'))
    assert seventy_five['success'] and seventy_five['activeQuestionCount']==75
    assert [q['question'] for q in seventy_five['questions']]==list(range(1,76))
    assert [q['questionNumber'] for q in seventy_five['annotationGeometry']['questions']]==list(range(1,76))
    checks.append('inactive 76–100 are excluded from verification and annotation')
    marks=json.loads((ROOT/'output/mark-accuracy-1.1.30/fixtures/manifest.json').read_text())['cases']
    multiple=next(c for c in marks if c['name']=='40-two-marks-clear')
    multi=read(multiple)
    for q,e in zip(multi['questions'],multiple['expected']):
        if e is None:continue
        if q.get('verifiedMultipleOptions'):
            assert sorted(q['verifiedMultipleOptions'])==sorted([e,e%4+1])
            assert q['verificationPasses']>=2 and q['reviewReason']=='multiple_ink_confirmed'
        else:assert q.get('needsReview') or q['confidence']<.85
    checks.append('multiple marks require exact corroborated options or retain uncertainty')
    report=dict(passed=True,checks=checks,strictJson=True,sourceImagesUnchanged=True)
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    getattr(core,'_ZN2cv13setNumThreadsEi')(1)

if __name__=='__main__':main()
