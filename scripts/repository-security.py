#!/usr/bin/env python3
"""Small, secret-safe guard for tracked files and repository workflow invariants.

This supplements GitHub secret scanning; it is not a comprehensive secret detector.
Only filenames and rule names are reported, never matching file contents.
"""
from pathlib import Path
import hashlib
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def file_errors(path, content):
    errors = []
    name = Path(path).name.lower()
    if name.endswith(('.jks', '.keystore', '.p12', '.pfx', '.key')) or name in {
        '.env', 'keystore.properties', 'signing.properties',
    } or name.startswith('.env.'):
        errors.append('credential file tracked')
    if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----', content):
        errors.append('private key marker')
    if re.search(r'\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}|AKIA[A-Z0-9]{16})\b', content):
        errors.append('credential token pattern')
    if path.endswith(('.gradle', '.gradle.kts')) and re.search(
        r'\b(?:storePassword|keyPassword)\s*(?:=\s*|\(\s*)[\"\']', content
    ):
        errors.append('literal signing password')
    return errors


def workflow_errors(path, content):
    errors = []
    authorized_cleanup = (path == '.github/workflows/history-cleanup.yml'
                          and hashlib.sha256(content.encode()).hexdigest() == 'a0fce3f5b1be78a91e89ad5be9c4233a21686a16fdb68e3bd5c773c6f4e6cb69')
    for action in re.findall(r'^\s*(?:-\s*)?uses:\s*([^\s#]+)', content, re.M):
        if not re.fullmatch(r'(?:actions/(?:checkout|setup-java|upload-artifact)|github/codeql-action/(?:init|analyze))@[0-9a-f]{40}', action):
            errors.append('unapproved or unpinned action')
    if 'persist-credentials: false' not in content:
        errors.append('checkout credentials retained')
    if not re.search(r'^permissions:\n  contents: read\n', content, re.M):
        errors.append('missing read-only default token')
    if re.search(r'^\s*pull_request_target\s*:', content, re.M):
        errors.append('privileged pull request trigger')
    if re.search(r'^\s*(?:contents|actions|packages|pull-requests|issues|id-token):\s*write\s*$', content, re.M):
        if not authorized_cleanup:
            errors.append('unnecessary token write permission')
    if re.search(r'\bsecrets\s*(?:\.|\[)', content) and path != '.github/workflows/build-apk.yml':
        errors.append('signing secrets outside stable workflow')
    if 'security-events: write' in content and path != '.github/workflows/codeql.yml':
        errors.append('code scanning permission outside CodeQL')
    return errors


def main():
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    failed = False
    for path in filter(None, paths):
        data = (ROOT / path).read_bytes()
        content = data.decode('utf-8', errors='ignore')
        errors = file_errors(path, content)
        if path.startswith('.github/workflows/') and path.endswith(('.yml', '.yaml')):
            errors += workflow_errors(path, content)
        for error in errors:
            print(f'FAIL {path}: {error}', file=sys.stderr)
            failed = True
    if failed:
        return 1
    print('PASS tracked credential guards, pinned actions, minimal token permissions and secret-free PR workflows')
    return 0


if __name__ == '__main__':
    sys.exit(main())
