#!/usr/bin/env python3
"""Verify that every literal key passed to Loc.s/Loc.t exists in the catalogue."""
import json, re, glob, sys

cat = json.load(open('assets/i18n.json', encoding='utf-8'))
keys = set()
for m in cat.values():
    if isinstance(m, dict):
        keys |= set(m.keys())

def decode(s):
    s = s.replace('\\n', '\n').replace("\\'", "'").replace('\\"', '"')
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)

def read_call(src, start):
    """Return the argument text of a call whose '(' is at src[start]."""
    depth = 0
    i = start
    in_str = False
    esc = False
    while i < len(src):
        c = src[i]
        if in_str:
            if esc: esc = False
            elif c == '\\': esc = True
            elif c == '"': in_str = False
        else:
            if c == '"': in_str = True
            elif c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0: return src[start+1:i]
        i += 1
    return ''

def literal(arg):
    """Turn `"a" + "b"` into 'ab'; None if it contains non-literals."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', arg)
    if not parts: return None
    rest = re.sub(r'"((?:[^"\\]|\\.)*)"', '', arg)
    rest = rest.replace(',', '').replace('+', '').strip()
    if rest: return None
    return decode(''.join(parts))

missing = []
for path in sorted(glob.glob('src/com/ben/pavo/*.java')):
    src = open(path, encoding='utf-8').read()
    for m in re.finditer(r'\bLoc\.(?:s|t|number)\(', src):
        call = read_call(src, m.end()-1)
        args = []
        depth = 0; cur = ''; in_str = False; esc = False
        for c in call:
            if in_str:
                cur += c
                if esc: esc = False
                elif c == '\\': esc = True
                elif c == '"': in_str = False
                continue
            if c == '"':
                in_str = True; cur += c; continue
            if c == '(': depth += 1
            if c == ')': depth -= 1
            if c == ',' and depth == 0:
                args.append(cur); cur = ''
            else:
                cur += c
        args.append(cur)
        if len(args) < 2: continue
        lit = literal(args[-1])
        if lit is None: continue
        if lit in keys: continue
        line = src[:m.start()].count('\n') + 1
        missing.append((path.split('/')[-1], line, lit))

print(f'{len(missing)} Loc.s/Loc.t keys missing from the catalogue\n')
seen = set()
for f, line, s in missing:
    if (s) in seen: continue
    seen.add(s)
    print(f'{f}:{line}: {s!r}')
