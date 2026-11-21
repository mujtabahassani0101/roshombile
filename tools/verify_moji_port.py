#!/usr/bin/env python3
"""Check the Moji scanner copy, retained Roshd engines and an optional release APK."""
import argparse, hashlib, json
from pathlib import Path
from zipfile import ZipFile

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path)
p.add_argument('--optimized-source-engine',action='store_true',help='Validate documented OMR speed updates and rebuilt binaries')
args=p.parse_args()
sha=lambda data:hashlib.sha256(data).hexdigest()
provenance=json.loads((root/'docs/MOJI_SCANNER_PROVENANCE.json').read_text())
source=Path(provenance['source'])
for item in provenance['files']:
    assert sha((root/item['destination']).read_bytes())==item['sha256'],item['destination']
    assert sha((source/item['source']).read_bytes())==item['sha256'],item['source']
intentional={}
if args.optimized_source_engine:
    for name in ['AMA_SPEED_PROVENANCE_1.1.48.json','AMA_SPEED_PROVENANCE_1.1.50.json','AMA_ACCURACY_PROVENANCE_1.1.51.json','AMA_SPEED_PROVENANCE_1.1.52.json','AMA_SPEED_PROVENANCE_1.1.54.json','AMA_SPEED_PROVENANCE_1.1.55.json','AMA_SPEED_PROVENANCE_1.1.56.json','AMA_GRADED_PROVENANCE_1.1.58.json']:
        intentional.update(json.loads((root/'docs'/name).read_text())['intentionalChanges'])
protected=[];changed=[]
for folder in ['native/omr_engine','native/roshd_adapter','native/document_scanner','app/src/main/assets/templates','app/src/main/assets/sheets']:
    for path in (root/folder).rglob('*'):
        if path.is_file():
            rel=path.relative_to(root)
            current=sha(path.read_bytes());original_path=root.parent/rel
            original=sha(original_path.read_bytes()) if original_path.is_file() else None
            if current!=original:
                expected=intentional.get(str(rel))
                assert expected and expected['originalSha256']==original and expected['optimizedSha256']==current,str(rel)
                if original is None:
                    # A new asset must be an exact copy of an existing protected
                    # source template, with both original and copy verified.
                    copied_from=expected['copiedFrom']
                    assert current==sha((root/copied_from).read_bytes())==sha((root.parent/copied_from).read_bytes()),str(rel)
                changed.append(str(rel))
            else:protected.append(str(rel))
assert set(changed)==set(intentional),'Documented native updates must match the actual source changes'
report={'unchangedMojiFiles':len(provenance['files']),'unchangedRoshdEngineAndSheetFiles':len(protected),'intentionalSourceChanges':changed}
if args.apk:
    entries={}
    with ZipFile(args.apk) as apk, ZipFile(source/'artifacts/MojiScan-3.0.2-paperframe.apk') as moji:
        for name in ['assets/scanner/border.mnn','assets/scanner/dewarp.mnn','lib/arm64-v8a/libMNN.so']:
            assert sha(apk.read(name))==sha(moji.read(name)),name
            entries[name]=sha(apk.read(name))
        bridge='lib/arm64-v8a/libpaperframe_inference.so'
        assert len(apk.read(bridge))>0
        entries[bridge]=sha(apk.read(bridge))
        for name in ['libdocument_scanner.so','liblajvard_omr_mobile.so']:
            n='lib/arm64-v8a/'+name
            expected=(root/'app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a'/name) if args.optimized_source_engine else (root/'native/roshd_adapter/vendor/arm64-v8a'/name)
            assert sha(apk.read(n))==sha(expected.read_bytes()),n
            entries[n]=sha(apk.read(n))
        for path in (root/'app/src/main/assets/templates').glob('*.json'):
            n='assets/templates/'+path.name
            assert sha(apk.read(n))==sha(path.read_bytes()),n
        report['apkEntries']=entries
        report['apkSha256']=sha(args.apk.read_bytes())
print(json.dumps(report,indent=2))
