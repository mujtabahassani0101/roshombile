#!/usr/bin/env python3
"""Verify exact upstream sources and, optionally, exact engines in a Roshd APK."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source-root',type=Path,default=Path('/home/you/Projects/دانشمند جوان موبایل'))
p.add_argument('--apk',type=Path)
p.add_argument('--report',type=Path,required=True)
a=p.parse_args()
def digest(data):return hashlib.sha256(data).hexdigest()
protected={}
for relative in ['native/omr_engine','native/document_scanner']:
    src=a.source_root/relative;dst=ROOT/relative
    originals={f.relative_to(src).as_posix():f for f in src.rglob('*') if f.is_file()}
    copies={f.relative_to(dst).as_posix():f for f in dst.rglob('*') if f.is_file()}
    assert originals.keys()==copies.keys(),f'Added or missing upstream source in {relative}'
    for name,file in originals.items():
        expected=file.read_bytes();assert expected==copies[name].read_bytes(),f'Changed upstream file: {relative}/{name}'
        protected[f'{relative}/{name}']=digest(expected)
for relative in ['DocumentImagePreparation.kt','scanner/DocumentScanner.kt','scanner/DocumentCaptureGate.kt']:
    name='app/src/main/kotlin/com/haseebullahjan/youngscientist/'+relative
    data=(a.source_root/name).read_bytes();assert data==(ROOT/name).read_bytes(),f'Changed copied Kotlin pipeline: {name}'
    protected[name]=digest(data)
name='app/src/main/kotlin/com/haseebullahjan/youngscientist/ui/DocumentEditorFragment.kt'
expected=(a.source_root/name).read_text().replace('com.haseebullahjan.youngscientist','com.haseebullahjan.roshd')
expected=expected.replace('import com.haseebullahjan.roshd.DocumentImagePreparation','import com.haseebullahjan.youngscientist.DocumentImagePreparation')
expected=expected.replace('import com.haseebullahjan.roshd.scanner.*','import com.haseebullahjan.youngscientist.scanner.*')
assert expected==(ROOT/'app/src/main/kotlin/com/haseebullahjan/roshd/ui/DocumentEditorFragment.kt').read_text(),'Editor differs beyond package imports'
vendor=json.loads((ROOT/'native/roshd_adapter/vendor/provenance.json').read_text())
for item in vendor:
    data=(ROOT/'native/roshd_adapter/vendor'/item['abi']/item['library']).read_bytes()
    assert digest(data)==item['sha256']
    with ZipFile(item['sourceApk']) as z:assert data==z.read(f'lib/{item["abi"]}/{item["library"]}')
apk_entries={}
if a.apk:
    with ZipFile(a.apk) as z:
        names=z.namelist();assert z.testzip() is None
        assert not any('libMNN.so' in n or 'libpaperframe_inference.so' in n or n.startswith('assets/scanner/') for n in names)
        for item in vendor:
            entry=f'lib/{item["abi"]}/{item["library"]}'
            if entry in names:
                data=z.read(entry);assert digest(data)==item['sha256'],f'APK engine differs from source APK: {entry}'
                apk_entries[entry]=item['sha256']
        assert len(apk_entries)>=2,'Copied engine libraries are missing from the APK'
report=dict(sourceRoot=str(a.source_root),exactSourceFiles=len(protected),sourceSha256=protected,
            editorIdenticalExceptPackageImports=True,exactVendorLibraries=vendor,exactApkEngines=apk_entries,
            adaptation='Roshd bridge, current PDF calibration, 75 active questions, verified historical QR binding, durable queue and colored sheets are outside the copied engines.')
a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'exactSourceFiles':len(protected),'exactApkEngines':apk_entries},indent=2))
