#!/usr/bin/env python3
"""Reject false registration and missing identity/rows through production ABIs."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True);p.add_argument('--adapter',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    core=ctypes.CDLL('libopencv_core.so.4.13.0');getattr(core,'_ZN2cv13setNumThreadsEi')(4)
    lib=ctypes.CDLL(str(a.adapter.resolve()))
    for name in ('roshd_prepare_camera_json','roshd_process_json'):
        f=getattr(lib,name);f.argtypes=[ctypes.c_char_p];f.restype=ctypes.c_void_p
    lib.roshd_free_string.argtypes=[ctypes.c_void_p]
    def call(name,request):
        ptr=getattr(lib,name)(json.dumps(request,ensure_ascii=False).encode())
        try:return json.loads(ctypes.string_at(ptr))
        finally:lib.roshd_free_string(ptr)
    rows=[]
    for c in json.loads(a.manifest.read_text())['cases']:
        source=Path(c['path']);assert hashlib.sha256(source.read_bytes()).hexdigest()==c['sha256']
        folder=a.output/c['name'];folder.mkdir(exist_ok=True);framed=(folder/'framed.bmp').resolve()
        framing=call('roshd_prepare_camera_json',dict(imagePath=str(source),outputPath=str(framed)))
        if c['rejectPreparation']:assert not framing.get('applied'),c['name']
        complete=False
        if c['rejectComplete']:
            result=call('roshd_process_json',dict(preferCalibratedHeader=True,returnNormalizedImages=True,includeQuestions=True,
                maxInputDimension=2000,outputDirectory=str(folder.resolve()),
                templateJsonPaths=[str(ROOT/'app/src/main/assets/templates'/n) for n in ('yse_omr_a5_t1_100q_v2.json','yse_omr_a5_t3_40q_v1.json')],
                pages=[dict(imagePath=str(framed if framing.get('applied') else source),activeQuestionCount=c['count'],pageNumber=1)]))
            s=(result.get('sheets') or [{}])[0];required=20 if c['count']==40 else 25
            complete=bool(s.get('success') and s.get('qrCode',{}).get('found') and s.get('matchedRowsUsed')==required and
                          s.get('homographyStrategyUsed')==f'all_{required}_registration_rows' and 0<=s.get('reprojectionError',99)<=1.5)
            assert not complete,c['name']+' was accepted with missing identity/registration'
        assert hashlib.sha256(source.read_bytes()).hexdigest()==c['sha256']
        for file in folder.iterdir():
            if file.suffix.lower() in ('.bmp','.png','.jpg'):file.unlink()
        rows.append(dict(name=c['name'],framing=framing,rejectPreparationPassed=not c['rejectPreparation'] or not framing.get('applied'),
                         rejectCompletePassed=not c['rejectComplete'] or not complete))
    report=dict(cases=len(rows),passed=len(rows),originalsUnchanged=True,checks=rows)
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(dict(cases=len(rows),passed=len(rows))))
    getattr(core,'_ZN2cv13setNumThreadsEi')(1)


if __name__=='__main__':main()
