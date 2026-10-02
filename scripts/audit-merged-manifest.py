#!/usr/bin/env python3
"""Fail on unintended release components, flags or permissions after manifest merge."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
root = ET.parse(path).getroot()
A = '{http://schemas.android.com/apk/res/android}'
assert root.get('package') == 'ru.namaz.safadzhay'
app = root.find('application')
assert app.get(A + 'debuggable', 'false') == 'false'
assert app.get(A + 'testOnly', 'false') == 'false'
assert app.get(A + 'allowBackup') == 'false'
assert app.get(A + 'usesCleartextTraffic') == 'false'
expected = {
    ('activity', 'ru.namaz.safadzhay.MainActivity'): ('true', None),
    ('receiver', 'ru.namaz.safadzhay.PrayerNotificationReceiver'): ('false', None),
    ('receiver', 'ru.namaz.safadzhay.PrayerBootReceiver'): ('false', None),
    ('receiver', 'androidx.profileinstaller.ProfileInstallReceiver'): ('true', 'android.permission.DUMP'),
    ('provider', 'androidx.startup.InitializationProvider'): ('false', None),
}
components = [e for e in app if e.tag in {'activity', 'activity-alias', 'receiver', 'service', 'provider'}]
assert {(e.tag, e.get(A + 'name')) for e in components} == set(expected)
for e in components:
    assert (e.get(A + 'exported'), e.get(A + 'permission')) == expected[e.tag, e.get(A + 'name')]
    assert e.get(A + 'debuggable', 'false') == 'false'
    assert e.get(A + 'grantUriPermissions', 'false') == 'false'
    assert e.get(A + 'testOnly', 'false') == 'false'
    assert not e.findall('intent-filter/data'), 'Unexpected deep link or URI scheme'
activity = app.find('activity')
filters = activity.findall('intent-filter')
assert len(filters) == 1
assert {e.get(A + 'name') for e in filters[0].findall('action')} == {'android.intent.action.MAIN'}
assert {e.get(A + 'name') for e in filters[0].findall('category')} == {'android.intent.category.LAUNCHER'}
permissions = {e.get(A + 'name') for e in root.findall('uses-permission')}
assert permissions == {'android.permission.' + name for name in (
    'INTERNET', 'ACCESS_NETWORK_STATE', 'POST_NOTIFICATIONS', 'VIBRATE', 'RECEIVE_BOOT_COMPLETED',
    'SCHEDULE_EXACT_ALARM', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION')
} | {'ru.namaz.safadzhay.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
permission = root.find('permission')
assert permission.get(A + 'name') == 'ru.namaz.safadzhay.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
assert permission.get(A + 'protectionLevel') == 'signature'
print('PASS merged release manifest: identity, private receivers/provider, permission-gated profile receiver, launcher, flags and permissions')
