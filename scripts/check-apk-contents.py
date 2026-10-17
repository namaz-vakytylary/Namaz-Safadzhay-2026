#!/usr/bin/env python3
"""TEST identity and no packaged prayer tables, fixtures or signing material."""
from pathlib import Path
import hashlib
import re
import sys
import zipfile

# Public certificates already bundled by the pinned RuStore SDK; exact bytes only.
PUBLIC_SDK_CERTIFICATES = {'res/raw/ministry_of_digital_root.pem': 'aa800ef345422d6158c6fafe1c06c429dbda21c3df4bb1ccb45a920ec1111399', 'res/raw/ministry_of_digital_sub.pem': 'a97bea1a5b8f4fc386016b3e13d9eb63544cf4fd82985ad80741bdb5a08cb5c5', 'res/raw/ministry_of_digital_sub_2024.pem': '6f9d829c8e6712444fce3624658d8788672849c5d5b7b53fd9cf7e83eac4193e'}

def check_public_certificate(name, content):
    # AAPT2 shortens release resource paths (e.g. res/S4.pem). Trust exact
    # public certificate bytes, never a generated filename or any PEM text.
    assert re.fullmatch(r'res/(?:raw/)?[A-Za-z0-9_]+\.pem', name), name
    assert hashlib.sha256(content).hexdigest() in PUBLIC_SDK_CERTIFICATES.values(), ('Unexpected PEM file', name)
    assert b'BEGIN CERTIFICATE' in content and b'PRIVATE KEY' not in content


def main():
    badging = Path('apk-badging.txt').read_text()
    assert "package: name='ru.namaz.safadzhay.test'" in badging
    assert "versionCode='37'" in badging and "versionName='1.4-test1'" in badging
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for item in apk.infolist():
            name = item.filename
            path = Path(name)
            assert path.suffix.lower() not in {'.jks', '.keystore', '.p12', '.pfx', '.key'}
            assert path.name not in {'keystore.properties', 'signing.properties', 'local.properties', '.env'}
            assert not name.startswith(('app/src/test/', 'verification/', '.git/'))
            assert not (name.startswith(('assets/', 'res/raw/')) and re.search(r'(?:schedule|timetable|202[6-9])', name)), name
            if not item.is_dir():
                content = apk.read(item)
                if path.suffix.lower() == '.pem':
                    check_public_certificate(name, content)
                assert b'safadzhayData' not in content and b'moscowData' not in content
                assert not re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', content)
                assert not re.search(rb'gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}', content)
    print('PASS TEST APK identity, no embedded prayer tables/fixtures or private signing inputs')

if __name__ == '__main__':
    main()
