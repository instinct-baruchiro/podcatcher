#!/bin/bash
mkdir -p out
P=com.baruchiro.podcatcher.next
curl -sSL -o app.apk https://github.com/instinct-baruchiro/podcatcher/releases/download/v0.3-next-trial/podcatcher-next-trial.apk; sha256sum app.apk
adb uninstall $P >/dev/null 2>&1
adb install -r app.apk | tail -1
adb shell pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell am start -n $P/com.baruchiro.podcatcher.ui.MainActivity >/dev/null 2>&1 || adb shell monkey -p $P 1 >/dev/null 2>&1
sleep 6
shot(){ adb exec-out screencap -p > out/$1.png; }
dump(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/$1.xml >/dev/null 2>&1; }
tapid(){ python3 - "$1" <<'PY'
import sys,re,subprocess
x=open('out/_cur.xml',encoding='utf8').read()
m=re.search(r'resource-id="[^"]*'+sys.argv[1]+r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
if not m: print("NOTFOUND",sys.argv[1]); sys.exit(1)
a,b,c,d=map(int,m.groups()); subprocess.run(["adb","shell","input","tap",str((a+c)//2),str((b+d)//2)])
PY
}
cur(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/_cur.xml >/dev/null 2>&1; }
shot 01-launch
# consent dialog may appear on first send; handle later
cur; tapid convInput; sleep 2
adb shell input text 'keep%sonly%s3%sepisodes%sof%seach%spodcast'
sleep 1; shot 02-typed
cur; tapid convSend; sleep 3
shot 03-sending
cur; grep -o 'text="[^"]*מסכים[^"]*"' out/_cur.xml | head -2
if grep -q 'text="מסכים"' out/_cur.xml; then python3 - <<'PY'
import re,subprocess
x=open('out/_cur.xml',encoding='utf8').read()
m=re.search(r'text="לא מסכים"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
a,b,c,d=map(int,m.groups()); subprocess.run(["adb","shell","input","tap",str((a+c)//2),str((b+d)//2)])
PY
sleep 2; fi
sleep 15
shot 04-reply; dump 04-reply
echo "--- bubbles after first send"; grep -o 'text="[^"]*"' out/04-reply.xml | head -30
adb shell am force-stop $P; sleep 2
adb shell monkey -p $P 1 >/dev/null 2>&1; sleep 6
shot 05-after-kill; dump 05-after-kill
echo "--- prefs after kill (no key files)"
for f in $(adb shell run-as $P ls shared_prefs 2>/dev/null | tr -d '\r'); do echo "== $f"; adb shell run-as $P cat shared_prefs/$f | grep -iv 'key\|secret\|token' | head -20; done
echo "--- chat persistence on relaunch"
grep -o 'text="[^"]*"' out/05-after-kill.xml | head -20
rm -f out/_cur.xml

adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
for f in out/*.png out/*.xml; do adb push $f /storage/emulated/0/Download/podcatcher-shots/ >/dev/null; done
