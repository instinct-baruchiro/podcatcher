#!/bin/bash
# THROWAWAY: build this source, fresh-install, capture the first-run lead.
PKG=com.baruchiro.podcatcher.next
O=out; mkdir -p $O
S="$PKG/com.baruchiro.podcatcher.ui.MainActivity"
./gradlew assembleDebug -q > $O/build.txt 2>&1
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb uninstall $PKG >/dev/null 2>&1
adb install $APK > $O/install.txt 2>&1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >> $O/install.txt 2>&1
adb shell am start -n $S >/dev/null 2>&1; sleep 10
adb exec-out screencap -p > $O/c1-first-launch-lead.png
adb shell mkdir -p /storage/emulated/0/Download/podcatcher-shots
adb push $O/. /storage/emulated/0/Download/podcatcher-shots/ >/dev/null 2>&1
