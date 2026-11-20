#!/bin/bash
# THROWAWAY: exact v0.5 APK, in-place over v0.3 with seeded old data; captures the first-run lead.
PKG=com.baruchiro.podcatcher.next
BASE=https://github.com/instinct-baruchiro/podcatcher/releases/download
O=out; mkdir -p $O
curl -sSL -o $O/old.apk $BASE/v0.3-next-trial/podcatcher-next-trial.apk
curl -sSL -o $O/new.apk $BASE/v0.5-next-trial/podcatcher-next-trial.apk
sha256sum $O/new.apk > $O/00-new-sha.txt
shot() { adb exec-out screencap -p > $O/$1.png; }
rp() { adb shell run-as $PKG cat shared_prefs/$1.xml 2>&1; }
S="$PKG/com.baruchiro.podcatcher.ui.MainActivity"
adb uninstall $PKG >/dev/null 2>&1
adb install $O/old.apk > $O/01-install-old.txt 2>&1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >> $O/01-install-old.txt 2>&1
adb shell am start -n $S >/dev/null 2>&1; sleep 6; adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'cat > shared_prefs/podcatcher.xml'" <<'X'
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
rp podcatcher > $O/b1.txt; rp telemetry >> $O/b1.txt
adb install -r $O/new.apk > $O/02-install-new.txt 2>&1
adb shell dumpsys package $PKG | grep -E "versionCode|versionName|firstInstallTime" | head -3 > $O/02-vers.txt
rp podcatcher > $O/b2.txt; rp telemetry >> $O/b2.txt
adb shell am start -n $S >/dev/null 2>&1; sleep 9; shot c1-first-launch-lead
rp podcatcher > $O/b3.txt; rp telemetry >> $O/b3.txt
adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
adb push $O/. /storage/emulated/0/Download/podcatcher-shots/ >/dev/null 2>&1
