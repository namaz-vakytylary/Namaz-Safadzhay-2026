"""Regression checks for secret restoration; fixtures are deliberately not real keys."""
import base64
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


class SigningRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "private" / "release.jks"
        self.environment = {
            "NAMAZ_KEYSTORE_BASE64": base64.b64encode(b"synthetic-test-key").decode(),
            "NAMAZ_KEYSTORE_PATH": str(self.destination),
            "NAMAZ_STORE_PASSWORD": "synthetic-private-password",
            "NAMAZ_KEY_PASSWORD": "synthetic-private-password",
            "NAMAZ_KEY_ALIAS": "synthetic-alias",
        }

    def run_restore(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("restore-signing.py"))],
                                env=self.environment, capture_output=True, text=True)
        for key, value in self.environment.items():
            if key != "NAMAZ_KEYSTORE_PATH":
                self.assertNotIn(value, result.stdout + result.stderr)
        return result

    def test_restores_exact_bytes_with_private_permissions(self):
        self.assertEqual(0, self.run_restore().returncode)
        self.assertEqual(b"synthetic-test-key", self.destination.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(self.destination.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(self.destination.parent.stat().st_mode))

    def test_missing_secret_and_invalid_payload_leave_no_file(self):
        cases = [("NAMAZ_STORE_PASSWORD", ""), ("NAMAZ_KEYSTORE_BASE64", "invalid$base64"),
                 ("NAMAZ_KEYSTORE_BASE64", "A" * 65537)]
        for name, value in cases:
            with self.subTest(name=name, length=len(value)):
                old = self.environment[name]
                self.environment[name] = value
                # Empty values are not checked for logging (empty text matches everything).
                result = subprocess.run([sys.executable, str(Path(__file__).with_name("restore-signing.py"))],
                                        env=self.environment, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("synthetic-private-password", result.stdout + result.stderr)
                self.assertFalse(self.destination.exists())
                self.environment[name] = old

    def test_existing_file_is_not_overwritten(self):
        self.destination.parent.mkdir()
        self.destination.write_bytes(b"keep-existing-key")
        self.assertNotEqual(0, self.run_restore().returncode)
        self.assertEqual(b"keep-existing-key", self.destination.read_bytes())

    def test_symlink_target_is_not_overwritten(self):
        target = Path(self.temporary.name) / "original"
        target.write_bytes(b"keep-original-key")
        self.destination.parent.mkdir()
        self.destination.symlink_to(target)
        self.assertNotEqual(0, self.run_restore().returncode)
        self.assertEqual(b"keep-original-key", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
