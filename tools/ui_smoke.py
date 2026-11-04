#!/usr/bin/env python3
"""Drive every built-in Pavo app through a safe, human-like smoke test.

For each activity it launches the screen, dumps the view hierarchy, then taps
reversible controls (switches, chips, sliders, tabs), scrolls lists and checks
that the app stays alive. Crashes, ANRs, blank screens and dead taps are
reported per app together with a screenshot.

Usage:
  python3 tools/ui_smoke.py --serial 192.168.1.10:43929
  python3 tools/ui_smoke.py --only FanActivity --actions 20
"""
import argparse, json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', os.path.expanduser('~/.local/opt/platform-tools/adb'))
PKG = 'com.ben.pavo'

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

SKIP = {  # need extras / not meaningful standalone
    'ImageViewerActivity', 'VideoPlayerActivity', 'AudioPlayerActivity',
    'FileEditorActivity', 'BenchmarkResultActivity',
}

DANGER = re.compile(r'''(?i)(
    delete|remove|erase|wipe|format|uninstall|reset|restore|reboot|shutdown|power off|
    stop|kill|clear all|discard|forget|revoke|factory|
    삭제|제거|초기화|재설정|중지|종료|지우기|포맷|복원|재부팅|
    löschen|entfernen|zurücksetzen|stoppen|beenden|formatieren|deinstallieren|
    supprimer|effacer|réinitialiser|arrêter|désinstaller|
    borrar|eliminar|restablecer|detener|desinstalar|
    eliminare|rimuovere|ripristina|arresta|disinstalla|
    apagar|remover|redefinir|parar|desinstalar|
    удалить|сбросить|остановить|формат|восстановить|
    видалити|скинути|зупинити|
    sil|kaldır|sıfırla|durdur|
    verwĳderen|verwijderen|stopzetten|herstellen|
    verwijder|verwijderen|stopzetten|resetten|
    poista|nollaa|pysäytä|
    slet|fjern|nulstil|stop|
    raderen|verwijderen|resetten|stoppen|
    διαγραφή|κατάργηση|επαναφορά|διακοπή|
    מחיקה|הסרה|איפוס|עצירה|
    törlés|eltávolítás|visszaállítás|leállítás|
    usuń|usuwanie|przywróć|zatrzymaj|
    șterge|elimină|resetează|oprește|
    xóa|xoá|đặt lại|dừng|
    ลบ|รีเซ็ต|หยุด|
    删除|移除|重置|停止|卸载|清除|恢复
)''', re.X)

SAFE = re.compile(r'''(?i)(
    on|off|enable|disable|auto|manual|default|preset|apply|save|refresh|check|learn|
    add|plus|minus|more|less|next|prev|back|close|done|ok|cancel|
    quiet|balanced|performance|extreme|custom|
    켬|끔|사용|해제|자동|수동|기본|적용|저장|새로|확인|추가|다음|이전|닫기|완료|취소
)''', re.X)

IDLE = 0.7

class Adb:
    def __init__(self, serial):
        self.base = [ADB] + (['-s', serial] if serial else [])
    def sh(self, cmd, timeout=30, root=False):
        full = self.base + ['shell', (f"su -c '{cmd}'" if root else cmd)]
        p = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
        return p.stdout.strip(), p.stderr.strip(), p.returncode
    def tap(self, x, y):
        self.sh(f'input tap {x} {y}')
    def swipe(self, x1, y1, x2, y2, ms=350):
        self.sh(f'input swipe {x1} {y1} {x2} {y2} {ms}')
    def dump(self):
        for _ in range(3):
            self.sh('rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1')
            p = subprocess.run(self.base + ['exec-out', 'cat', '/sdcard/ui.xml'],
                               capture_output=True, text=True, timeout=30)
            if p.stdout.startswith('<?xml') and p.stdout.rstrip().endswith('</hierarchy>'):
                return p.stdout
            time.sleep(0.6)
        return p.stdout
    def shot(self, path):
        with open(path, 'wb') as f:
            subprocess.run(self.base + ['exec-out', 'screencap', '-p'],
                           stdout=f, timeout=60)
    def logcat(self, clear=False):
        if clear:
            self.sh('logcat -c')
            return ''
        out, _, _ = self.sh('logcat -d -v brief')
        return out

def parse(xml, want_pkg=None):
    nodes = []
    try:
        root = ET.fromstring(xml)
    except Exception:
        return nodes
    for n in root.iter('node'):
        b = n.get('bounds') or ''
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
        if not m: continue
        x1, y1, x2, y2 = map(int, m.groups())
        if want_pkg and (n.get('package') or '') != want_pkg:
            continue
        nodes.append({
            'text': (n.get('text') or '').strip(),
            'desc': (n.get('content-desc') or '').strip(),
            'cls': n.get('class') or '',
            'clickable': n.get('clickable') == 'true',
            'scrollable': n.get('scrollable') == 'true',
            'checkable': n.get('checkable') == 'true',
            'bounds': (x1, y1, x2, y2),
            'cx': (x1 + x2) // 2, 'cy': (y1 + y2) // 2,
        })
    return nodes

def visible_text(nodes):
    return [n for n in nodes if (len(n['text']) > 1 or len(n['desc']) > 1)]

def crash_scan(log):
    hits = []
    for line in log.splitlines():
        if PKG in line and re.search(r'FATAL EXCEPTION|AndroidRuntime|Force finishing|ANR in ' + PKG, line):
            hits.append(line.strip())
        elif re.search(r'FATAL EXCEPTION|Process: ' + PKG, line):
            hits.append(line.strip())
    return hits

def top_activity(adb):
    out, _, _ = adb.sh('dumpsys window | grep -m1 mCurrentFocus')
    m = re.search(r'u0 ([^/]+)/([^}]+)\}', out)
    return (m.group(1), m.group(2)) if m else (None, None)

def run_app(adb, name, label, actions, outdir):
    print(f'\n=== {label} ({name})')
    result = {'activity': name, 'label': label, 'opened': False,
              'actions': 0, 'crashes': [], 'blank': False, 'notes': []}
    adb.sh('logcat -c')
    adb.sh('input keyevent 3')          # HOME first: never inherit another app
    time.sleep(0.5)
    adb.sh(f'am start -W -n {PKG}/.{name}', root=True, timeout=40)
    pkg, act = (None, None)
    for _ in range(12):
        time.sleep(0.35)
        pkg, act = top_activity(adb)
        if pkg == PKG and name in (act or ''):
            break
    result['opened'] = (pkg == PKG and name in (act or ''))
    if not result['opened']:
        result['notes'].append(f'top={pkg}/{act}')
    nodes = parse(adb.dump(), PKG)
    if len(visible_text(nodes)) == 0:
        result['blank'] = True
    screens = set()
    for i in range(actions):
        nodes = parse(adb.dump(), PKG)
        if not nodes:
            result['notes'].append('empty hierarchy')
            break
        texts = tuple(sorted(n['text'] for n in visible_text(nodes)))[:40]
        screens.add(texts)
        # scroll first if there is a scrollable container
        scroll = [n for n in nodes if n['scrollable']]
        tapped = False
        for n in nodes:
            t = n['text'] or n['desc']
            if not n['clickable'] or not t or len(t) > 60: continue
            if DANGER.search(t): continue
            safe = n['checkable'] or 'Switch' in n['cls'] or 'CheckBox' in n['cls'] \
                or 'RadioButton' in n['cls'] or 'SeekBar' in n['cls'] or SAFE.search(t)
            if not safe: continue
            if 'SeekBar' in n['cls']:
                x1, y1, x2, y2 = n['bounds']
                adb.tap(x1 + (x2 - x1) * (1 if i % 2 else 0) // 4, n['cy'])
            else:
                adb.tap(n['cx'], n['cy'])
            result['actions'] += 1
            tapped = True
            time.sleep(IDLE)
            break
        if not tapped and scroll:
            s = scroll[0]
            x1, y1, x2, y2 = s['bounds']
            adb.swipe((x1+x2)//2, y2 - 40, (x1+x2)//2, y1 + 40, 320)
            result['actions'] += 1
            time.sleep(IDLE)
        elif not tapped:
            result['notes'].append('no safe control on screen')
            break
        if crash_scan(adb.logcat()):
            break
    log = adb.logcat()
    result['crashes'] = crash_scan(log)
    result['distinct_screens'] = len(screens)
    if result['distinct_screens'] <= 1 and result['actions'] > 2:
        result['notes'].append('screen never changed')
    adb.sh('input keyevent 4')          # exercise back navigation
    time.sleep(0.5)
    adb.sh('input keyevent 3')
    time.sleep(0.4)
    shot = os.path.join(outdir, f'{name}.png')
    adb.shot(shot)
    result['screenshot'] = shot
    print(f"   opened={result['opened']} actions={result['actions']} "
          f"screens={result['distinct_screens']} crashes={len(result['crashes'])} "
          f"{'BLANK ' if result['blank'] else ''}{'; '.join(result['notes'])}")
    return result

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', '192.168.1.10:43929'))
    ap.add_argument('--only', default=None)
    ap.add_argument('--actions', type=int, default=8)
    ap.add_argument('--out', default='/tmp/opencode/ui-smoke')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    adb = Adb(args.serial)
    apps = [a for a in APPS if args.only is None or args.only in a[0]]
    results = []
    for name, label in apps:
        try:
            results.append(run_app(adb, name, label, args.actions, args.out))
        except Exception as e:
            results.append({'activity': name, 'label': label, 'error': str(e)})
    path = os.path.join(args.out, 'report.json')
    json.dump(results, open(path, 'w', encoding='utf-8'), indent=2)
    bad = [r for r in results if r.get('crashes') or r.get('blank') or not r.get('opened')]
    print(f'\n{len(results)} apps, {len(bad)} with findings -> {path}')
    for r in bad:
        print(f" - {r['label']}: crashes={len(r.get('crashes') or [])} blank={r.get('blank')} opened={r.get('opened')}")
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main())
