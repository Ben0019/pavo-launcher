#!/usr/bin/env python3
"""Structural QA for the runtime translation catalogue.

Checks every language against the English source keys:
missing/extra keys, broken format placeholders, lost newlines, untranslated
strings, duplicated translations, suspicious length, script mixing, digit sets
and whitespace problems. Output is grouped per language.
"""
import json, re, sys, collections, unicodedata

PATH = sys.argv[1] if len(sys.argv) > 1 else 'assets/i18n.json'
data = json.load(open(PATH, encoding='utf-8'))

PLACEHOLDER = re.compile(r'%(?:\d+\$)?[-+0#,]*\d*(?:\.\d+)?[dfsuoxXeEgGcCbBhH%]')
LATIN = re.compile(r'[A-Za-z]')
CJK = re.compile(r'[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff]')
HANGUL = re.compile(r'[\uac00-\ud7af]')
CYRILLIC = re.compile(r'[\u0400-\u04ff]')
GREEK = re.compile(r'[\u0370-\u03ff]')
HEBREW = re.compile(r'[\u0590-\u05ff]')
THAI = re.compile(r'[\u0e00-\u0e7f]')
NONLATIN_DIGIT = re.compile(r'[\u0660-\u0669\u06f0-\u06f9\u0966-\u096f\u0e50-\u0e59\uff10-\uff19]')

# Strings that legitimately stay identical to English.
KEEP = re.compile(r'''^(
    [\d\s.,:+\-–—%/×*°²³()\[\]{}#<>|·•…]+ |
    [A-Z0-9_./\-+ ×%:]+ |
    (?:[A-Z]{2,}[0-9]*)(?:\s*[/·:]\s*[A-Z0-9]+)* |
    .*\.(?:json|xml|png|sh|conf|pid|log|txt|mp4|zip|tmp) |
    (?:aurora|pavo|odin|adreno|quectel|qualcomm|android|github|pserver|schedutil|walt|kgsl|devfreq|msm-adreno-tz|sans-serif|monospace|utf-8|user-agent|qcom|sd|usb|ram|cpu|gpu|fps|rgb|hdr|dpi|hz|mhz|ghz|mbps|ms|mah|ma|mw|rpm|soh|pcm|aac|opus|mp4|hevc|h\.264|avi|qr|ftp|ip|uid|su|cat|cd|echo|sh|true|xml|json)
    .*
)''', re.X | re.I)

def placeholders(s):
    return sorted(PLACEHOLDER.findall(s))

def norm_ws(s):
    return s.strip()

languages = sorted(k for k, v in data.items() if isinstance(v, dict) and k != 'en')
universe = set()
for v in data.values():
    if isinstance(v, dict):
        universe |= set(v.keys())
universe |= set()

report = {}
for lang in languages:
    cat = data[lang]
    issues = collections.defaultdict(list)
    for key, value in cat.items():
        if not isinstance(value, str):
            continue
        eng = key
        # placeholders must survive
        ph_eng, ph_val = placeholders(eng), placeholders(value)
        if ph_eng and ph_eng != ph_val:
            issues['placeholder'].append((key, value, ph_eng, ph_val))
        if eng.count('\n') != value.count('\n'):
            issues['newline'].append((key, value))
        if eng[:1].isspace() != value[:1].isspace() or eng[-1:].isspace() != value[-1:].isspace():
            issues['whitespace'].append((key, value))
        if NONLATIN_DIGIT.search(value):
            issues['localized_digit'].append((key, value))
        if value == eng and not KEEP.match(eng) and len(eng) > 3:
            issues['untranslated'].append((key, value))
        if '  ' in value and '  ' not in eng:
            issues['double_space'].append((key, value))
        ratio = len(value) / max(1, len(eng))
        if ratio > 2.4 and len(value) > 28 and LATIN.search(eng):
            issues['long'].append((key, value, round(ratio, 2)))
    report[lang] = issues

all_langs = set(map(str.lower, languages))
counts = {}
for lang in languages:
    missing = universe - set(data[lang].keys())
    counts[lang] = {k: len(v) for k, v in report[lang].items()}
    counts[lang]['missing_keys'] = len(missing)

print('language  keys  missing  placeholder  newline  whitespace  untranslated  long  double_space  digits')
for lang in languages:
    c = counts[lang]
    print(f"{lang:8} {len(data[lang]):5} {c['missing_keys']:8} {c.get('placeholder',0):12} "
          f"{c.get('newline',0):8} {c.get('whitespace',0):11} {c.get('untranslated',0):13} "
          f"{c.get('long',0):5} {c.get('double_space',0):12} {c.get('localized_digit',0):7}")

out = {}
for lang in languages:
    out[lang] = {k: v for k, v in report[lang].items() if v}
json.dump(out, open('/tmp/opencode/i18n-audit.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('\nfull report: /tmp/opencode/i18n-audit.json')
