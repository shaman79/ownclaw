"""The user's values put back into a skill's code, by Python's own tokenizer (OwnClaw's CodeValues).

Read from stdin: {"code": the code as the model wrote it, "values": {placeholder: value}}. Written to
stdout: {"code": the code with each placeholder the values name put back} or {"refused": why,
"line": n}. A placeholder inside a string literal becomes the value escaped for that literal: its
quote, backslashes, and every character but printable ASCII as an escape -- the same escapes in
every Python, whatever Unicode it knows -- bytes as UTF-8 escapes; a raw literal is closed, an
escaped literal joined to it, and opened again; an f-string's braces are doubled; a digit after an
octal escape cannot extend it. In a comment, the value with every character but printable ASCII
escaped, so it cannot end the comment. Refused: a placeholder anywhere else -- code, an f-string's
field or format -- in a comment on the code's first two lines, where Python reads an encoding
declaration, and, on a Python older than 3.12, in an f-string at all.

And then the code put back is checked as the skill's runner will read it -- its bytes, decoded as
they declare: it must declare the encoding the code as written does, and parse to the very program
the code as written does, every placeholder in a string replaced by its value and nothing else
different. Anything else is refused.
"""
import ast
import bisect
import io
import json
import re
import sys
import tokenize

PLACEHOLDER = re.compile(r'<[a-z]+_[0-9]+>')
PLACEHOLDER_BYTES = re.compile(rb'<[a-z]+_[0-9]+>')
OCTAL = '01234567'


class Refused(Exception):
    def __init__(self, line, why):
        self.line = line
        self.why = why


def escape(ch):
    """One character that is not printable ASCII, as an escape every Python reads alike."""
    o = ord(ch)
    if ch == '\n':
        return '\\n'
    if ch == '\r':
        return '\\r'
    if ch == '\t':
        return '\\t'
    if o < 0x100:
        return '\\x%02x' % o
    if o < 0x10000:
        return '\\u%04x' % o
    return '\\U%08x' % o


def escaped(value, quote, as_bytes):
    out = []
    if as_bytes:
        try:
            data = value.encode('utf-8')
        except UnicodeEncodeError:
            raise Refused(0, 'bytes')
        for b in data:
            ch = chr(b)
            if ch == '\\':
                out.append('\\\\')
            elif ch == quote:
                out.append('\\' + quote)
            elif 0x20 <= b < 0x7f:
                out.append(ch)
            else:
                out.append('\\x%02x' % b)
        return ''.join(out)
    for ch in value:
        if ch == '\\':
            out.append('\\\\')
        elif ch == quote:
            out.append('\\' + quote)
        elif 0x20 <= ord(ch) < 0x7f:
            out.append(ch)
        else:
            out.append(escape(ch))
    return ''.join(out)


def in_comment(value):
    return ''.join(ch if 0x20 <= ord(ch) < 0x7f else escape(ch) for ch in value)


def printable_ascii(value):
    return all(0x20 <= ord(ch) < 0x7f for ch in value)


def split_literal(text):
    """prefix, delimiter, body start, body end of a string literal's source text."""
    i = 0
    while i < len(text) and text[i] not in '\'"':
        i += 1
    prefix = text[:i]
    delim = text[i:i + 3] if text[i:i + 3] in ('"""', "'''") else text[i]
    return prefix, delim, i + len(delim), len(text) - len(delim)


def regions(code):
    """Where text stands in the code: (start, end, kind, info) for every literal's text and comment, in order."""
    starts = []
    reader = io.StringIO(code, newline='').readline
    at = [0]

    def readline():
        line = reader()
        starts.append(at[0])
        at[0] += len(line)
        return line

    def offset(pos):
        return starts[pos[0] - 1] + pos[1]

    found = []
    stack = []  # the f-strings (and t-strings) open: [prefix, delimiter, field depth]
    try:
        for tok in tokenize.generate_tokens(readline):
            name = tokenize.tok_name[tok.type]
            if name == 'ERRORTOKEN':
                raise Refused(tok.start[0], 'read')
            start, end = offset(tok.start), offset(tok.end)
            if name == 'COMMENT':
                found.append((start, end, 'comment', tok.start[0]))
            elif name == 'STRING':
                prefix, delim, b0, b1 = split_literal(tok.string)
                if 'f' in prefix.lower() or 't' in prefix.lower():
                    # Python before 3.12: an f-string is one token, its fields inside it. Read by
                    # hand, a quote in a format spec fooled the reading: a value in one is refused.
                    found.append((start + b0, start + b1, 'old f-string', tok.start[0]))
                else:
                    found.append((start + b0, start + b1, 'str', (prefix, delim, start, end, tok.start[0])))
            elif name in ('FSTRING_START', 'TSTRING_START'):
                q = min(i for i in (tok.string.find("'"), tok.string.find('"')) if i >= 0)
                stack.append([tok.string[:q], tok.string[q:], 0])
            elif name in ('FSTRING_END', 'TSTRING_END'):
                stack.pop()
            elif name in ('FSTRING_MIDDLE', 'TSTRING_MIDDLE'):
                prefix, delim, depth = stack[-1]
                if depth == 0:
                    found.append((start, end, 'fpart', (prefix, delim, tok.start[0])))
                else:
                    found.append((start, end, 'spec', tok.start[0]))
            elif name == 'OP' and stack:
                if tok.string == '{':
                    stack[-1][2] += 1
                elif tok.string == '}':
                    stack[-1][2] -= 1
    except (tokenize.TokenError, SyntaxError, IndentationError):
        raise Refused(0, 'read')
    return found


def octal_open(code, floor, s):
    """Whether code[:s] ends inside an octal escape of one or two digits: a digit after it would join it."""
    j = s
    while j > floor and s - j < 2 and code[j - 1] in OCTAL:
        j -= 1
    if j == s or j - 1 < floor or code[j - 1] != '\\':
        return False
    k = j - 1
    while k > floor and code[k - 1] == '\\':
        k -= 1
    return (j - k) % 2 == 1


def restored(code, values):
    spans = regions(code)
    span_starts = [r[0] for r in spans]
    edits = []  # (start, end, text) over the code as written, in order, apart
    last_end = 0
    for m in PLACEHOLDER.finditer(code):
        value = values.get(m.group(0))
        if value is None:
            continue
        s, e = m.start(), m.end()
        i = bisect.bisect_right(span_starts, s) - 1
        region = spans[i] if i >= 0 and spans[i][0] <= s and e <= spans[i][1] else None
        if region is None:
            raise Refused(code.count('\n', 0, s) + 1, 'outside')
        kind, info = region[2], region[3]
        if kind == 'spec':
            raise Refused(info, 'outside')
        if kind == 'old f-string':
            raise Refused(info, 'fstring')
        if kind == 'comment':
            if info <= 2:
                raise Refused(info, 'header')
            edits.append((s, e, in_comment(value)))
            continue
        prefix, delim = info[0], info[1]
        flags = prefix.lower()
        raw, as_bytes, fstr = 'r' in flags, 'b' in flags, kind == 'fpart'
        q = delim[0]
        floor = max(region[0], last_end)
        if kind == 'str' and region[0] == s and region[1] == e and len(delim) == 1:
            other = '"' if q == "'" else "'"
            if q in value and other not in value and '\\' not in value and printable_ascii(value):
                # The whole literal is the placeholder: between the other quotes, as written to be read.
                edits.append((info[2], info[3], prefix + other + value + other))
                last_end = info[3]
                continue
        if raw:
            # A raw literal holds no escape: closed, an escaped literal joined to it, and opened again.
            k = s
            while k > floor and (code[k - 1] == '\\' or (len(delim) == 3 and code[k - 1] == q)):
                k -= 1
            piece = ('b' if as_bytes else '') + "'" + escaped(code[k:s] + value, "'", as_bytes) + "'"
            edits.append((k, e, delim + ' ' + piece + ' ' + prefix + delim))
        else:
            # A backslash before the placeholder that escapes nothing -- "\<" -- is one of the text's own.
            k = s
            while k > floor and code[k - 1] == '\\':
                k -= 1
            lead = '\\' if (s - k) % 2 == 1 else ''
            text = escaped(value, q, as_bytes)
            if not lead and value and value[0] in OCTAL and octal_open(code, floor, s):
                # "\0" before "12ab" would read "\012": the value's first digit as an escape of its own.
                text = '\\x%02x' % ord(value[0]) + escaped(value[1:], q, as_bytes)
            if fstr:
                text = text.replace('{', '{{').replace('}', '}}')
            edits.append((s, e, lead + text))
        last_end = e
    out = []
    at = 0
    for s, e, text in sorted(edits):
        out.append(code[at:s])
        out.append(text)
        at = e
    out.append(code[at:])
    return ''.join(out)


def source(code):
    """The code as the skill's runner reads it: its bytes."""
    return code.encode('utf-8', 'surrogatepass')


def encoding(data):
    try:
        return tokenize.detect_encoding(io.BytesIO(data).readline)[0]
    except SyntaxError:
        return None


def joined(tree):
    """f-string parts as Python itself would join them: adjacent text merged, empty text gone."""
    for node in ast.walk(tree):
        if isinstance(node, ast.JoinedStr):
            parts = []
            for v in node.values:
                if isinstance(v, ast.Constant) and parts and isinstance(parts[-1], ast.Constant):
                    parts[-1] = ast.Constant(parts[-1].value + v.value)
                else:
                    parts.append(v)
            node.values = [v for v in parts if not (isinstance(v, ast.Constant) and v.value == '')]
    return tree


def meant(code, values):
    """The program the code as written means once its values are put back: its tree, each placeholder
    in a string constant replaced by its value."""
    tree = ast.parse(source(code))

    def text(m):
        return values.get(m.group(0), m.group(0))

    def data(m):
        value = values.get(m.group(0).decode('ascii'))
        return m.group(0) if value is None else value.encode('utf-8', 'surrogatepass')

    for node in ast.walk(tree):
        if isinstance(node, ast.Constant):
            if isinstance(node.value, str):
                node.value = PLACEHOLDER.sub(text, node.value)
            elif isinstance(node.value, bytes):
                node.value = PLACEHOLDER_BYTES.sub(data, node.value)
    return joined(tree)


def checked(code, values, out):
    """Refused unless out, read as the runner reads it, is the program the code as written means."""
    before, after = source(code), source(out)
    if encoding(before) != encoding(after):
        raise Refused(0, 'changed')
    try:
        got = joined(ast.parse(after))
    except (SyntaxError, ValueError):
        raise Refused(0, 'changed')
    if ast.dump(got) != ast.dump(meant(code, values)):
        raise Refused(0, 'changed')
    return out


def main():
    data = json.loads(sys.stdin.buffer.read().decode('utf-8'))
    try:
        result = {'code': checked(data['code'], data['values'], restored(data['code'], data['values']))}
    except Refused as r:
        result = {'refused': r.why, 'line': r.line}
    sys.stdout.write(json.dumps(result))


if __name__ == '__main__':
    main()
