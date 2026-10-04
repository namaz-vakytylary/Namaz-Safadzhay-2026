"""Prevent manual Ramadan previews and built-in Ramadan dates in stable inputs."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ProductionRamadanTests(unittest.TestCase):
    def test_all_runtime_source_sets_exclude_preview_code(self):
        markers = ['RamadanUiPreview', 'UiPreviewScene', 'TEST_RAMADAN',
                   'Тест UI', 'Проверка Рамадана', 'debug_ramadan_ui_scene']
        for source_set in ['main', 'debug', 'release']:
            for path in (ROOT / 'app/src' / source_set).rglob('*'):
                if path.suffix in {'.kt', '.java', '.xml'}:
                    text = path.read_text()
                    for marker in markers:
                        self.assertNotIn(marker, text, str(path))

    def test_ramadan_requires_remote_dates_without_builtin_records(self):
        source = (ROOT / 'app/src/main/java/ru/namaz/safadzhay/HolidayCalendar.kt').read_text()
        builtins = source.split('val items = listOf(', 1)[1].split(').sortedBy', 1)[0]
        self.assertNotIn('Начало Рамадана', builtins)
        self.assertNotIn('Ураза-байрам', builtins)
        self.assertNotIn('2026-02-19', source)
        self.assertNotIn('2026-03-20', source)
        ramadan = source.split('fun ramadanDay(', 1)[1].split('fun labels(', 1)[0]
        self.assertIn('remoteByYear[date.year] ?: return null', ramadan)
        self.assertNotIn('holidaysFor(', ramadan)
        self.assertNotIn('LocalDate.of(', ramadan)
        self.assertNotIn('LocalDate.parse(', ramadan)

    def test_stable_build_and_security_workflows_remain_protected(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = "ru.namaz.safadzhay"', build)
        self.assertIn('dependsOn(verifyReleaseSigning)', build)
        self.assertIn('isDebuggable = false', build)
        workflow = (ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('environment: stable-signing', workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn('python3 scripts/repository-security.py', workflow)
        self.assertIn('python3 scripts/audit-merged-manifest.py', workflow)
        self.assertIn('bash scripts/verify-apk.sh', workflow)
