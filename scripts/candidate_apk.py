"""Identity and context guards for the explicitly approved private APK candidate."""
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REQUEST = ROOT / 'verification/candidate-apk-request.json'


def request():
    data = json.loads(REQUEST.read_text())
    expected = {
        'requestId': '2026-10-26-1.4-rc1-40', 'branch': 'candidate/stable-1.4',
        'applicationId': 'ru.namaz.safadzhay', 'versionName': '1.4-rc1', 'versionCode': 40,
        'minSdk': 23, 'targetSdk': 37, 'upgradeFromVersionCode': 33,
        'baseCommit': 'b1f0eeec54808b98c4da154da1a2724c5fc09127',
        'purpose': 'private-migration-test', 'publish': False,
    }
    if data != expected:
        raise ValueError('Candidate request differs from the approved build')
    return data


def expected_identity(candidate=False):
    if candidate:
        data = request()
        return data['applicationId'], data['versionCode'], data['versionName']
    return 'ru.namaz.safadzhay', 33, '1.3'


def check_identity(package, code, version, candidate=False):
    if (package, int(code), version) != expected_identity(candidate):
        raise ValueError('Unexpected APK identity or version')


def check_context(env):
    data = request()
    if (env.get('GITHUB_EVENT_NAME'), env.get('GITHUB_REF'), env.get('GITHUB_REPOSITORY')) != (
        'push', 'refs/heads/' + data['branch'], 'namaz-vakytylary/Namaz-Safadzhay-2026'
    ):
        raise ValueError('Candidate signing is restricted to the approved repository and branch push')
    sha = env.get('GITHUB_SHA', '')
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError('Missing exact source commit')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
    if head != sha:
        raise ValueError('Checkout does not match the requested source commit')
    subprocess.run(['git', 'merge-base', '--is-ancestor', data['baseCommit'], sha], cwd=ROOT, check=True)


if __name__ == '__main__':
    check_context(os.environ)
    print('PASS approved candidate identity, branch, source commit and private test purpose')
