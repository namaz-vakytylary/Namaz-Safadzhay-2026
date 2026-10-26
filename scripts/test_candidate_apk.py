import unittest
from unittest.mock import patch
import candidate_apk


class CandidateIdentityTest(unittest.TestCase):
    def test_candidate_and_stable_are_distinct_modes_with_same_application_id(self):
        candidate_apk.check_identity('ru.namaz.safadzhay', 40, '1.4-rc1', candidate=True)
        candidate_apk.check_identity('ru.namaz.safadzhay', 33, '1.3')
        with self.assertRaises(ValueError):
            candidate_apk.check_identity('ru.namaz.safadzhay', 40, '1.4-rc1')
        with self.assertRaises(ValueError):
            candidate_apk.check_identity('ru.namaz.safadzhay', 33, '1.3', candidate=True)

    def test_test_package_and_wrong_version_are_rejected(self):
        for values in [('ru.namaz.safadzhay.test', 40, '1.4-rc1'),
                       ('ru.namaz.safadzhay', 39, '1.4-rc1'),
                       ('ru.namaz.safadzhay', 40, '1.4')]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                candidate_apk.check_identity(*values, candidate=True)

    def test_only_approved_branch_push_and_repository_can_sign(self):
        env = dict(GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/candidate/stable-1.4',
                   GITHUB_REPOSITORY='namaz-vakytylary/Namaz-Safadzhay-2026', GITHUB_SHA='a' * 40)
        with patch.object(candidate_apk.subprocess, 'check_output', return_value=b'a' * 40), \
                patch.object(candidate_apk.subprocess, 'run') as ancestor:
            candidate_apk.check_context(env)
            ancestor.assert_called_once()
            for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/main'),
                               ('GITHUB_REPOSITORY', 'example/fork'), ('GITHUB_SHA', 'invalid')]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    candidate_apk.check_context(dict(env, **{key: value}))

    def test_checkout_mismatch_is_rejected(self):
        env = dict(GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/candidate/stable-1.4',
                   GITHUB_REPOSITORY='namaz-vakytylary/Namaz-Safadzhay-2026', GITHUB_SHA='a' * 40)
        with patch.object(candidate_apk.subprocess, 'check_output', return_value=b'b' * 40), self.assertRaises(ValueError):
            candidate_apk.check_context(env)
