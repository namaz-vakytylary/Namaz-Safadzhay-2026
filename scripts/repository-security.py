#!/usr/bin/env python3
"""Small, secret-safe guard for tracked files and repository workflow invariants.

This supplements GitHub secret scanning; it is not a comprehensive secret detector.
Only filenames and rule names are reported, never matching file contents.
"""
from pathlib import Path
import io
import json
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_BYTE_LIMIT = 32 * 1024 * 1024
ARCHIVE_ENTRY_LIMIT = 2000


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


def tracked_data_errors(path, data, depth=0, budget=None):
    """Inspect archives in memory with bounded expansion; never echo their data."""
    errors = file_errors(path, data.decode('utf-8', errors='ignore'))
    if data.startswith(bytes.fromhex('feedfeed')):
        errors.append('JKS binary detected')
    if not (path.lower().endswith('.zip') or data.startswith(b'PK\x03\x04')):
        return errors
    if depth >= 3:
        return errors + ['archive nesting limit']
    if budget is None:
        budget = [ARCHIVE_BYTE_LIMIT, ARCHIVE_ENTRY_LIMIT]
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                budget[1] -= 1
                budget[0] -= entry.file_size
                if budget[0] < 0 or budget[1] < 0:
                    return errors + ['archive expansion limit']
                if entry.flag_bits & 1:
                    errors.append('encrypted archive cannot be inspected')
                    continue
                member = archive.read(entry)
                # Report only the finding, not untrusted archive entry names.
                errors.extend(tracked_data_errors(entry.filename, member, depth + 1, budget))
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, OSError, ValueError):
        errors.append('archive cannot be inspected')
    return sorted(set(errors))


def history_errors(root, purged_object_ids):
    if subprocess.check_output(['git', 'rev-parse', '--is-shallow-repository'], cwd=root).strip() == b'true':
        return ['full Git history required; checkout with fetch-depth: 0']
    reachable = subprocess.check_output(['git', 'rev-list', '--objects', 'HEAD'], cwd=root)
    object_ids = {line.split(b' ', 1)[0].decode() for line in reachable.splitlines()}
    if object_ids & set(purged_object_ids):
        return ['purged credential object reachable through Git history']
    return []


def workflow_errors(path, content):
    errors = []
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
        errors = tracked_data_errors(path, data)
        if path.startswith('.github/workflows/') and path.endswith(('.yml', '.yaml')):
            errors += workflow_errors(path, content)
        for error in errors:
            print(f'FAIL {path}: {error}', file=sys.stderr)
            failed = True
    known_ids = json.loads((ROOT / 'scripts/purged-credential-object-ids.json').read_text())['blob_ids']
    if not known_ids or any(not re.fullmatch(r'[0-9a-f]{40}', sha) for sha in known_ids):
        print('FAIL invalid purged credential object identifiers', file=sys.stderr)
        return 1
    for error in history_errors(ROOT, known_ids):
        print(f'FAIL history: {error}', file=sys.stderr)
        failed = True
    if failed:
        return 1
    print('PASS tracked files/archives, purged history guards, pinned actions, minimal token permissions and secret-free PR workflows')
    return 0


if __name__ == '__main__':
    sys.exit(main())
