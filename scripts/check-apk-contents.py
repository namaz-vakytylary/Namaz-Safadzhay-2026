"""Reject accidental signing inputs or test identity in a built stable APK."""
from pathlib import Path
import json
import re
import sys
import zipfile

badging = Path('apk-badging.txt').read_text()
package, code, version = re.search(
    r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
    badging, re.M).groups()
baseline = json.loads(Path('verification/stable-baseline.json').read_text())
assert package == baseline['applicationId']
assert version == '1.4' and int(code) == 34 and int(code) > baseline['versionCode']
for label in re.findall(r"^application-label[^:]*:'(.*)'$", badging, re.M):
    assert label == 'Намаз Вакытлары', 'Unexpected user-facing label'
manifest = Path('apk-manifest.txt').read_text()
assert 'ru.namaz.safadzhay.test' not in manifest
with zipfile.ZipFile(sys.argv[1]) as apk:
    for item in apk.infolist():
        path = Path(item.filename)
        assert path.suffix.lower() not in {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key'}, 'Packaged private signing material'
        assert path.name not in {'keystore.properties', 'signing.properties', 'local.properties', '.env'}
        assert not item.filename.startswith(('app/src/test/', 'verification/', '.git/'))
        # Certificates in META-INF are public. Private key markers must never be present.
        if not item.is_dir():
            data = apk.read(item)
            for marker in ['RamadanUiPreview', 'UiPreviewScene', 'TEST_RAMADAN',
                           'debug_ramadan_ui_scene', 'Тест UI', 'Проверка Рамадана']:
                assert marker.encode('utf-8') not in data, 'Packaged manual Ramadan preview'
                assert marker.encode('utf-16le') not in data, 'Packaged manual Ramadan preview'
            assert not re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data)
            assert not re.search(rb'gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}', data)
print('PASS APK contents and upgrade identity/version prerequisites')
