"""The property of restore_values.py (CodeValues), checked in the Python it runs on: random values --
quotes, backslashes, braces, line breaks, unicode, code-like ones -- put back into every kind of
literal and into comments. The restored code compiles; each literal is exactly the text written with
the value in the placeholder's place; the program's shape -- every node but the literals' text -- is
the one written, so the values run nothing; no line is added.

    python -I restore_property.py <restore_values.py> <seed> <cases>   ->   "<version> ok N bad 0 refused {}"
"""
import ast, random, sys, warnings, json, importlib.util
warnings.simplefilter('ignore')
spec = importlib.util.spec_from_file_location('rv', sys.argv[1]); rv = importlib.util.module_from_spec(spec); spec.loader.exec_module(rv)
rnd = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
ALPHA = ['a', 'Z', '5', ' ', "'", '"', '\\', '{', '}', '\n', '\r', '\t', '\x00', '\x7f', 'é', '🚀', ' ', '‮', '#', '<', '>', '_', ')', '(', '+', ',', 'N', 'x', 'u', '﻿']
PAYLOADS = ["a'+__import__(\"os\").getcwd()+'b", 'x"""; import os; """', "}}{__import__('os')}", "\\", "'", '"', "\\'", '\n# x\nimport os', "''' + str(1) + '''", "{0}", "\\N{BULLET}"]
def value():
    if rnd.random() < 0.3: return rnd.choice(PAYLOADS)
    return ''.join(rnd.choice(ALPHA) for _ in range(rnd.randint(0, 12)))
KINDS = ["'{}'", '"{}"', "'''{}'''", '"""{}"""', "r'{}'", 'r"{}"', "r'''{}'''", 'R"""{}"""', "b'{}'", "rb'{}'", 'Br"{}"',
         "u'{}'", "f'{}'", 'f"{}"', "rf'{}'", 'Rf"{}"', "f'''{}'''", 'fr"""{}"""', "f'{{x}}{}'", "f'{{x!r:>4}}{}{{x}}'"]
BODIES = ['{p}', 'pre {p} post', '{p}{p}', '/tmp/{p}_backup', 'a\\\\{p}', "it's {p}" , 'x{p}\\\\']
def literal(kind, body):
    lit = kind.replace('{}', '\0').replace('{{', '{').replace('}}', '}').replace('\0', body)
    try:
        ast.parse('V = ' + lit)
    except SyntaxError:
        return None
    return lit
def norm(tree):
    """f-string parts as Python itself would join them: adjacent text merged, empty text gone."""
    for n in ast.walk(tree):
        if isinstance(n, ast.JoinedStr):
            vals = []
            for v in n.values:
                if isinstance(v, ast.Constant) and vals and isinstance(vals[-1], ast.Constant):
                    vals[-1] = ast.Constant(vals[-1].value + v.value)
                else:
                    vals.append(v)
            n.values = [v for v in vals if not (isinstance(v, ast.Constant) and v.value == '')]
    return tree
def consts(tree):
    out = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Constant) and isinstance(n.value, (str, bytes)): out.append(n.value)
    return out
def shape(tree):
    for n in ast.walk(tree):
        if isinstance(n, ast.Constant) and isinstance(n.value, (str, bytes)): n.value = 'S'
    return ast.dump(tree)
bad = 0; ok = 0; refused = {}
for case in range(int(sys.argv[3]) if len(sys.argv) > 3 else 3000):
    values = {'<ssid_%d>' % i: value() for i in range(1, 4)}
    lines = ['import json', 'x = 1', 'w = 4']
    for j in range(rnd.randint(1, 4)):
        p = rnd.choice(list(values))
        body = rnd.choice(BODIES).replace('{p}', p)
        lit = literal(rnd.choice(KINDS), body)
        if lit is None: continue
        lines.append('V%d = %s' % (j, lit))
        if rnd.random() < 0.4: lines[-1] += '  # note %s here' % rnd.choice(list(values))
    if rnd.random() < 0.2 and sys.version_info >= (3, 12):
        lines.append("D = f\"{ {'k': 1}.get('%s', 0) }\"" % rnd.choice(list(values)))
    code = '\n'.join(lines) + '\n'
    tree = ast.parse(code)
    try:
        out = rv.restored(code, values)
    except rv.Refused as r:
        refused[r.why] = refused.get(r.why, 0) + 1
        continue
    try:
        got = ast.parse(out)
        compile(out, 'skill.py', 'exec')
    except SyntaxError as e:
        bad += 1; print('DOES NOT COMPILE', repr(code), repr(values), repr(out), e); continue
    want = []
    want_tree = ast.parse(code)
    for n in ast.walk(want_tree):
        if isinstance(n, ast.Constant) and isinstance(n.value, (str, bytes)):
            c = n.value
            if isinstance(c, bytes):
                for k, v in values.items(): c = c.replace(k.encode(), v.encode('utf-8', 'surrogatepass'))
            else:
                for k, v in values.items(): c = c.replace(k, v)
            n.value = c
    norm(want_tree); got = norm(got)
    for c in []:
        if isinstance(c, bytes):
            for k, v in values.items(): c = c.replace(k.encode(), v.encode('utf-8', 'surrogatepass'))
        else:
            for k, v in values.items(): c = c.replace(k, v)
        want.append(c)
    want = consts(want_tree)
    if consts(got) != want or shape(want_tree) != shape(got):
        bad += 1; print('DIFFERENT', repr(code), repr(values), repr(out), consts(got), want); continue
    # Comments: the value as written, its line breaks escaped; no new line of code.
    if code.count('\n') != out.count('\n'):
        bad += 1; print('LINES', repr(code), repr(out)); continue
    ok += 1
print(sys.version.split()[0], 'ok', ok, 'bad', bad, 'refused', refused)
