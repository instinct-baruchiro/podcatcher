#!/bin/bash
# THROWAWAY: update-in-place check of v0.3 -> v0.4 trial APKs on the CI emulator.
PKG=com.baruchiro.podcatcher.next
BASE=https://github.com/instinct-baruchiro/podcatcher/releases/download
O=out; mkdir -p $O
curl -sSL -o $O/old.apk $BASE/v0.3-next-trial/podcatcher-next-trial.apk
curl -sSL -o $O/new.apk $BASE/v0.4-next-trial/podcatcher-next-trial.apk
ls -l $O/*.apk > $O/00-apks.txt
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $O/u.xml >/dev/null 2>&1; }
tap_id() { # $1 resource id suffix
  for i in 1 2 3 4 5 6; do dump
    c=$(python3 - "$1" <<'PY'
import re,sys
s=open('out/u.xml',encoding='utf8').read()
for m in re.finditer(r'<node[^>]*resource-id="[^"]*:id/'+sys.argv[1]+r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s):
    a=list(map(int,m.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
    [ -n "$c" ] && { adb shell input tap $c; return 0; }; sleep 2; done; echo "no $1" >> $O/log.txt; return 1; }
say1() { tap_id convInput; sleep 1; adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep 1; tap_id convSend; }
say() { for n in 1 2 3 4; do say1 "$1"; sleep 22; dump; grep -q "HTTP 50" $O/u.xml || return 0; echo "retry $n for: $1" >> $O/log.txt; sleep 10; done; }
shot() { adb exec-out screencap -p > $O/$1.png; }
prefs() { for f in podcatcher telemetry conv_config; do echo "== $f"; adb shell run-as $PKG cat shared_prefs/$f.xml 2>&1 | cut -c1-600; done > $O/$1.txt; adb shell run-as $PKG ls -R files shared_prefs databases >> $O/$1.txt 2>&1; }
vers() { adb shell dumpsys package $PKG | grep -E "versionCode|versionName|lastUpdateTime|firstInstallTime" | head -5; }
adb uninstall $PKG >/dev/null 2>&1
adb install $O/old.apk > $O/01-install-old.txt 2>&1; vers > $O/01-vers-old.txt
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >> $O/01-install-old.txt 2>&1
adb shell am start -n $PKG/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1 || adb shell monkey -p $PKG 1 >/dev/null 2>&1
sleep 6; adb shell input keyevent KEYCODE_BACK; sleep 1; shot a1-old-launch
say "keep 3 episodes for every podcast"; sleep 25; shot a2-old-reply
say "subscribe to https://feeds.feedburner.com/dancarlin/history?format=xml"; shot a3-old-search
sleep 15; shot a4-old-subscribed
adb shell am force-stop $PKG; sleep 1
# seed an earlier analytics opt-out (old build has no switch): enabled=false
adb shell "run-as $PKG sh -c 'cat > shared_prefs/telemetry.xml'" <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="analytics_enabled" value="false" /></map>
X
prefs b1-prefs-before-update
# UPDATE IN PLACE: -r, no uninstall
adb install -r $O/new.apk > $O/02-install-new.txt 2>&1; cat $O/02-install-new.txt
vers > $O/02-vers-new.txt
prefs b2-prefs-after-update
adb shell monkey -p $PKG 1 >/dev/null 2>&1; sleep 8; shot c1-new-first-launch
tap_id onboardDone; sleep 2; shot c2-after-done
prefs b3-prefs-after-done
say "subscribe to https://feeds.feedburner.com/dancarlin/history?format=xml"; sleep 10; shot d1-search
shot d2-readback
say "auto-download only this podcast"; shot d3-override
prefs b4-prefs-final
adb shell am force-stop $PKG; adb shell monkey -p $PKG 1 >/dev/null 2>&1; sleep 6; shot e1-relaunch
adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
adb push $O/. /storage/emulated/0/Download/podcatcher-shots/ >/dev/null 2>&1
