#!/bin/bash
# THROWAWAY: seeded old-build data survives in-place update v0.3 -> v0.4.
PKG=com.baruchiro.podcatcher.next
BASE=https://github.com/instinct-baruchiro/podcatcher/releases/download
O=out; mkdir -p $O
curl -sSL -o $O/old.apk $BASE/v0.3-next-trial/podcatcher-next-trial.apk
curl -sSL -o $O/new.apk $BASE/v0.4-next-trial/podcatcher-next-trial.apk
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $O/u.xml >/dev/null 2>&1; }
shot() { adb exec-out screencap -p > $O/$1.png; }
vers() { adb shell dumpsys package $PKG | grep -E "versionCode|versionName|firstInstallTime" | head -4; }
rp() { adb shell run-as $PKG cat shared_prefs/$1.xml 2>&1; }
adb uninstall $PKG >/dev/null 2>&1
adb install $O/old.apk > $O/01-install-old.txt 2>&1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >> $O/01-install-old.txt 2>&1
vers > $O/01-vers-old.txt
# run old once so it creates its own data dir, then stop and seed old-format data
adb shell am start -n $PKG/com.baruchiro.podcatcher.ui.MainActivity > $O/01-start.txt 2>&1; sleep 6
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; cat > shared_prefs/podcatcher.xml'" <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="default_policy">{"autoDownload":false,"keep":5,"speed":1,"skipIntroSec":0}</string>
    <string name="library">[{"feedUrl":"https:\/\/example.invalid\/old.xml","title":"Seeded Old Podcast","author":"Old Author","artwork":"","policy":{"autoDownload":true,"keep":7,"speed":1.25,"skipIntroSec":30},"lastSeenGuid":""}]</string>
</map>
X
adb shell "run-as $PKG sh -c 'cat > shared_prefs/telemetry.xml'" <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="analytics_enabled" value="false" /></map>
X
rp podcatcher > $O/b1-before-update.txt; rp telemetry >> $O/b1-before-update.txt
adb shell am start -n $PKG/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1; sleep 6; shot a1-old-build-with-seed
adb shell am force-stop $PKG
rp podcatcher > $O/b1b-old-after-run.txt
adb install -r $O/new.apk > $O/02-install-new.txt 2>&1; cat $O/02-install-new.txt
vers > $O/02-vers-new.txt
rp podcatcher > $O/b2-after-update.txt; rp telemetry >> $O/b2-after-update.txt
adb shell am start -n $PKG/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1; sleep 8; shot c1-new-first-launch
# scroll the drawer to its top so the lead bubble is visible
for i in 1 2 3; do adb shell input swipe 540 1400 540 2000 250; sleep 1; done
shot c1b-lead-top
dump; cp $O/u.xml $O/c1b-ui.xml
adb shell am force-stop $PKG; adb shell am start -n $PKG/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1; sleep 6
rp podcatcher > $O/b3-after-new-launch.txt; rp telemetry >> $O/b3-after-new-launch.txt
shot c2-library-after-update
cmp <(rp podcatcher) $O/b1-before-update.txt >/dev/null 2>&1; 
adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
adb push $O/. /storage/emulated/0/Download/podcatcher-shots/ >/dev/null 2>&1
