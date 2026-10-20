import json
import unittest
from apk_policy import check_runtime_entry


class ProductionApkPolicyTest(unittest.TestCase):
    def test_production_images_and_unrelated_json_are_allowed(self):
        check_runtime_entry('res/drawable/ramadan_iftar_started.webp', b'RIFF public image bytes')
        check_runtime_entry('assets/settings.json', b'{"theme":"system"}')

    def test_debug_implementations_and_paused_updater_are_rejected_in_dex_or_resources(self):
        for marker in ('TestDiagnosticsUi', 'RamadanUiPreview', 'RuStoreAppUpdateSource'):
            for encoding in ('utf-8', 'utf-16le'):
                with self.subTest(marker=marker, encoding=encoding), self.assertRaises(AssertionError):
                    check_runtime_entry('classes.dex', marker.encode(encoding))

    def test_renaming_a_packaged_schedule_does_not_bypass_content_validation(self):
        data = json.dumps({'cities': [{'days': [{key: 'unit-test' for key in
                          ('fajr', 'zuhr', 'asr', 'maghrib', 'isha')}]}]}).encode()
        with self.assertRaises(AssertionError):
            check_runtime_entry('res/raw/innocent.json', data)

    def test_named_fixture_is_rejected_even_if_its_contents_are_obfuscated(self):
        with self.assertRaises(AssertionError):
            check_runtime_entry('assets/2026.json', b'obfuscated')
