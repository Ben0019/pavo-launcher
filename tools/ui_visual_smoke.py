#!/usr/bin/env python3
"""Human-like smoke test for Pavo's custom-drawn screens.

Pavo draws most of its UI on a canvas, so `uiautomator dump` often cannot reach
an idle state. This harness instead screenshots each built-in app, reads the
visible text with Tesseract, and taps safe-looking controls (switches, chips,
tabs) the way a person would. It reports crashes, blank screens, dead taps and
saves a screenshot per app.

Usage:
  python3 tools/ui_visual_smoke.py --serial 192.168.1.10:43929
  python3 tools/ui_visual_smoke.py --only FanActivity --actions 10 --lang en
"""
import argparse, json, os, re, subprocess, sys, time
from PIL import Image

ADB = os.environ.get('ADB', os.path.expanduser('~/.local/opt/platform-tools/adb'))
PKG = 'com.ben.pavo'
TESS = 'tesseract'

APPS = [
    ('MainActivity', 'Launcher home'),
    ('ControllerMapActivity', 'Controller Remapper'),
    ('PerfLogActivity', 'Performance Logs'),
    ('DisplayActivity', 'Display'),
    ('RgbActivity', 'RGB Lighting'),
    ('OverlayActivity', 'Overlay & Drawer'),
    ('UpdatesActivity', 'Pavo Updates'),
    ('FanActivity', 'Fan Control'),
    ('TouchMapperActivity', 'Touch Mapper'),
    ('PerformanceTunerActivity', 'Performance Tuner'),
    ('FtpActivity', 'FTP'),
    ('DeviceHealthActivity', 'Device Health'),
    ('BenchmarkActivity', 'Stress Test'),
    ('ScreenRecorderActivity', 'Screen Recorder'),
    ('FileManagerActivity', 'File Manager'),
    ('PerfControlActivity', 'Overlay controls'),
    ('SetupActivity', 'Setup'),
]

DANGER = re.compile(r'(?i)(delete|remove|erase|wipe|format|uninstall|reset|restore|reboot|'
                    r'shutdown|power off|stop|kill|clear all|discard|forget|revoke|factory)')
SAFE = re.compile(r'(?i)^(on|off|enable|disable|auto|manual|default|defaults|preset|apply|save|'
                  r'refresh|check|learn|add|more|less|next|prev|back|close|done|ok|cancel|'
                  r'quiet|balanced|performance|extreme|custom|profile|system|game|balanced|'
                  r'cool|warm|turbo|normal|silent|maximum|minimum|left|right|both|all|'
                  r'panel|strip|overlay|drawer|lighting|fan|tuner|display|health|stress|'
                  r'file|ftp|updates?|recorder|mapper|remapper|settings|home|apps|\+\d*|−\d*|\+\d*%?)$')

class Adb:
    def __init__(self, serial):
        self.base = [ADB] + (['-s', serial] if serial else [])
    def sh(self, cmd, timeout=40, root=False):
        full = self.base + ['shell', (f"su -c '{cmd}'" if root else cmd)]
        p = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
        return p.stdout.strip(), p.stderr.strip(), p.returncode
    def tap(self, x, y):
        self.sh(f'input tap {x} {y}')
    def swipe(self, x1, y1, x2, y2, ms=400):
        self.sh(f'input swipe {x1} {y1} {x2} {y2} {ms}')
    def shot(self, path):
        with open(path, 'wb') as f:
            subprocess.run(self.base + ['exec-out', 'screencap', '-p'], stdout=f, timeout=60)
    def logcat(self):
        out, _, _ = self.sh('logcat -d -v brief')
        return out
    def clear_log(self):
        self.sh('logcat -c')

def top_activity(adb):
    out, _, _ = adb.sh('dumpsys window | grep -m1 mCurrentFocus')
    m = re.search(r'u0 ([^/]+)/([^}]+)\}', out)
    return (m.group(1), m.group(2)) if m else (None, None)

def ocr(path):
    """Return list of (text, cx, cy) from the screenshot."""
    p = subprocess.run([TESS, path, '-', '--psm', '11', 'tsv'],
                       capture_output=True, text=True, timeout=120)
    rows = []
    for line in p.stdout.splitlines()[1:]:
        c = line.split('\t')
        if len(c) < 12: continue
        try:
            conf = float(c[10]); text = c[11].strip()
        except ValueError:
            continue
        if conf < 35 or len(text) < 2: continue
        if not re.search(r'[A-Za-z0-9]', text): continue
        x, y, w, h = map(int, c[6:10])
        rows.append((text, x + w // 2, y + h // 2, conf))
    return rows

def brightness(path):
    im = Image.open(path).convert('L').resize((64, 36))
    px = list(im.getdata())
    return sum(px) / len(px), len(set(px))

def crash_scan(log):
    hits = []
    for line in log.splitlines():
        if re.search(r'FATAL EXCEPTION|AndroidRuntime.*' + re.escape(PKG) + r'|ANR in ' + re.escape(PKG) +
                     r'|Process: ' + re.escape(PKG), line):
            hits.append(line.strip())
    return hits

def ensure_language(adb, lang):
    if not lang: return
    adb.sh(f"sed -i 's|<string name=\"language\">[^<]*</string>|<string name=\"language\">{lang}</string>|' "
           "/data/data/com.ben.pavo/shared_prefs/pavo.xml", root=True)
    adb.sh('am force-stop com.ben.pavo', root=True)

def run_app(adb, name, label, actions, outdir):
    print(f'\n=== {label} ({name})')
    r = {'activity': name, 'label': label, 'opened': False, 'actions': 0,
         'crashes': [], 'notes': [], 'screens': []}
    adb.clear_log()
    adb.sh('input keyevent 3'); time.sleep(0.5)
    adb.sh(f'am start -W -n {PKG}/.{name}', root=True)
    pkg = act = None
    for _ in range(12):
        time.sleep(0.35)
        pkg, act = top_activity(adb)
        if pkg == PKG and name in (act or ''):
            break
    r['opened'] = (pkg == PKG and name in (act or ''))
    if not r['opened']:
        r['notes'].append(f'top={pkg}/{act}')
    time.sleep(0.8)
    shot = os.path.join(outdir, f'{name}.png')
    adb.shot(shot)
    mean, distinct = brightness(shot)
    r['brightness'] = round(mean, 1)
    if mean < 8 or distinct < 8:
        r['notes'].append('blank screen')
    baseline = None
    for i in range(actions):
        words = ocr(shot)
        texts = tuple(sorted(w[0].lower() for w in words))
        if baseline is None:
            baseline = texts
        # skip the title and the top bar; prefer controls near the middle
        cands = []
        for text, cx, cy, conf in words:
            word = text.strip(' .:·•')
            if DANGER.search(word): continue
            if cy < 90: continue
            if SAFE.search(word) or re.match(r'^[A-Z][A-Z /&+−-]{2,}$', word):
                cands.append((cy, cx, word))
        cands.sort()
        if cands:
            cy, cx, word = cands[i % len(cands)]
            adb.tap(cx, cy)
            r['actions'] += 1
            print(f'   tap {word!r} at {cx},{cy}')
        else:
            adb.swipe(960, 780, 960, 380, 350)
            r['actions'] += 1
            print('   scroll')
        time.sleep(0.9)
        shot = os.path.join(outdir, f'{name}_{i+1}.png')
        adb.shot(shot)
        r['screens'].append(shot)
        log = adb.logcat()
        c = crash_scan(log)
        if c:
            r['crashes'] = c
            break
    log = adb.logcat()
    r['crashes'] = r['crashes'] or crash_scan(log)
    adb.sh('input keyevent 4')
    time.sleep(0.6)
    pkg2, act2 = top_activity(adb)
    r['closed'] = not (pkg2 == PKG and name in (act2 or ''))
    adb.sh('input keyevent 3')
    print(f"   opened={r['opened']} closed={r['closed']} actions={r['actions']} "
          f"crashes={len(r['crashes'])} {'; '.join(r['notes'])}")
    return r

LIMITS = {'BenchmarkActivity': 1, 'ScreenRecorderActivity': 0, 'FtpActivity': 3,
          'FileManagerActivity': 4, 'SetupActivity': 1}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', '192.168.1.10:43929'))
    ap.add_argument('--only', default=None)
    ap.add_argument('--actions', type=int, default=6)
    ap.add_argument('--lang', default=None, help='force app language, e.g. en or ko')
    ap.add_argument('--out', default='/tmp/opencode/ui-smoke')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    adb = Adb(args.serial)
    ensure_language(adb, args.lang)
    results = []
    for name, label in APPS:
        if args.only and args.only not in name: continue
        try:
            results.append(run_app(adb, name, label, min(args.actions, LIMITS.get(name, args.actions)), args.out))
        except Exception as e:
            results.append({'activity': name, 'label': label, 'error': str(e)})
    path = os.path.join(args.out, 'visual-report.json')
    json.dump(results, open(path, 'w', encoding='utf-8'), indent=2)
    bad = [r for r in results if r.get('crashes') or r.get('notes') or not r.get('opened')]
    print(f'\n{len(results)} apps, {len(bad)} with findings -> {path}')
    for r in bad:
        print(f" - {r['label']}: {r.get('notes')} crashes={len(r.get('crashes') or [])}")
    return 0

if __name__ == '__main__':
    sys.exit(main())
