import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('security', Path(__file__).with_name('repository-security.py'))
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)


class RepositorySecurityTests(unittest.TestCase):
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
