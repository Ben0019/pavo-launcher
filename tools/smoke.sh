#!/usr/bin/env bash
# Smoke test for a Pavo build.
#
#   tools/smoke.sh                     # test the installed build on the only device
#   tools/smoke.sh --serial 1.2.3.4:5555
#   tools/smoke.sh --apk Pavo.apk      # install first, then test
#
# Checks that the home screen and every built-in activity launches without a
# crash, and that the preference store is readable, seeded and inside range.
set -uo pipefail

PKG=com.ben.pavo
ADB=${ADB:-adb}
SERIAL=""
APK=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -n "$SERIAL" ]; then
    ADB="$ADB -s $SERIAL"
fi

# Activities that are launched straight from the home grid.
ACTIVITIES=(
    MainActivity
    FanActivity
    DisplayActivity
    PerfLogActivity
    PerformanceTunerActivity
    RgbActivity
    DeviceHealthActivity
    FileManagerActivity
    ScreenRecorderActivity
    ControllerMapActivity
    TouchMapperActivity
    BenchmarkActivity
    OverlayActivity
)

fail=0
pass() { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; fail=1; }

echo "Pavo smoke test ($($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"

$ADB wait-for-device

ROOT=""
if $ADB shell 'su -c true' >/dev/null 2>&1; then
    ROOT=1
fi
start_activity() {
    if [ -n "$ROOT" ]; then
        $ADB shell "su -c 'am start -W -n $PKG/.$1'" 2>&1
    else
        $ADB shell am start -W -n "$PKG/.$1" 2>&1
    fi
}
if [ -n "$APK" ]; then
    echo "Installing $APK"
    $ADB install -r "$APK" >/dev/null 2>&1 || { echo "install failed" >&2; exit 1; }
fi

if ! $ADB shell pm path "$PKG" >/dev/null 2>&1; then
    echo "$PKG is not installed" >&2
    exit 1
fi
pass "package installed"

$ADB logcat -c >/dev/null 2>&1

echo "Launching activities"
for activity in "${ACTIVITIES[@]}"; do
    out=$(start_activity "$activity")
    if ! printf '%s' "$out" | grep -qE "Status: ok|Warning: Activity not started"; then
        bad "$activity did not start"
        continue
    fi
    sleep 1
    if ! $ADB shell pidof "$PKG" >/dev/null 2>&1; then
        bad "$activity crashed the process"
        continue
    fi
    if $ADB logcat -d 2>/dev/null | grep -q "FATAL EXCEPTION"; then
        bad "$activity raised a fatal exception"
        $ADB logcat -d -s AndroidRuntime | grep -A5 "FATAL EXCEPTION" | head -12
        break
    fi
    pass "$activity"
done

if $ADB logcat -d 2>/dev/null | grep -qiE "ANR in $PKG"; then
    bad "ANR recorded for $PKG"
else
    pass "no ANR"
fi

start_activity MainActivity >/dev/null 2>&1

echo "Preferences"
prefs=""
if $ADB shell 'su -c true' >/dev/null 2>&1; then
    prefs=$($ADB shell "su -c 'cat /data/data/$PKG/shared_prefs/pavo.xml'" 2>/dev/null)
elif $ADB shell "run-as $PKG true" >/dev/null 2>&1; then
    prefs=$($ADB shell "run-as $PKG cat shared_prefs/pavo.xml" 2>/dev/null)
fi

if [ -z "$prefs" ]; then
    echo "  skip  preferences not readable (no root and not debuggable)"
else
    printf '%s' "$prefs" | grep -q "<map>" && pass "prefs readable" \
        || bad "prefs unreadable"
    printf '%s' "$prefs" | grep -q 'name="defaults_seeded" value="true"' \
        && pass "defaults seeded" || bad "defaults not seeded"

    # Every glass value must sit inside the range its slider allows.
    check_int() {
        local key=$1 min=$2 max=$3
        local v
        v=$(printf '%s' "$prefs" | sed -n "s/.*name=\"$key\" value=\"\([-0-9]*\)\".*/\1/p" | head -1)
        [ -z "$v" ] && { pass "$key default"; return; }
        if [ "$v" -ge "$min" ] && [ "$v" -le "$max" ]; then
            pass "$key = $v"
        else
            bad "$key = $v (expected $min..$max)"
        fi
    }
    for surface in dock drawer popup_drawer folder menu settings builtin; do
        case "$surface" in
            drawer) t=app_drawer_transparency ;;
            *) t=${surface}_transparency ;;
        esac
        check_int "$t" 20 100
        check_int "${surface}_saturation" 0 200
        check_int "${surface}_darkness" 0 100
    done
    check_int glass 0 50
    check_int dock_sheen 0 100
    check_int haptics_strength 0 100
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "SMOKE PASS"
else
    echo "SMOKE FAIL"
fi
exit "$fail"
