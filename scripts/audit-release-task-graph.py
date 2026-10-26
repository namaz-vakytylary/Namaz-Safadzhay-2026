#!/usr/bin/env python3
"""Verify release gates in Gradle's dry-run output, without signing or packaging."""
from pathlib import Path
import re
import sys


def audit(text):
    tasks = re.findall(r'^(:app:\w+) SKIPPED\s*$', text, re.M)
    tests_and_lint = ['testDebugUnitTest', 'testReleaseUnitTest', 'lintDebug', 'lintRelease']
    required = tests_and_lint + ['verifyReleaseQuality', 'verifyReleaseSigning', 'packageRelease']
    for name in required:
        if ':app:' + name not in tasks:
            raise ValueError('Missing mandatory release task: ' + name)
    positions = {name: tasks.index(':app:' + name) for name in required}
    if any(positions[name] >= positions['verifyReleaseQuality'] for name in tests_and_lint):
        raise ValueError('Quality gate must follow both variants of tests and lint')
    if any(positions[name] >= positions['packageRelease'] for name in required[:-1]):
        raise ValueError('Mandatory gates must precede release packaging')


if __name__ == '__main__':
    audit(Path(sys.argv[1]).read_text())
    print('PASS release dry-run: signing, Debug/Release tests and lint precede packaging; no APK built')
