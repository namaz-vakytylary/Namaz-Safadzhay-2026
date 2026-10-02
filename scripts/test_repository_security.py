import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('security', Path(__file__).with_name('repository-security.py'))
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)


class RepositorySecurityTests(unittest.TestCase):
    @staticmethod
    def archive(entries):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return output.getvalue()

    def test_archived_key_and_literal_signing_password_rejected(self):
        data = self.archive({'app/release.jks': b'fixture', 'app/build.gradle.kts': b'storePassword = "fixture"'})
        errors = security.tracked_data_errors('app.zip', data)
        self.assertIn('credential file tracked', errors)
        self.assertIn('literal signing password', errors)

    def test_nested_and_renamed_archives_inspected(self):
        inner = self.archive({'hidden.bin': bytes.fromhex('feedfeed') + b'fixture'})
        outer = self.archive({'nested.data': inner})
        self.assertIn('JKS binary detected', security.tracked_data_errors('source.data', outer))

    def test_safe_archive_with_public_certificate_accepted(self):
        data = self.archive({'app/data.json': b'{"year":2026}', 'certificate.txt': b'public certificate SHA-256'})
        self.assertEqual([], security.tracked_data_errors('source.zip', data))

    def test_malformed_or_excessive_archive_rejected(self):
        self.assertIn('archive cannot be inspected', security.tracked_data_errors('source.zip', b'broken'))
        data = self.archive({'large.txt': b'fixture' * 50})
        with patch.object(security, 'ARCHIVE_BYTE_LIMIT', 32):
            self.assertIn('archive expansion limit', security.tracked_data_errors('source.zip', data))

    def test_archive_nesting_is_bounded(self):
        data = self.archive({'safe.txt': b'fixture'})
        for _ in range(3):
            data = self.archive({'inner.zip': data})
        self.assertIn('archive nesting limit', security.tracked_data_errors('source.zip', data))

    def test_removed_credential_is_still_detected_in_ancestry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', *args], cwd=root, stderr=subprocess.DEVNULL)
            git('init', '--initial-branch=main')
            fixture = root / 'fixture.txt'
            fixture.write_text('historical credential fixture')
            blob = git('hash-object', 'fixture.txt').decode().strip()
            git('add', 'fixture.txt'); git('commit', '-m', 'Historical fixture')
            git('rm', 'fixture.txt'); git('commit', '-m', 'Remove fixture')
            self.assertIn('purged credential object reachable through Git history', security.history_errors(root, [blob]))
            git('checkout', '--orphan', 'clean')
            fixture.write_text('clean fixture'); git('add', 'fixture.txt'); git('commit', '-m', 'Clean root')
            self.assertEqual([], security.history_errors(root, [blob]))

    def test_credential_files_and_private_keys_rejected_without_values(self):
        for name in ['app/release.jks', 'release.keystore', 'key.p12', '.env.production']:
            self.assertIn('credential file tracked', security.file_errors(name, ''))
        marker = '-----BEGIN ' + 'PRIVATE KEY-----'
        self.assertEqual(['private key marker'], security.file_errors('notes.txt', marker))

    def test_literal_password_rejected_environment_allowed(self):
        self.assertEqual(['literal signing password'], security.file_errors('app/build.gradle.kts', 'storePassword = "fixture"'))
        self.assertEqual([], security.file_errors('app/build.gradle.kts', 'storePassword = providers.environmentVariable("SIGNING_PASSWORD").orNull'))

    def test_privileged_trigger_floating_action_and_secret_rejected(self):
        workflow = 'permissions:\n  contents: read\n' + 'on:\n  pull_request_target:\nsteps:\n  - uses: actions/checkout@v5\n    with:\n      persist-credentials: false\n  - run: echo ${{ secrets.SIGNING_KEY }}\n'
        errors = security.workflow_errors('.github/workflows/checks.yml', workflow)
        self.assertIn('privileged pull request trigger', errors)
        self.assertIn('unapproved or unpinned action', errors)
        self.assertIn('signing secrets outside stable workflow', errors)

    def test_writable_token_and_persisted_credentials_rejected(self):
        errors = security.workflow_errors('.github/workflows/checks.yml', 'permissions:\n  contents: write\n')
        self.assertIn('unnecessary token write permission', errors)
        self.assertIn('checkout credentials retained', errors)


if __name__ == '__main__':
    unittest.main()
