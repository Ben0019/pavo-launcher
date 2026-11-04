#!/usr/bin/env python3
"""Spell-check translated values with hunspell dictionaries.

Unknown words are aggregated per language. Brand names, acronyms and English
tokens that also appear in the source key are ignored so only unexplained
words surface.
"""
import json, re, subprocess, collections, os, sys

DICT_DIR = '/tmp/opencode/dicts'
LANGS = [d for d in sorted(os.listdir(DICT_DIR)) if os.path.exists(f'{DICT_DIR}/{d}/index.dic')]
data = json.load(open('assets/i18n.json', encoding='utf-8'))

WORD = re.compile(r"[^\W\d_][^\W\d_'’\-]*", re.UNICODE)
ALLOW = set('''pavo aurora odin android github pserver qcom adreno quectel qualcomm
schedutil walt kgsl devfreq msm adreno tz rgb hdr fps cpu gpu ram soh pcm aac opus hevc avi
dpi hz mhz ghz mbps mah rpm ftp qr ip uid usb sd nfc ui idn'''.split())

def words(s):
    out = []
    for m in WORD.finditer(s):
        w = m.group(0)
        if len(w) < 2: continue
        if w.upper() == w: continue          # acronyms
        if w.lower() in ALLOW: continue
        out.append(w)
    return out

for lang in LANGS:
    cat = data.get(lang) or {}
    key_tokens = set()
    for k in cat:
        for w in words(k):
            key_tokens.add(w.lower())
    counts = collections.Counter()
    samples = {}
    for k, v in cat.items():
        if not isinstance(v, str): continue
        for w in words(v):
            if w.lower() in key_tokens:      # technical term kept from English
                continue
            counts[w] += 1
            samples.setdefault(w, k)
    unique = list(counts)
    if not unique:
        print(f'== {lang}: no words'); continue
    try:
        p = subprocess.run(['hunspell', '-d', f'{DICT_DIR}/{lang}/index', '-l'],
                           input='\n'.join(unique), capture_output=True, text=True, timeout=120)
        unknown = [w for w in p.stdout.split('\n') if w.strip()]
    except Exception as e:
        print(f'== {lang}: hunspell failed {e}'); continue
    unknown.sort(key=lambda w: -counts[w])
    print(f'== {lang}: {len(unknown)} unknown of {len(unique)}')
    for w in unknown[:25]:
        print(f'   {counts[w]:3} {w!r}  e.g. {samples[w][:60]!r}')
