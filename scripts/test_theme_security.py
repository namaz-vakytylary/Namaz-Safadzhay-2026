"""Ensure the artificial Ramadan date is excluded from release source inputs."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RamadanIsolationTest(unittest.TestCase):
    def test_fixture_exists_only_in_debug_source_set(self):
        relative = 'java/ru/namaz/safadzhay/RamadanUiPreview.kt'
        debug = (ROOT / 'app/src/debug' / relative).read_text()
        release = (ROOT / 'app/src/release' / relative).read_text()
        self.assertIn('TEST_RAMADAN_START_DATE = "2026-08-10"', debug)
        self.assertIn('const val available = false', release)
        self.assertIn('LocalDateTime = realNow', release)
        self.assertIn('List<UiPreviewScene> = emptyList()', release)
        self.assertNotIn('2026-08-10', release)
        self.assertNotIn('getSharedPreferences', release)
        for source in (ROOT / 'app/src/main').rglob('*'):
            if source.suffix in ('.kt', '.xml'):
                self.assertNotIn('TEST_RAMADAN_START_DATE', source.read_text())

    def test_calendar_and_alarm_code_do_not_use_preview(self):
        src = ROOT / 'app/src/main/java/ru/namaz/safadzhay'
        for name in ['HolidayCalendar.kt', 'HolidayRepository.kt', 'ScheduleRepository.kt', 'ScheduleUpdateChecker.kt']:
            self.assertNotIn('RamadanUiPreview', (src / name).read_text())
        main = (src / 'MainActivity.kt').read_text()
        alarms = main.split('private fun schedulePrayerNotifications()', 1)[1].split('private fun currentData()', 1)[0]
        receivers = main.split('class PrayerNotificationReceiver', 1)[1]
        for code in [alarms, receivers]:
            self.assertNotIn('RamadanUiPreview', code)
            self.assertNotIn('uiNow()', code)

    def test_ci_checks_both_build_types_and_preserves_release_signing_gate(self):
        workflow = (ROOT / '.github/workflows/build-apk.yml').read_text()
        for gate in [':app:testDebugUnitTest', ':app:testReleaseUnitTest', ':app:lintDebug', ':app:lintRelease',
                     'python3 scripts/preflight.py', 'scripts/verify-apk.sh']:
            self.assertIn(gate, workflow)
        self.assertIn("github.ref == 'refs/heads/test/1.2-test3-security'", workflow)
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('dependsOn(verifyReleaseSigning)', build)
        self.assertIn('isDebuggable = false', build)
