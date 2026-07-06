#!/usr/bin/env bash
# End-to-end test of Verdure dictation on a live emulator.
#
# Proves the two things device testing kept failing on:
#   1. The floating mic only appears while a text field is focused.
#   2. Transcribed text actually lands in the focused field — asserted by
#      driving the exact production delivery path (DictationCoordinator →
#      accessibility injection) via the debug broadcast, then reading the
#      field back with uiautomator.
#
# Requires: booted emulator on adb, debug APK at $APK.
set -uo pipefail

APK="${APK:-VerdureApp/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT:-e2e-artifacts}"
SERVICE="com.verdure/com.verdure.services.VoiceInputAccessibilityService"
TAG="VoiceInputA11y"
PHRASE_ONE="hello from verdure dictation"
PHRASE_TWO="and a second phrase"

mkdir -p "$OUT"
FAILURES=0

step() { echo; echo "════ $* ════"; }
pass() { echo "✅ PASS: $*"; }
fail() { echo "❌ FAIL: $*"; FAILURES=$((FAILURES + 1)); }

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

# Poll logcat (since last clear) for a pattern, up to N seconds.
wait_for_log() {
  local pattern="$1" timeout="${2:-15}"
  for _ in $(seq 1 "$timeout"); do
    if adb logcat -d -s "$TAG":I 2>/dev/null | grep -qF "$pattern"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml
}

step "Install APK"
adb install -r "$APK" || { fail "APK install"; exit 1; }

step "Enable accessibility service"
adb logcat -c
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
if wait_for_log "Voice input accessibility service connected" 20; then
  pass "accessibility service connected"
else
  fail "accessibility service never connected"
  adb logcat -d > "$OUT/logcat_service_failure.txt"
  exit 1
fi
wait_for_log "Debug dictation hooks registered" 10 \
  && pass "debug hooks registered" \
  || fail "debug hooks not registered (is the APK debuggable?)"

step "Scenario A: home screen — no field focused"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb logcat -c
adb shell am broadcast -a com.verdure.dictation.DEBUG_DUMP > /dev/null
if wait_for_log "no editable target found" 10; then
  pass "no injection target on home screen (as expected)"
else
  fail "expected 'no editable target found' on home screen"
fi
shot "a_home"

step "Scenario B: open test activity — field focused, mic should appear"
adb logcat -c
adb shell am start -n com.verdure/.ui.DictationTestActivity
sleep 4
# Tap the first field to guarantee a focus event.
TAP=$(dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"resource-id=\"com.verdure:id/dictationTestField\"[^>]*"
              r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
')
if [ -n "$TAP" ]; then
  adb shell input tap $TAP
  pass "tapped test field at $TAP"
else
  fail "could not locate dictationTestField in UI dump"
fi
if wait_for_log "Floating mic shown" 10; then
  pass "floating mic appeared when field focused"
else
  fail "floating mic did not appear on field focus"
fi
adb shell am broadcast -a com.verdure.dictation.DEBUG_DUMP > /dev/null
if wait_for_log "editable=true" 10; then
  pass "injection target resolved (editable field found)"
else
  fail "no editable injection target in test activity"
fi
shot "b_field_focused"

step "Scenario C: inject text via production delivery path"
adb logcat -c
adb shell am broadcast -a com.verdure.dictation.DEBUG_INJECT --es text "'$PHRASE_ONE'" > /dev/null
sleep 3
if dump_ui | grep -qF "$PHRASE_ONE"; then
  pass "injected text is inside the text field"
else
  fail "injected text NOT found in UI"
fi
wait_for_log "ACTION_SET_TEXT result=true" 5 \
  && pass "SET_TEXT reported success" \
  || fail "SET_TEXT did not report success"
shot "c_injected"

step "Scenario D: second injection appends with spacing"
adb shell am broadcast -a com.verdure.dictation.DEBUG_INJECT --es text "'$PHRASE_TWO'" > /dev/null
sleep 3
if dump_ui | grep -qF "$PHRASE_ONE $PHRASE_TWO"; then
  pass "second phrase appended after the first with a space"
else
  fail "append-with-spacer behavior broken"
fi
shot "d_appended"

step "Scenario E: leave the field — mic should hide"
adb logcat -c
adb shell input keyevent KEYCODE_HOME
if wait_for_log "Floating mic hidden" 10; then
  pass "floating mic hidden after leaving the text field"
else
  fail "floating mic did not hide on home screen"
fi
shot "e_home_hidden"

step "Collect diagnostics"
adb logcat -d -s "$TAG":I DictationFgService:I > "$OUT/logcat_dictation.txt" || true

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "🎉 ALL DICTATION E2E CHECKS PASSED"
else
  echo "💥 $FAILURES CHECK(S) FAILED"
fi
exit "$FAILURES"
