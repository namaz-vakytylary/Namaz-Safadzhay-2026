"""Production content rules, independent of signing credentials and APK tooling."""
import json
import re


def check_runtime_entry(name, data):
    assert not (name.startswith(('assets/', 'res/raw/')) and
                re.search(r'(?:schedule|timetable|202[6-9])', name, re.I)), 'Packaged schedule fixture'
    for marker in ('safadzhayData', 'moscowData', 'TestDiagnosticsUi', 'TestAlarmTrace',
                   'TestGeoTrace', 'RamadanUiPreview', 'UiPreviewScene',
                   'AppUpdateController', 'RuStoreAppUpdateSource', 'Диагностика · TEST'):
        for encoding in ('utf-8', 'utf-16le'):
            assert marker.encode(encoding) not in data, 'Packaged TEST, timetable or paused updater implementation'
    if name.startswith(('assets/', 'res/')) and data.lstrip().startswith(b'{'):
        try:
            root = json.loads(data)
        except (ValueError, UnicodeError):
            return
        # Catch renamed payloads too, rather than trusting a resource filename.
        if isinstance(root, dict) and isinstance(root.get('cities'), list):
            for city in root['cities']:
                if isinstance(city, dict) and isinstance(city.get('days'), list):
                    assert not any(isinstance(day, dict) and
                                   {'fajr', 'zuhr', 'asr', 'maghrib', 'isha'} <= day.keys()
                                   for day in city['days']), 'Packaged prayer times'
