import urllib.request, json, time, sys

TOKEN = 'eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiI0MThjM2VhYiIsInVzZXJuYW1lIjoic2hhbWFuIiwiaWF0IjoxNzcyMTI5MjI3LCJleHAiOjE3NzQ3MjEyMjd9.UfVgd7z-iFZ_9bS7iuxQa4piMQ9nlsJjYbIsG79LyHLAuoqK2AdCFvs7X-OngEqIhiS1U1CFWdLTjBeWHCRK7w'
BASE = 'http://192.168.1.204:8080'
HDRS = {'Authorization': f'Bearer {TOKEN}', 'Content-Type': 'application/json; charset=utf-8'}

def api(method, path, body=None, timeout=300):
    data = json.dumps(body).encode('utf-8') if body else None
    req = urllib.request.Request(f'{BASE}{path}', data=data, method=method, headers=HDRS)
    resp = urllib.request.urlopen(req, timeout=timeout)
    return json.loads(resp.read().decode('utf-8'))

# ── Step 1: delete ALL existing skills ──
status = api('GET', '/api/debug/status')
print(f"Skills before cleanup: {status['skillCount']}")
for s in status.get('skills', []):
    name = s['name']
    try:
        r = api('DELETE', f'/api/debug/skill/{name}')
        print(f"  DELETE {name}: {r.get('result','?')}")
    except Exception as e:
        print(f"  DELETE {name} FAILED: {e}")

status = api('GET', '/api/debug/status')
print(f"Skills after cleanup: {status['skillCount']}")
assert status['skillCount'] == 0, f"Expected 0, got {status['skillCount']}"

# ── Step 2: run test prompt ──
prompt = "What was on menu on Tuesday here: https://www.hospoda-orechovska.cz/"
print(f"\n{'='*60}")
print(f"PROMPT: {prompt}")
print(f"{'='*60}")
t0 = time.time()
result = api('POST', '/api/debug/prompt', {'message': prompt}, timeout=300)
elapsed = time.time() - t0
print(f"Duration: {elapsed:.1f}s  (server: {result.get('durationMs','?')}ms)")

# ── Step 3: trajectory ──
print(f"\n--- TRAJECTORY ---")
for step in result.get('trajectory', []):
    tool = step.get('toolName', step.get('tool', '?'))
    ok   = step.get('success', '?')
    dur  = step.get('durationMs', '?')
    out  = step.get('output', '')[:200].replace('\n', '\\n')
    print(f"  [{step.get('step','?')}] {tool}  ok={ok}  {dur}ms")
    print(f"      {out}")

# ── Step 4: response ──
response = result.get('response', '')
print(f"\n{'='*60}")
print("RESPONSE:")
print(f"{'='*60}")
print(response)
print(f"{'='*60}")

# ── Step 5: verification ──
expected = [
    'celestýnsk',
    'Svíčková',
    'kotleta',
    'houbách',
    'panenk',
    'Smažené',
    'hermelín',
    'hranolky',
]
print("\n--- CHECKS ---")
ok_all = True
for frag in expected:
    found = frag.lower() in response.lower()
    tag = 'PASS' if found else 'FAIL'
    if not found: ok_all = False
    print(f"  [{tag}] '{frag}'")

# ── Step 6: final skill count ──
status2 = api('GET', '/api/debug/status')
print(f"\nSkills after test: {status2['skillCount']}")
for s in status2.get('skills', []):
    print(f"  - {s['name']}")

print(f"\n{'='*60}")
print(f"RESULT: {'ALL CHECKS PASSED' if ok_all else 'SOME CHECKS FAILED'}")
print(f"{'='*60}")
sys.exit(0 if ok_all else 1)
