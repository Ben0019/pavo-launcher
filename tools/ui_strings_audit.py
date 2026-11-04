#!/usr/bin/env python3
"""Find user-visible string literals in the launcher source that are not in the
translation catalogue. Heuristic: literals passed to UI APIs or used in UI-ish
fields. Output is grouped by file with line numbers."""
import json, re, sys, os, glob

cat = json.load(open('assets/i18n.json', encoding='utf-8'))
keys = set()
for lang, m in cat.items():
    if isinstance(m, dict):
        keys |= set(m.keys())

STR = re.compile(r'"((?:[^"\\]|\\.)*)"')
UI_CALL = re.compile(r'(setText|setHint|setTitle|setContentDescription|setSummary|Toast\.makeText|LToast\.makeText|LToast\.show|setLabel|notification\(|NotificationCompat|addButton|addChip|setPositiveButton|setNegativeButton|setMessage|setNeutralButton)')

# literals that are clearly not UI copy
SKIP = re.compile(r'''^(
    (?:com|org|android|androidx|java|javax|kotlin)\..* |
    /.* | .*\.(java|json|xml|png|sh|conf|pid|log|txt|mp4|zip|tmp|kl|kcm) |
    [a-z0-9_]+(?:\.[a-z0-9_]+)+ |
    [A-Z0-9_]{2,} |
    .*%[0-9$]*[dsf] .* |
    .*\s=\s.*
)$''', re.X)

def norm(s):
    s = s.replace('\\n', '\n').replace("\\'", "'").replace('\\"', '"')
    s = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)
    return s

found = []
for path in sorted(glob.glob('src/com/ben/pavo/*.java')):
    for i, line in enumerate(open(path, encoding='utf-8'), 1):
        if line.lstrip().startswith('*') or line.lstrip().startswith('//'):
            continue
        if 'Log.' in line or 'Log.w' in line or 'Log.i' in line or 'Log.d' in line:
            continue
        if not UI_CALL.search(line):
            continue
        for m in STR.finditer(line):
            raw = norm(m.group(1))
            if len(raw.strip()) < 2:
                continue
            if not re.search(r'[A-Za-z]', raw):
                continue
            if SKIP.match(raw.strip()):
                continue
            # already translated somewhere?
            if raw in keys or raw.strip() in keys:
                continue
            if raw in ('', ' ', 'UTF-8'):
                continue
            found.append((path.split('/')[-1], i, raw))

print(f'{len(found)} candidate literals not present in the catalogue\n')
byfile = {}
for f, i, s in found:
    byfile.setdefault(f, []).append((i, s))
for f, items in byfile.items():
    print(f'== {f}')
    for i, s in items[:40]:
        print(f'  {i}: {s!r}')
    if len(items) > 40:
        print(f'  ... {len(items)-40} more')
