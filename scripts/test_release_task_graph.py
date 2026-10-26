import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('release_graph', Path(__file__).with_name('audit-release-task-graph.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseTaskGraphTest(unittest.TestCase):
    names = ['verifyReleaseSigning', 'testDebugUnitTest', 'testReleaseUnitTest',
             'lintDebug', 'lintRelease', 'verifyReleaseQuality', 'packageRelease']

    def graph(self, names):
        return '\n'.join(':app:' + name + ' SKIPPED' for name in names)

    def test_complete_quality_and_signing_gates_are_accepted(self):
        module.audit(self.graph(self.names))

    def test_missing_either_test_variant_or_other_gate_is_rejected(self):
        for missing in self.names:
            with self.subTest(missing=missing), self.assertRaises(ValueError):
                module.audit(self.graph([name for name in self.names if name != missing]))

    def test_signing_after_packaging_is_rejected(self):
        with self.assertRaises(ValueError):
            module.audit(self.graph(self.names[1:] + self.names[:1]))

    def test_quality_gate_before_its_checks_is_rejected(self):
        with self.assertRaises(ValueError):
            module.audit(self.graph(['verifyReleaseQuality'] + [name for name in self.names if name != 'verifyReleaseQuality']))
