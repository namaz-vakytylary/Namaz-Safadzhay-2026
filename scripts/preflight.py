#!/usr/bin/env python3
"""Run before building: validate original data, identity, resources and CI scripts."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/ru/namaz/safadzhay'
s = (SRC / 'MainActivity.kt').read_text()
fixture_path = ROOT / 'verification/original_timetables.json'
assert hashlib.sha256(fixture_path.read_bytes()).hexdigest() == (ROOT / 'verification/timetables.sha256').read_text().strip(), 'Original fixture was changed'
fixture = json.loads(fixture_path.read_text())
for name, original in fixture.items():
    block = s.split('private val '+name+' = listOf(', 1)[1].split('\n    )', 1)[0]
    actual = re.findall(r'PrayerDay\("([^\"]+)", "([^\"]+)", "([^\"]+)", "([^\"]+)", "([^\"]+)", "([^\"]+)"\)', block)
    assert [list(x) for x in actual] == original, name + ': original prayer times changed'
    assert len(actual) == 153 and len({x[0] for x in actual}) == 153
    for i, row in enumerate(actual):
        assert dt.date.fromisoformat(row[0]) == dt.date(2026, 8, 1) + dt.timedelta(days=i)
        for value in row[1:]: dt.time.fromisoformat(value)
    print('PASS exact original timetable:', name, '153 days / 765 times')
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {x.get(ns+'name') for x in manifest.findall('uses-permission')}
for permission in ['POST_NOTIFICATIONS','VIBRATE','RECEIVE_BOOT_COMPLETED','SCHEDULE_EXACT_ALARM','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION']:
    assert 'android.permission.'+permission in permissions, permission
assert 'android.permission.INTERNET' in permissions
assert 'android.permission.ACCESS_NETWORK_STATE' in permissions
assert 'android.permission.ACCESS_BACKGROUND_LOCATION' not in permissions
app = manifest.find('application')
assert app.get(ns+'allowBackup') == 'false'
build = (ROOT / 'app/build.gradle.kts').read_text()
for required in ['applicationId = "ru.namaz.safadzhay.test"','versionCode = 35','versionName = "1.2-test3"','namaz-release.jks','isDebuggable = false']:
    assert required in build, required
assert not (ROOT / 'app/namaz-test.jks').exists()
assert (ROOT / 'app/namaz-release.jks').is_file()
for xml in (ROOT/'app/src/main/res').rglob('*.xml'): ET.parse(xml)
assert 'Намаз Вакытлары Тест' in (ROOT/'app/src/main/res/values/strings.xml').read_text()
resources = {p.stem for p in (ROOT/'app/src/main/res').rglob('*') if p.is_file()}
for code in SRC.glob('*.kt'):
    for res in re.findall(r'(?<!android\.)R\.(?:drawable|mipmap)\.(\w+)',code.read_text()):
        assert res in resources, 'Missing resource: '+res
for script in (ROOT/'scripts').glob('*.sh'):
    subprocess.run(['bash','-n',str(script)],check=True)
workflow=(ROOT/'.github/workflows/build-apk.yml').read_text()
for marker in ['python3 scripts/preflight.py',':app:testReleaseUnitTest',':app:lintRelease',':app:assembleRelease','scripts/verify-apk.sh']:
    assert marker in workflow, marker
assert workflow.index('python3 scripts/preflight.py') < workflow.index(':app:assembleRelease')
# Published Gradle 8.10.2 checksums: services.gradle.org/distributions/.
assert hashlib.sha256((ROOT/'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest() == '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
assert 'distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26' in (ROOT/'gradle/wrapper/gradle-wrapper.properties').read_text()
subprocess.run(['bash','-n',str(ROOT/'gradlew')],check=True)
print('PASS separate test identity, original data fixture, permissions, XML, resources, shell syntax and CI gates')
