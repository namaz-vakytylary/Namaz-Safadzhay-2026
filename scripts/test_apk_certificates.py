import hashlib
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("apk_contents", Path(__file__).with_name("check-apk-contents.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PublicSdkCertificatesTest(unittest.TestCase):
    def setUp(self):
        self.content = b"-----BEGIN CERTIFICATE-----\nsynthetic unit test\n-----END CERTIFICATE-----"
        self.allow = patch.object(checker, "PUBLIC_SDK_CERTIFICATES", {"original.pem": hashlib.sha256(self.content).hexdigest()})
        self.allow.start()
        self.addCleanup(self.allow.stop)

    def test_original_and_aapt_shortened_paths_accept_same_exact_bytes(self):
        for name in ("res/raw/ministry_of_digital_root.pem", "res/S4.pem"):
            checker.check_public_certificate(name, self.content)

    def test_changed_certificate_is_rejected(self):
        with self.assertRaises(AssertionError):
            checker.check_public_certificate("res/S4.pem", self.content + b" ")

    def test_assets_and_path_traversal_are_rejected(self):
        for name in ("assets/S4.pem", "res/../S4.pem"):
            with self.assertRaises(AssertionError):
                checker.check_public_certificate(name, self.content)

    def test_private_material_is_rejected_even_if_digest_is_allowlisted(self):
        content = self.content + b"PRIVATE KEY"
        with patch.object(checker, "PUBLIC_SDK_CERTIFICATES", {"x": hashlib.sha256(content).hexdigest()}):
            with self.assertRaises(AssertionError):
                checker.check_public_certificate("res/S4.pem", content)
