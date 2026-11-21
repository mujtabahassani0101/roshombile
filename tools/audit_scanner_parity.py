#!/usr/bin/env python3
"""Fail if a visual/camera release changes the preserved native scanner libraries, models or templates."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

parser=argparse.ArgumentParser()
parser.add_argument('--baseline-apk',type=Path,required=True)
parser.add_argument('--candidate-apk',type=Path,required=True)
parser.add_argument('--baseline-source',type=Path)
parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
args=parser.parse_args()

def protected(name):
    return name.startswith(('lib/arm64-v8a/','assets/scanner/','assets/templates/')) and (
        name.startswith(('assets/scanner/','assets/templates/')) or Path(name).name in {
            'liblajvard_omr_mobile.so','libpaperframe_inference.so','libMNN.so','libroshd_jni.so','libopencv_java4.so','libc++_shared.so'})

def entries(path):
    with ZipFile(path) as z:
        return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if protected(n)}

before=entries(args.baseline_apk);after=entries(args.candidate_apk)
assert before and before==after,'Protected APK engine/model/template content changed'
report={'baselineApk':args.baseline_apk.name,'candidateApk':args.candidate_apk.name,'protectedApkFiles':before,'identical':True}
if args.baseline_source:
    roots=('native/','app/src/main/cpp/','app/src/main/jniLibs/','app/src/main/assets/scanner/','app/src/main/assets/templates/','app/src/main/kotlin/dev/','app/src/main/kotlin/com/haseebullahjan/roshd/platform/')
    prefix='RoshdKotlin/'
    with ZipFile(args.baseline_source) as z:
        original={n[len(prefix):] for n in z.namelist() if n.startswith(prefix) and not n.endswith('/') and n[len(prefix):].startswith(roots)}
        current={p.relative_to(args.root).as_posix() for root in roots for p in (args.root/root).rglob('*') if p.is_file()}
        assert original==current,'Protected source files were added/removed'
        changed=[n for n in sorted(original) if z.read(prefix+n)!=(args.root/n).read_bytes()]
        assert not changed,f'Protected source changed: {changed}'
        report['protectedSourceFilesIdentical']=len(original)
        report['baselineSource']=args.baseline_source.name
        pipeline=('app/src/main/kotlin/com/haseebullahjan/roshd/NativeHostActivity.kt',
                  'app/src/main/kotlin/com/haseebullahjan/roshd/DocumentImagePreparation.kt',
                  'app/src/main/kotlin/com/haseebullahjan/roshd/messenger/ExamsController.kt')
        for name in pipeline:
            assert z.read(prefix+name)==(args.root/name).read_bytes(),f'Recognition/payload pipeline changed: {name}'
        report['unchangedPipelineSources']={n:hashlib.sha256((args.root/n).read_bytes()).hexdigest() for n in pipeline}
print(json.dumps(report,indent=2))
