"""The user's values put back into a skill's code, by Python's own tokenizer (OwnClaw's CodeValues).

Read from stdin: {"code": the code as the model wrote it, "values": {placeholder: value}}. Written to
stdout: {"code": the code with each placeholder the values name put back} or {"refused": why,
"line": n}. A placeholder inside a string literal becomes the value escaped for that literal: its
quote, triple quotes, backslashes, control characters and line breaks, bytes as UTF-8 escapes; a
raw literal is closed, an escaped literal joined to it, and opened again; an f-string's braces are
doubled. In a comment, the value with its line breaks escaped, so it cannot end the comment. A
placeholder anywhere else -- code, an f-string's field, an encoding declaration -- is refused: put
back there, a value would be code.
"""
import io
import json
import re
import sys
import tokenize

PLACEHOLDER = re.compile(r'<[a-z]+_[0-9]+>')
CODING = re.compile(r'^[ \t\f]*#.*?coding[:=]')


class Refused(Exception):
    def __init__(self, line, why):
        self.line = line
        self.why = why


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
        o = ord(ch)
        if ch == '\\':
            out.append('\\\\')
        elif ch == quote:
            out.append('\\' + quote)
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        elif ch.isprintable():
            out.append(ch)
        elif o < 0x100:
            out.append('\\x%02x' % o)
        elif o < 0x10000:
            out.append('\\u%04x' % o)
        else:
            out.append('\\U%08x' % o)
    return ''.join(out)


def in_comment(value):
    out = []
    for ch in value:
        if ch.isprintable():
            out.append(ch)
        else:
            out.append(escaped(ch, '', False))
    return ''.join(out)


def split_literal(text):
    """prefix, delimiter, body start, body end of a string literal's source text."""
    i = 0
    while i < len(text) and text[i] not in '\'"':
        i += 1
    prefix = text[:i]
    delim = text[i:i + 3] if text[i:i + 3] in ('"""', "'''") else text[i]
    return prefix, delim, i + len(delim), len(text) - len(delim)


def skip_string(body, j):
    """The index after a string literal that starts at body[j] (a quote), inside an f-string's field."""
    q = body[j]
    delim = body[j:j + 3] if body[j:j + 3] == q * 3 else q
    k = j + len(delim)
    while k < len(body):
        if body[k] == '\\':
            k += 2
            continue
        if body.startswith(delim, k):
            return k + len(delim)
        k += 1
    return len(body)


def fstring_parts(body, raw):
    """The literal parts of an f-string's body -- outside its {} fields -- as (start, end) in it."""
    parts = []
    start = 0
    i = 0
    n = len(body)
    while i < n:
        c = body[i]
        if not raw and c == '\\' and body.startswith('N{', i + 1):
            close = body.find('}', i)
            i = n if close < 0 else close + 1
            continue
        if c == '{':
            if body.startswith('{{', i):
                i += 2
                continue
            parts.append((start, i))
            depth = 1
            j = i + 1
            while j < n and depth > 0:
                ch = body[j]
                if ch in '\'"':
                    j = skip_string(body, j)
                    continue
                if ch in '([{':
                    depth += 1
                elif ch in ')]}':
                    depth -= 1
                j += 1
            i = start = j
            continue
        i += 1
    parts.append((start, n))
    return parts


def regions(code):
    """Where text stands in the code: (start, end, kind, info) for every literal's text and comment."""
    lines = []
    reader = io.StringIO(code, newline='').readline

    def readline():
        line = reader()
        lines.append(line)
        return line

    starts = []

    def offset(pos):
        row, col = pos
        while len(starts) < row:
            starts.append(sum(len(l) for l in lines[:len(starts)]))
        return starts[row - 1] + col

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
                    raw = 'r' in prefix.lower()
                    for p0, p1 in fstring_parts(tok.string[b0:b1], raw):
                        found.append((start + b0 + p0, start + b0 + p1, 'fpart', (prefix, delim, tok.start[0])))
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


def line_of(code, at):
    return code.count('\n', 0, at) + 1


def restored(code, values):
    spans = regions(code)
    edits = []  # (start, end, text) over the code as written, in order, apart
    last_end = 0
    for m in PLACEHOLDER.finditer(code):
        value = values.get(m.group(0))
        if value is None:
            continue
        s, e = m.start(), m.end()
        region = next((r for r in spans if r[0] <= s and e <= r[1]), None)
        if region is None:
            raise Refused(line_of(code, s), 'outside')
        kind, info = region[2], region[3]
        if kind == 'spec':
            raise Refused(info, 'outside')
        if kind == 'comment':
            if info <= 2 and CODING.match(code[code.rfind('\n', 0, s) + 1:s]):
                raise Refused(info, 'coding')
            edits.append((s, e, in_comment(value)))
            continue
        prefix, delim = info[0], info[1]
        flags = prefix.lower()
        raw, as_bytes, fstr = 'r' in flags, 'b' in flags, kind == 'fpart'
        q = delim[0]
        floor = max(region[0], last_end)
        if kind == 'str' and region[0] == s and region[1] == e and len(delim) == 1:
            other = '"' if q == "'" else "'"
            if (q in value and other not in value and '\\' not in value and value.isprintable()
                    and (not as_bytes or value.isascii())):
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


def main():
    data = json.loads(sys.stdin.buffer.read().decode('utf-8'))
    try:
        result = {'code': restored(data['code'], data['values'])}
    except Refused as r:
        result = {'refused': r.why, 'line': r.line}
    sys.stdout.write(json.dumps(result))


if __name__ == '__main__':
    main()
