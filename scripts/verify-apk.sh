#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
"$TOOLS/aapt" dump badging "$APK" > apk-badging.txt
grep -q "package: name='ru.namaz.safadzhay'" apk-badging.txt
grep -Fq "versionName='1.2'" apk-badging.txt
grep -Fq "versionCode='31'" apk-badging.txt
if grep -q 'ТЕСТ' apk-badging.txt; then exit 1; fi
grep -q "launchable-activity: name='ru.namaz.safadzhay.MainActivity'" apk-badging.txt
if grep -q "uses-permission: name='android.permission.INTERNET'" apk-badging.txt; then
    echo 'ERROR: offline test app unexpectedly requests Internet access'
    exit 1
fi
grep -q "uses-permission: name='android.permission.ACCESS_FINE_LOCATION'" apk-badging.txt
grep -q "uses-permission: name='android.permission.ACCESS_COARSE_LOCATION'" apk-badging.txt
if grep -q "uses-permission: name='android.permission.ACCESS_BACKGROUND_LOCATION'" apk-badging.txt; then
    echo 'ERROR: Qibla must not request background location'
    exit 1
fi
if grep -q 'application-debuggable' apk-badging.txt; then
    echo 'ERROR: release APK is debuggable'
    exit 1
fi
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > apk-signing.txt
EXPECTED="$(cat verification/release-certificate.sha256)"
grep -Fq "Signer #1 certificate SHA-256 digest: $EXPECTED" apk-signing.txt
echo 'PASS APK: original package and signing certificate, stable name/version, release build'
