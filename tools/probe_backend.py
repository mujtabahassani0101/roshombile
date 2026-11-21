#!/usr/bin/env python3
"""Check a Roshd backend with a real account, the way the app talks to it.

The username and password are typed by the person running this script and are
never printed or stored. The bearer token stays in memory and the session is
logged out at the end. The output and the JSON report contain only HTTP
statuses, problem codes, timings, counts and which notice fields are present;
no token, student name, score or answer is printed.

  python3 tools/probe_backend.py [https://roshd.azmoonic.com]
"""
import base64, getpass, json, sys, time, uuid, zlib
import urllib.error, urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else 'https://roshd.azmoonic.com').rstrip('/')
REPORT = Path(__file__).resolve().parents[1] / 'output/backend-probe/report.json'
rows = []


def call(method, path, token=None, body=None, school=None, accept='application/json'):
    headers = {'Accept': accept, 'User-Agent': 'RoshdApp-probe/1.0'}
    if token: headers['Authorization'] = f'Bearer {token}'
    if school: headers['X-ROSHD-Scanner-School'] = school
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            status, raw, kind = response.status, response.read(), response.headers.get('Content-Type', '')
    except urllib.error.HTTPError as error:
        status, raw, kind = error.code, error.read(), error.headers.get('Content-Type', '')
    except Exception as error:  # network failure
        rows.append({'request': f'{method} {path.split("?")[0]}', 'status': 'network', 'detail': type(error).__name__, 'seconds': round(time.perf_counter() - started, 2)})
        return None, None
    seconds = round(time.perf_counter() - started, 2)
    parsed = None
    if 'json' in kind and raw:
        try: parsed = json.loads(raw)
        except ValueError: pass
    code = parsed.get('code') if isinstance(parsed, dict) and status >= 400 else None
    rows.append({'request': f'{method} {path.split("?")[0]}', 'status': status, 'code': code, 'seconds': seconds})
    return status, parsed


def note(text):
    rows[-1]['detail'] = text


def b64url(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def notice_fields(token):
    """Fetch one encrypted notice and report which current-contract fields it carries."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    status, editions = call('GET', '/api/v1/school-manager/results/editions', token)
    if status != 200 or not isinstance(editions, list) or not editions:
        note('no edition with published results'); return None
    note(f'{len(editions)} editions')
    for edition in editions[:3]:
        status, page = call('GET', f'/api/v1/school-manager/results?editionId={edition["editionId"]}&limit=25', token)
        if status != 200 or not isinstance(page, dict): continue
        results = [r for item in page.get('items', []) for r in item.get('results', [])]
        note(f'{len(page.get("items", []))} students, {len(results)} results, '
             f'{sum(bool(r.get("managerMayReadNotice")) for r in results)} with notice permission')
        target = next((r for r in results if r.get('managerMayReadNotice')), None)
        if not target: continue
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        body = {'requestId': str(uuid.uuid4()), 'recipientPublicKeySpki': base64.b64encode(spki).decode(), 'color': 'blue'}
        status, envelope = call('POST', f'/api/v1/school-manager/results/{target["id"]}/notice/mobile-payload', token, body)
        if status != 200 or not isinstance(envelope, dict): return None
        header = json.loads(b64url(envelope['protected']))
        cek = key.decrypt(b64url(envelope['encrypted_key']), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        plain = AESGCM(cek).decrypt(b64url(envelope['iv']), b64url(envelope['ciphertext']) + b64url(envelope['tag']), envelope['protected'].encode('ascii'))
        if header.get('zip') == 'DEF': plain = zlib.decompress(plain, -15)
        payload = json.loads(plain)
        data = payload['notices'][0]['data']
        ranks = data.get('ranks') or {}
        groups = data.get('groupRanks') or []
        def state(container, name):
            if name not in container: return 'absent'
            return 'null' if container[name] is None else 'present'
        fields = {
            'schemaVersion': payload.get('schemaVersion'),
            'rendererVersion': data.get('rendererVersion'),
            'jweTyp': header.get('typ'),
            'hasResult': state(data, 'hasResult'),
            'section': state(data, 'section'),
            **{f'ranks.{name}': state(ranks, name) for name in ['overall', 'school', 'class', 'province', 'nationalPopulation', 'provincePopulation', 'schoolPopulation', 'classPopulation']},
            'groupRanks': len(groups),
            'groupRanks[].province': state(groups[0], 'province') if groups else 'no groups',
            'groupRanks[].class': state(groups[0], 'class') if groups else 'no groups',
        }
        note('decrypted; field presence recorded')
        return fields
    return None


def main():
    print(f'Backend: {BASE}')
    username = input('Username: ').strip()
    password = getpass.getpass('Password (not shown): ')
    status, login = call('POST', '/api/v1/scanner/login', body={'username': username, 'password': password})
    password = None
    summary = {'base': BASE, 'checkedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z')}
    token = login.get('accessToken') if status == 200 and isinstance(login, dict) else None
    if not token:
        print(f'Login failed: HTTP {status} {login.get("code") if isinstance(login, dict) else ""}')
    else:
        summary['account'] = {key: login.get(key) for key in ['role', 'accessScope', 'apiVersion', 'tokenType', 'expiresIn', 'schoolSelectionRequired']}
        summary['account']['currentAcademicYear'] = login.get('currentAcademicYear') is not None
        summary['account']['openRounds'] = (login.get('currentAcademicYear') or {}).get('openRounds') if isinstance(login.get('currentAcademicYear'), dict) else None
        school = None
        if login.get('accessScope') == 'province' and login.get('schoolIds'):
            school = login['schoolIds'][0]
        note(f'role {login.get("role")}, scope {login.get("accessScope")}')
        status, value = call('GET', '/api/v1/scanner/configuration', token)
        if isinstance(value, dict): note(f'features: {sorted(k for k, v in (value.get("features") or {}).items() if v)}')
        status, value = call('GET', '/api/v1/scanner/exams?limit=5', token)
        if isinstance(value, dict): note(f'{len(value.get("items", []))} exams on first page')
        status, value = call('GET', '/api/v1/scanner/academic-years', token)
        if isinstance(value, dict): note(f'{len(value.get("items", value.get("years", [])))} archive years')
        status, value = call('GET', '/api/v1/school-manager/attendance/exams?limit=5', token, school=school)
        if isinstance(value, dict) and status == 200: note(f'{len(value.get("items", []))} attendance exams')
        call('GET', '/api/v1/access/year-context', token)
        call('GET', '/api/v1/academic-years', token)
        if login.get('role') == 'SchoolManager':
            try:
                summary['notice'] = notice_fields(token)
            except Exception as error:
                summary['notice'] = {'error': type(error).__name__}
        call('POST', '/api/v1/scanner/logout', token)
        token = None
    summary['requests'] = rows
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print()
    for row in rows:
        print(f"{row['request']:<70} {str(row['status']):>7} {row.get('code') or '':<28} {row['seconds']:>6}s  {row.get('detail', '')}")
    if summary.get('notice'):
        print('\nNotice payload fields:')
        for key, value in summary['notice'].items(): print(f'  {key}: {value}')
    print(f'\nReport (no secrets or student data): {REPORT}')


if __name__ == '__main__':
    main()
