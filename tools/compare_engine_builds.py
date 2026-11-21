#!/usr/bin/env python3
"""Byte-for-byte comparison of two host builds of the Roshd engine adapter (1.1.54).

Each build runs in its own process (both export the same library names) over the same
corpus. Results are compared with every timing field removed and every normalized image
replaced by its SHA-256, so "identical" means identical readings, review flags, geometry
and saved pixels.

  python3 tools/compare_engine_builds.py run  LIBDIR CORPUS.json OUT.jsonl [threads]
  python3 tools/compare_engine_builds.py diff BASE.jsonl OPT.jsonl

LIBDIR holds libroshd_engine_adapter.so and liblajvard_omr_mobile.so of one build.
The corpus is a JSON list of {name, count, path, bindings?, extra?, templates?}.
"""
import ctypes, hashlib, json, os, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENCV = Path('/home/you/.local/opt/yse-opencv4/usr/lib')
TEMPLATES = ROOT / 'app/src/main/assets/templates'
SELECTION = {40: ['yse_omr_a5_t3_40q_v1.json', 'yse_omr_a5_t3_40q_legacy_v1.json'],
             75: ['yse_omr_a5_t1_75q_20261115.json', 'yse_omr_a5_t1_100q_v2.json', 'yse_omr_a5_t1_100q_legacy_v2.json'],
             100: ['yse_omr_a5_t1_100q_v2.json', 'yse_omr_a5_t1_100q_legacy_v2.json']}


def scrub(value):
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if key.endswith('Ms'):
                continue
            if key == 'normalizedImagePath':
                path = Path(item) if item else None
                out[key] = hashlib.sha256(path.read_bytes()).hexdigest() if path and path.is_file() else None
                continue
            out[key] = scrub(item)
        return out
    if isinstance(value, list):
        return [scrub(item) for item in value]
    return value


def worker(libdir, corpus_path, out_path, threads):
    core = ctypes.CDLL(str(OPENCV / 'libopencv_core.so.4.13.0'))
    getattr(core, '_ZN2cv13setNumThreadsEi')(threads)
    lib = ctypes.CDLL(str(Path(libdir) / 'libroshd_engine_adapter.so'))
    lib.roshd_process_json.argtypes = [ctypes.c_char_p]
    lib.roshd_process_json.restype = ctypes.c_void_p
    lib.roshd_free_string.argtypes = [ctypes.c_void_p]
    corpus = json.loads(Path(corpus_path).read_text())
    work = Path(str(out_path) + '.work')
    with open(out_path, 'w') as out:
        for index, case in enumerate(corpus):
            request = dict(templateJsonPaths=[str(TEMPLATES / t) for t in case.get('templates') or SELECTION[case['count']]],
                           outputDirectory=str(work / str(index)), returnDebugImages=False, returnNormalizedImages=True,
                           returnAnnotationGeometry=True, compactResult=False, includeQuestions=True,
                           maxInputDimension=2000, preferCalibratedHeader=True, qrBindings=case.get('bindings', []),
                           pages=[dict(pageNumber=1, imagePath=case['path'], activeQuestionCount=case['count'])])
            request.update(case.get('extra', {}))
            started = time.perf_counter()
            pointer = lib.roshd_process_json(json.dumps(request, ensure_ascii=False).encode())
            elapsed = (time.perf_counter() - started) * 1000
            try:
                result = json.loads(ctypes.string_at(pointer))
            finally:
                lib.roshd_free_string(pointer)
            out.write(json.dumps(dict(name=case['name'], ms=elapsed, result=scrub(result)), ensure_ascii=False, sort_keys=True) + '\n')


def diff(base_path, opt_path):
    base = [json.loads(line) for line in open(base_path)]
    opt = [json.loads(line) for line in open(opt_path)]
    assert [b['name'] for b in base] == [o['name'] for o in opt], 'corpora differ'
    differing = [b['name'] for b, o in zip(base, opt) if b['result'] != o['result']]
    report = dict(inputs=len(base), identical=len(base) - len(differing), differing=differing,
                  successful=sum(bool(b['result'].get('sheets', [{}])[0].get('success')) for b in base),
                  baseMs=round(sum(b['ms'] for b in base)), optimizedMs=round(sum(o['ms'] for o in opt)))
    print(json.dumps(report, indent=2))
    return 0 if not differing else 1


if __name__ == '__main__':
    if sys.argv[1] == 'run':
        if os.environ.get('ROSHD_COMPARE_CHILD') != '1':
            env = dict(os.environ, ROSHD_COMPARE_CHILD='1', LD_LIBRARY_PATH=f'{sys.argv[2]}:{OPENCV}')
            sys.exit(subprocess.call([sys.executable, *sys.argv], env=env))
        worker(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]) if len(sys.argv) > 5 else 8)
    elif sys.argv[1] == 'diff':
        sys.exit(diff(sys.argv[2], sys.argv[3]))
    else:
        sys.exit(__doc__)
