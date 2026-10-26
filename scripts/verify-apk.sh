#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
MODE="${2:-stable}"
case "$MODE" in
    stable) VERSION_NAME=1.3; VERSION_CODE=33 ;;
    candidate) VERSION_NAME=1.4-rc1; VERSION_CODE=40 ;;
    *) echo 'ERROR: unknown APK verification mode'; exit 1 ;;
esac
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
TOOLS="$ANDROID_HOME/build-tools/36.0.0"
"$TOOLS/aapt" dump badging "$APK" > apk-badging.txt
grep -q "package: name='ru.namaz.safadzhay'" apk-badging.txt
grep -Fq "versionName='$VERSION_NAME'" apk-badging.txt
grep -Fq "versionCode='$VERSION_CODE'" apk-badging.txt
grep -Fxq "application-label:'Намаз Вакытлары'" apk-badging.txt
grep -Fxq "sdkVersion:'23'" apk-badging.txt
grep -Fxq "targetSdkVersion:'37'" apk-badging.txt
grep -q "launchable-activity: name='ru.namaz.safadzhay.MainActivity'" apk-badging.txt
grep -q "uses-permission: name='android.permission.INTERNET'" apk-badging.txt
grep -q "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'" apk-badging.txt
grep -q "uses-permission: name='android.permission.ACCESS_FINE_LOCATION'" apk-badging.txt
grep -q "uses-permission: name='android.permission.ACCESS_COARSE_LOCATION'" apk-badging.txt
for permission in POST_NOTIFICATIONS VIBRATE RECEIVE_BOOT_COMPLETED SCHEDULE_EXACT_ALARM; do
    grep -Fq "uses-permission: name='android.permission.$permission'" apk-badging.txt
done
if grep -q "uses-permission: name='android.permission.ACCESS_BACKGROUND_LOCATION'" apk-badging.txt; then
    echo 'ERROR: Qibla must not request background location'
    exit 1
fi
if grep -q 'application-debuggable' apk-badging.txt; then
    echo 'ERROR: release APK is debuggable'
    exit 1
fi
"$TOOLS/aapt" dump xmltree "$APK" AndroidManifest.xml > apk-manifest.txt
grep -Eq 'usesCleartextTraffic.*0x0$' apk-manifest.txt
"$TOOLS/apksigner" verify --min-sdk-version 23 --verbose --print-certs "$APK" > apk-signing.txt
EXPECTED="$(cat verification/release-certificate.sha256)"
grep -Fq "Signer #1 certificate SHA-256 digest: $EXPECTED" apk-signing.txt
grep -Fxq 'Number of signers: 1' apk-signing.txt
if [ "$MODE" = candidate ]; then
    python3 scripts/check-apk-contents.py "$APK" --candidate
else
    python3 scripts/check-apk-contents.py "$APK"
fi
sha256sum "$APK" > apk.sha256
echo "PASS APK: $MODE $VERSION_NAME/$VERSION_CODE, previous release certificate, permissions, no packaged signing material"
