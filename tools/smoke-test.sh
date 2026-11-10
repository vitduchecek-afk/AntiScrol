#!/usr/bin/env bash
#
# Emulator smoke test, run by CI:  tools/smoke-test.sh <release.apk> <debug.apk>
#
# Part 1 installs the release APK and checks that the app starts and its screens work.
# Part 2 installs the debug APK (same key, so it installs over the release one) and uses
# its test hook to try blocked navigations on the real, logged-out instagram.com.
#
# It cannot log in, so it says nothing about the stories row, the feed or messages.
set -u

RELEASE_APK=$1
DEBUG_APK=$2
PKG=cz.antiscrol.app
ACTIVITY=$PKG/.MainActivity
OUT=smoke-out
mkdir -p "$OUT"
failures=0
shot=0
system_dialogs=0

ok()   { echo "  ok   $*"; }
bad()  { echo "  FAIL $*"; failures=$((failures + 1)); }
note() { echo; echo "### $*"; }

screenshot() {
  shot=$((shot + 1))
  adb exec-out screencap -p > "$OUT/$(printf '%02d' "$shot")-$1.png" 2>/dev/null || true
}

# Writes "text<TAB>bounds" for every labelled node on screen to $OUT/ui.txt.
dump_ui() {
  : > "$OUT/ui.txt"
  for _ in 1 2 3 4 5 6 7 8; do
    if adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then
      adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml"
      python3 - "$OUT/ui.xml" > "$OUT/ui.txt" <<'PY'
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter('node'):
    label = (node.get('text') or node.get('content-desc') or '').strip().replace('\n', ' ')
    if label:
        print(label[:160] + '\t' + node.get('bounds', ''))
PY
      # CI emulators are slow and sometimes show "<system app> isn't responding" over
      # everything. That is not the app under test: dismiss it and look again.
      if grep -F "isn't responding" "$OUT/ui.txt" | grep -q "AntiScrol"; then
        bad "the app itself stopped responding"
        return 0
      fi
      if grep -qF "isn't responding" "$OUT/ui.txt"; then
        echo "  (dismissing a system dialog: $(grep -F "isn't responding" "$OUT/ui.txt" | cut -f1 | head -1))"
        system_dialogs=$((system_dialogs + 1))
        tap_text_quiet "Wait"
        sleep 3
        continue
      fi
      return 0
    fi
    sleep 2
  done
  echo "  (could not dump the UI)"
  return 1
}

tap_text_quiet() {
  local line bounds
  line=$(grep -F -m1 "$1"$'\t' "$OUT/ui.txt") || return 1
  bounds=${line##*$'\t'}
  if [[ $bounds =~ \[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
    adb shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
  fi
}

show_ui() { cut -f1 "$OUT/ui.txt" | head -40 | sed 's/^/      | /'; }
on_screen() { grep -qF "$1" "$OUT/ui.txt"; }

expect_on_screen()     { if on_screen "$1"; then ok "screen shows \"$1\""; else bad "screen should show \"$1\""; fi; }
expect_not_on_screen() { if on_screen "$1"; then bad "screen should not show \"$1\""; else ok "screen does not show \"$1\""; fi; }

tap_text() { tap_text_quiet "$1" || { bad "nothing to tap for \"$1\""; return 1; }; }

expect_alive() {
  if [[ -n "$(adb shell pidof $PKG | tr -d '\r')" ]]; then ok "app is running ($1)"; else bad "app is not running ($1)"; fi
}

expect_no_crash() {
  if adb logcat -d -b crash | grep -q "$PKG"; then
    bad "crash in logcat ($1)"
    adb logcat -d -b crash | tail -40
  else
    ok "no crash in logcat ($1)"
  fi
}

app_log() { adb logcat -d -s AntiScrol:D | grep -v '^-----' || true; }
expect_log() { if app_log | grep -qF "$1"; then ok "log has \"$1\""; else bad "log should have \"$1\""; fi; }

test_command() { adb shell am start -n "$ACTIVITY" --es antiscrol.test "$1" > /dev/null; }

note "device"
sleep 20   # let the freshly booted launcher settle
adb shell getprop ro.build.version.release | sed 's/^/  Android /'
adb shell dumpsys webviewupdate | grep -i "current webview package" | sed 's/^ */  /'

# ------------------------------------------------------------------------------------------
note "part 1: release APK starts"
adb install -r "$RELEASE_APK" | tail -1
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | grep -E "Status|TotalTime" | sed 's/^/  /'
sleep 30
expect_alive "30 s after start"
expect_no_crash "after start"
dump_ui
screenshot release-start
echo "  what is on screen:"; show_ui
for tab in "Stories" "Zprávy" "+ Story" "Nastavení"; do expect_on_screen "$tab"; done

note "part 1: settings"
tap_text "Nastavení"; sleep 3; dump_ui; screenshot settings
expect_on_screen "Povolit profily"
expect_on_screen "Odhlásit a smazat data"
tap_text "Povolit profily"; sleep 4
expect_alive "after switching profiles on"
dump_ui; tap_text "Povolit profily"; sleep 4

note "part 1: tabs"
dump_ui; tap_text "Zprávy"; sleep 15; dump_ui; screenshot messages-tab
expect_not_on_screen "Povolit profily"
expect_alive "after the messages tab"
echo "  what is on screen:"; show_ui
tap_text "+ Story"; sleep 12; dump_ui; screenshot add-story-tab
expect_alive "after the + Story tab"
tap_text "Stories"; sleep 10
expect_alive "after the stories tab"
expect_no_crash "after using the tabs"

note "part 1: the release APK ignores the test hook"
adb logcat -c
test_command "push:/explore/"; sleep 5; dump_ui
expect_not_on_screen "Tohle je zablokované"

# ------------------------------------------------------------------------------------------
note "part 2: debug APK, blocking on the real site (logged out)"
adb install -r "$DEBUG_APK" | tail -1
adb logcat -c
adb shell am start -W -n "$ACTIVITY" > /dev/null
sleep 30
expect_alive "debug build started"
dump_ui; screenshot debug-start
echo "  what is on screen:"; show_ui

test_command "state"; sleep 3
echo "  page script state:"; app_log | grep "page: state" | tail -1 | sed 's/^/      | /'
expect_log "page: state"
if app_log | grep "page: state" | tail -1 | grep -q '"mode"'; then
  ok "the script is installed in the real page and talks to the app"
else
  bad "the script is not installed in the real page"
fi

note "part 2: in-page navigation to /explore/ (history.pushState)"
test_command "push:/explore/"; sleep 5; dump_ui; screenshot blocked-push
expect_on_screen "Tohle je zablokované"
expect_log "blocked attempt"
tap_text "Zpět na Stories"; sleep 8; dump_ui
expect_not_on_screen "Tohle je zablokované"
test_command "state"; sleep 3
if app_log | grep "page: state" | tail -1 | grep -q "explore"; then bad "still on /explore/"; else ok "not on /explore/ afterwards"; fi

note "part 2: reel that was not opened from messages (history.pushState)"
test_command "push:/reel/SMOKETEST/"; sleep 5; dump_ui
expect_on_screen "Tohle je zablokované"
adb shell input keyevent KEYCODE_BACK; sleep 6; dump_ui
expect_not_on_screen "Tohle je zablokované"
expect_on_screen "Stories"
expect_alive "after the back button on the blocked screen"

note "part 2: page load of /reels/ (link click / location change)"
adb logcat -c
test_command "assign:/reels/"; sleep 6; dump_ui; screenshot blocked-load
expect_on_screen "Tohle je zablokované"
expect_log "blocked load"
tap_text "Zpět na Stories"; sleep 8; dump_ui
expect_not_on_screen "Tohle je zablokované"

note "part 2: navigation that goes around the page script (backstop in the app)"
adb logcat -c
test_command "rawpush:/explore/"; sleep 8
expect_log "blocked committed"
test_command "state"; sleep 3
echo "  page script state, before anything is tapped:"; app_log | grep "page: state" | tail -1 | sed 's/^/      | /'
if app_log | grep "page: state" | tail -1 | grep -q "explore"; then
  bad "the app did not leave /explore/ on its own"
else
  ok "the app left /explore/ on its own"
fi
echo "  app log:"; app_log | grep -v "page: state" | tail -25 | sed 's/^/      | /'
dump_ui; screenshot blocked-backstop
expect_on_screen "Tohle je zablokované"
tap_text "Zpět na Stories"; sleep 10; dump_ui
expect_not_on_screen "Tohle je zablokované"
test_command "state"; sleep 3
if app_log | grep "page: state" | tail -1 | grep -q "explore"; then bad "still on /explore/"; else ok "not on /explore/ afterwards"; fi

note "part 2: back button never returns to a blocked page"
for _ in 1 2 3 4; do
  adb shell input keyevent KEYCODE_BACK; sleep 5
  [[ -z "$(adb shell dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity' | grep "$PKG")" ]] && break
  test_command "state"; sleep 2
  where=$(app_log | grep "page: state" | tail -1)
  if echo "$where" | grep -qE "explore|reels|SMOKETEST"; then bad "back landed on a blocked page: $where"; fi
done
ok "walked the history back without landing on a blocked page"

note "part 2: an allowed page still loads"
adb shell am start -W -n "$ACTIVITY" > /dev/null; sleep 6
adb logcat -c
test_command "assign:/accounts/login/"; sleep 10; dump_ui
expect_not_on_screen "Tohle je zablokované"
expect_log "allowed AUTH"
expect_alive "at the end"
expect_no_crash "debug build"

note "app log (debug build)"
app_log | tail -40 | sed 's/^/  /'

echo
echo "system dialogs dismissed along the way: $system_dialogs"
if [[ $failures -eq 0 ]]; then echo "SMOKE TEST PASSED"; else echo "SMOKE TEST FAILED: $failures check(s)"; fi
exit $(( failures > 0 ? 1 : 0 ))
