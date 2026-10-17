"""TEST readers stay local and production contains no diagnostic implementations."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REL = Path('java/ru/namaz/safadzhay')


class DiagnosticsIsolationTest(unittest.TestCase):
    def test_production_uses_noop_diagnostic_implementations(self):
        for name in ('TestDiagnosticsUi.kt', 'TestAlarmTrace.kt', 'TestGeoTrace.kt'):
            production = (ROOT / 'app/src/release' / REL / name).read_text()
            self.assertIn('= Unit', production)
            for forbidden in ('getSharedPreferences', 'requestLocation', 'FLAG_NO_CREATE',
                              'WeakHashMap', 'Executors', 'data class', 'Диагностика', 'Состояние списка'):
                self.assertNotIn(forbidden, production)
            self.assertTrue((ROOT / 'app/src/debug' / REL / name).is_file())
        ui = (ROOT / 'app/src/release' / REL / 'TestDiagnosticsUi.kt').read_text()
        self.assertIn('const val available = false', ui)

    def test_readers_have_no_alarm_or_provider_mutations(self):
        ui = (ROOT / 'app/src/debug' / REL / 'TestDiagnosticsUi.kt').read_text()
        for forbidden in ('PrayerAlarmScheduler.rebuild(', 'requestPermissions(',
                          'requestLocationUpdates(', 'getCurrentLocation(', 'setExact',
                          'setAndAllowWhileIdle', '.cancel(', 'FLAG_UPDATE_CURRENT', '.edit()'):
            self.assertNotIn(forbidden, ui)
        self.assertIn('PendingIntent.FLAG_NO_CREATE', ui)

    def test_geographic_trace_contains_no_coordinates_or_disk_history(self):
        trace = (ROOT / 'app/src/debug' / REL / 'TestGeoTrace.kt').read_text()
        for forbidden in ('latitude', 'longitude', 'getSharedPreferences', 'File(', 'Location('):
            self.assertNotIn(forbidden, trace)
        self.assertIn('WeakHashMap<CitySelectionSession, Fix>', trace)
