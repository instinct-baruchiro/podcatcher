#!/bin/bash
# THROWAWAY (apk-card-check, not for merge): drive the published v0.7 release APK on the emulator.
set -x
mkdir -p out
P=com.baruchiro.podcatcher.next
V6=https://github.com/instinct-baruchiro/podcatcher/releases/download/v0.6-next-trial/podcatcher-next-trial.apk
V7=https://github.com/instinct-baruchiro/podcatcher/releases/download/v0.7-next-trial/podcatcher-next-trial.apk
for i in 1 2 3 4 5; do curl -sSL -o v7.apk $V7 && [ $(stat -c%s v7.apk) -gt 1000000 ] && break; sleep 20; done
echo "d54064270e05d5df4c258fd13a8f18d016ee6ec0af8bae8da9d37095bfb9c645  v7.apk" | sha256sum -c - || exit 1
curl -sSL -o v6.apk $V6
adb uninstall $P >/dev/null 2>&1
# The owner's update path: v0.6 installed, then v0.7 over it.
adb install v6.apk | tail -1
adb shell monkey -p $P 1 >/dev/null 2>&1; sleep 5
adb install -r v7.apk | tail -1
adb shell pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell dumpsys package $P | grep -E "versionCode=|firstInstallTime|lastUpdateTime" | head -3
shot(){ adb exec-out screencap -p > out/$1.png; }
dump(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/$1.xml >/dev/null 2>&1; }
cur(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/_cur.xml >/dev/null 2>&1; }
tapid(){ python3 - "$1" <<'PY'
import sys,re,subprocess
x=open('out/_cur.xml',encoding='utf8').read()
m=re.search(r'resource-id="[^"]*'+sys.argv[1]+r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
if not m: print("NOTFOUND",sys.argv[1]); sys.exit(1)
a,b,c,d=map(int,m.groups()); subprocess.run(["adb","shell","input","tap",str((a+c)//2),str((b+d)//2)])
PY
}
adb shell am force-stop $P; sleep 2
adb shell am start -n $P/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1 || adb shell monkey -p $P 1 >/dev/null 2>&1
sleep 8
shot 01-launch
cur
if grep -q 'text="סיום"' out/_cur.xml; then python3 - <<'PYTAP'
import re,subprocess
x=open('out/_cur.xml',encoding='utf8').read()
m=re.search(r'text="סיום"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
if m:
    a,b,c,d=map(int,m.groups()); subprocess.run(["adb","shell","input","tap",str((a+c)//2),str((b+d)//2)]); print("onboarding finish tapped")
else: print("finish bounds not found")
PYTAP
sleep 3; fi
cur; tapid convInput; sleep 2
adb shell input text 'show%sthe%sdefault%ssettings'
sleep 1; cur; tapid convSend; sleep 3
cur; grep -o 'text="[^"]*מסכים[^"]*"' out/_cur.xml | head -2
if grep -q 'text="מסכים"' out/_cur.xml; then python3 - <<'PY'
import re,subprocess
x=open('out/_cur.xml',encoding='utf8').read()
m=re.search(r'text="לא מסכים"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
a,b,c,d=map(int,m.groups()); subprocess.run(["adb","shell","input","tap",str((a+c)//2),str((b+d)//2)])
PY
sleep 2; fi
sleep 20
shot 02-settings-reply; dump 02-settings-reply
echo "--- card present?"; grep -c 'cardKeepValue' out/02-settings-reply.xml; grep -o 'text="[^"]*כרטיס[^"]*"' out/02-settings-reply.xml | head -5
echo "--- reply texts"; grep -o 'text="[^"]\{4,\}"' out/02-settings-reply.xml | head -20
python3 - <<'PY2'
import re
x=open('out/02-settings-reply.xml',encoding='utf8').read()
m=re.search(r'resource-id="[^"]*cardKeepValue"[^>]*text="([^"]*)"',x) or re.search(r'text="([^"]*)"[^>]*resource-id="[^"]*cardKeepValue"',x)
print("KEEPVALUE-BEFORE-TAP:", m.group(1) if m else "NOTFOUND")
PY2
# Tap the stepper on the live card and confirm the stored value moves 3 -> 5.
cur; tapid cardKeepPlus; sleep 3
shot 03-stepped; dump 03-stepped
python3 - <<'PY'
import re
x=open('out/03-stepped.xml',encoding='utf8').read()
m=re.search(r'resource-id="[^"]*cardKeepValue"[^>]*text="([^"]*)"',x) or re.search(r'text="([^"]*)"[^>]*resource-id="[^"]*cardKeepValue"',x)
print("KEEPVALUE-AFTER-TAP:", m.group(1) if m else "NOTFOUND")
PY
echo "--- bubbles"; grep -o 'text="[^"]*"' out/03-stepped.xml | head -40
adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
for f in out/*.png out/0*.xml; do adb push $f /storage/emulated/0/Download/podcatcher-shots/ >/dev/null; done
rm -f out/_cur.xml
