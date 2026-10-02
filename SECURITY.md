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

`build-apk.yml` remains a manual `workflow_dispatch` on `main`. Its build job uses
the `stable-signing` Environment, restricted to `main`, with owner approval required
and administrator bypass disabled. It runs preflight,
unit tests and lint before signing, restores the existing keystore in a temporary
directory, removes it with `always()`, and verifies the APK's stable certificate.
There is no unsigned/debug fallback and no automatic publishing.

The existing four `NAMAZ_TEST_*` secret **names** are retained for compatibility;
they hold the existing stable signing inputs, not a new certificate. Their values
must never be logged, downloaded for audit, or committed.

These are currently **repository secrets**: the stable workflow passes them only
to its signing step, but a person able to change repository workflows can reference
them elsewhere. Step-level injection is not an authorization boundary. For stronger
isolation, the owner must migrate the same values into `stable-signing`, verify
the Environment-scoped signing inputs, then remove duplicate repository secrets.
The Environment is currently empty; the approval gate protects the current stable
job but does not isolate repository secrets from other authorized workflows.
Do not delete working secrets before verified migration or change the signing key.
See [the migration procedure](docs/signing-security-migration.md).

## Known signing incident — status as of 2026-10-02

With explicit owner approval, the production keystore and literal signing passwords
were removed from the history of `main`, `test/1.2-test3-security` and
`backup/main-before-stable-1.3-20261001`. The cleanup also sanitized 22 historical
ZIP versions. Current application sources, identity and APK certificate were preserved.
The main protection rules were immediately restored after the authorized rewrite.

PR #3 adds bounded scanning of tracked ZIPs, including nested/renamed archives,
and rejects known purged credential objects reachable from HEAD. CI and manual
stable builds use full Git history; these guards supplement GitHub scanning and
do not prove that every possible secret or externally retained copy is absent.

Cleanup of ordinary branch history is complete. GitHub's read-only refs for PR #1
and PR #2 still retain the old history; server-side object/cache removal requires
GitHub Support. Request #4815381 has been submitted and remains open without a
Support response at the latest check. Do not describe
the incident as fully erased or the exposed key as safe.

Treat the production private key as potentially compromised. Keeping the same
key in Secrets, changing its password, deleting branches, or rewriting history
does not invalidate copies someone already obtained.

See [the audit and cleanup status](docs/security-audit-20261001.md).
Any additional history rewriting or signing certificate rotation requires separate
explicit owner approval. They must preserve the ability to update existing installations and data.
The schedules repository is outside the scope of these repository controls.

Use a fresh clone of the sanitized repository. Review and transfer unpublished changes
without merging old history. Preserve the safe signing configuration when using the
sanitized backup as a rollback point. No APK release or publishing was launched by
this incident cleanup.
