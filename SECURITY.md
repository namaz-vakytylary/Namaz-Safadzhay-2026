# Security policy

Supported stable version: **Намаз Вакытлары 1.3** (`ru.namaz.safadzhay`, versionCode 33).

Use this repository's **Security → Report a vulnerability** form for confidential
reports. Do not put keys, passwords, tokens or keystore files in issues or pull requests.

## Repository controls

Changes to `main` use a pull request and the secret-free `Repository checks` job.
The owner can still create and merge passing PRs, including through ChatGPT Work;
an approving review from a second person is not required in this single-owner repository.
Deletion and force pushes are blocked without permanent actor bypasses. An organization
owner may edit the repository rule only for a separately authorized emergency recovery.
Do not disable protection as a routine workaround for failed checks.

All external fork workflows require approval. Approved Actions are pinned to full
commit SHAs, checkout does not persist credentials, and default token access is read-only.
CodeQL alone requests `security-events: write` to upload analysis; it cannot write code.
PR checks and CodeQL use no signing secrets and do not package a release APK.
Dependency/security updates create reviewable PRs, not automatic releases or merges.

## Stable signing

`build-apk.yml` remains a manual `workflow_dispatch` on `main`. It runs preflight,
unit tests and lint before signing, restores the existing keystore in a temporary
directory, removes it with `always()`, and verifies the APK's stable certificate.
There is no unsigned/debug fallback and no automatic publishing.

The existing four `NAMAZ_TEST_*` secret **names** are retained for compatibility;
they hold the existing stable signing inputs, not a new certificate. Their values
must never be logged, downloaded for audit, or committed.

These are currently **repository secrets**: the stable workflow passes them only
to its signing step, but a person able to change repository workflows can reference
them elsewhere. Step-level injection is not an authorization boundary. For stronger
isolation, the owner should separately migrate the same values into an Environment
limited to `main` and optionally require owner approval. Update the stable signing
job to use that Environment, verify it manually, then remove duplicate repository
secrets. Do not delete working secrets before migration or change the signing key.

## Known signing incident

The old `app/namaz-release.jks` and literal signing passwords remain in Git history.
Treat the old production private key as potentially compromised. Keeping the same
key in Secrets, changing its password, deleting branches, or rewriting history
does not invalidate copies someone already obtained.

See [the audit and proposed remediation](docs/security-audit-20261001.md).
History rewriting and signing certificate rotation require separate explicit owner
approval. They must preserve the ability to update existing installations and data.
The schedules repository is outside the scope of these repository controls.
