#!/usr/bin/env python3
"""OCR every built-in screen and report Latin text that is probably still
untranslated. Requires tesseract plus the language traineddata.
"""
import argparse, json, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(__file__))
from ui_visual_smoke import Adb, top_activity, APPS, PKG, ensure_language

TESS = 'tesseract'
TESSDATA = os.environ.get('TESSDATA_PREFIX', '/tmp/opencode/tessdata')

# Words that may legitimately stay in English.
ALLOW = re.compile(r'''(?ix)^(
    pavo|aurora|odin|android|github|pserver|qcom|adreno|quectel|qualcomm|reddit|brawl|stars|
    cpu|gpu|ram|fps|rgb|hdr|ddr|ssd|usb|sd|ftp|qr|ip|uid|dpi|hz|mhz|ghz|mbps|ms|mah|ma|mw|rpm|
    soh|pcm|aac|opus|mp4|hevc|avi|h\.264|walt|schedutil|kgsl|devfreq|msm|adreno|tz|sans|
    wifi|bt|nfc|usb|sim|vol|max|min|avg|temp|app|apps|pro|plus|ultra|oled|lcd|amoled|
    [a-z]|km|kg|mah|v|w|a|h|m|s|g|mb|gb|kb|tb|fps
)$''')

def ocr(path, langs):
    env = dict(os.environ, TESSDATA_PREFIX=TESSDATA)
    p = subprocess.run([TESS, path, '-', '-l', langs, '--psm', '11'],
                       capture_output=True, text=True, timeout=180, env=env)
    return [l.strip() for l in p.stdout.splitlines() if l.strip()]

def latin_words(line):
    out = []
    for w in re.findall(r"[A-Za-z][A-Za-z'’\-]{1,}", line):
        if ALLOW.match(w): continue
        if w.upper() == w and len(w) <= 5: continue
        out.append(w)
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default='192.168.1.10:43929')
    ap.add_argument('--lang', default='ko')
    ap.add_argument('--ocr', default=None, help='tesseract languages, e.g. kor+eng')
    ap.add_argument('--out', default='/tmp/opencode/i18n-screens')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    langs = args.ocr or ('kor+eng' if args.lang == 'ko' else args.lang + '+eng')
    adb = Adb(args.serial)
    ensure_language(adb, args.lang)
    findings = []
    for name, label in APPS:
        adb.sh('input keyevent 3'); time.sleep(0.4)
        adb.sh(f'am start -W -n {PKG}/.{name}', root=True)
        time.sleep(1.6)
        pkg, act = top_activity(adb)
        if pkg != PKG:
            findings.append({'app': label, 'activity': name, 'error': f'top={pkg}/{act}'})
            continue
        shot = os.path.join(args.out, f'{name}.png')
        adb.shot(shot)
        lines = ocr(shot, langs)
        bad = []
        for line in lines:
            words = latin_words(line)
            if words:
                bad.append((line, words))
        print(f'== {label}: {len(lines)} lines, {len(bad)} latin')
        for line, words in bad:
            print(f'   {line!r}  -> {words}')
        findings.append({'app': label, 'activity': name, 'latin': bad, 'screenshot': shot})
        adb.sh('input keyevent 4')
        time.sleep(0.4)
    json.dump(findings, open(os.path.join(args.out, 'report.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)
    print('\nsaved', os.path.join(args.out, 'report.json'))

if __name__ == '__main__':
    main()
