# Security audit — 2026-10-01; cleanup update 2026-10-02

Scope: `namaz-vakytylary/Namaz-Safadzhay-2026` only. No changes to
`namaz-vakytylary/namaz-schedules`, organization-wide settings, signing certificate,
Android sources/resources, application identity, version, user data or publication.

Baseline stable main: `6a796349e05d7ae59dac22bb4ec6e291110fd5a1`.
The owner's stable build and phone installation already succeeded. No stable APK
build or publication was launched for this audit.

## Current status — 2026-10-02

The owner separately approved cleanup of the three ordinary branches. The historical
baseline below is retained as audit evidence, not a description of current exposure.
History cleanup and the prevention changes in PR #3 are complete; GitHub PR refs and
cached old objects still require Support cleanup. The Support request is prepared,
not submitted. The exposed private key remains potentially compromised.

Verified main after PR #3: `7604e6c34a0c0e3d6dd8587a8eb9b4ae5180d0c6`.
Repository checks and CodeQL completed successfully on that commit. The recorded
checks include 14 Python security tests, 77 Android unit tests and Android lint.
Application sources/resources and Gradle configuration match the original stable
1.3 baseline. The signing certificate and installed APK were not changed; no APK
build, Release or publishing was dispatched.

- [Repository checks](https://github.com/namaz-vakytylary/Namaz-Safadzhay-2026/actions/runs/36964969552)
- [CodeQL](https://github.com/namaz-vakytylary/Namaz-Safadzhay-2026/actions/runs/36964969551)
- [Prevention PR #3](https://github.com/namaz-vakytylary/Namaz-Safadzhay-2026/pull/3)

## Read-only baseline — before hardening

| Area | Observed state before hardening |
|---|---|
| Visibility | Public; GitHub reported zero forks |
| Access | One organization member/owner: emilzhianshin-dot; effective repository Admin; no other collaborators or teams; organization base permission Read |
| main | No branch protection or ruleset; direct push, force push and deletion not blocked |
| Deploy keys/runners | No deploy keys; deploy keys disabled by existing organization policy; no self-hosted runners |
| Webhooks/Pages | No repository webhooks; application repository Pages disabled |
| GitHub Apps | ChatGPT Codex Connector, with code/workflows/Actions/PR write access; installation covers all organization repositories |
| Owner authentication | Organization does not require 2FA; UI requires owner to enable account 2FA first; account sessions/recovery credentials were not audited |
| Actions | All Actions allowed; full SHA pinning not enforced; approval required only for first-time external contributors |
| Default GITHUB_TOKEN | Read contents/packages; workflow PR creation/approval disabled by existing organization policy |
| Secrets | Four repository signing secrets; no environments, environment secrets or accessible organization secrets |
| Security features | Dependency graph, Dependabot alerts/updates, secret scanning, push protection and CodeQL not configured/enabled |
| Tags/releases | No Git tags and no GitHub Releases at audit time |

The App installation and 2FA are organization-wide controls; changing them could
affect the excluded schedules repository. They were inspected, not changed.
Organization-level webhook configuration was not exposed in the repository view.

Secret names (values neither retrieved nor printed):
`NAMAZ_TEST_KEYSTORE_BASE64`, `NAMAZ_TEST_KEY_ALIAS`,
`NAMAZ_TEST_KEY_PASSWORD`, `NAMAZ_TEST_STORE_PASSWORD`.

## Safe hardening

- Active `main-history-protection` ruleset (`24312824`) targets exactly `refs/heads/main`,
  blocks deletion and non-fast-forward updates, and has an empty bypass list.
- Main changes use PRs with secret-free checks. No second-person approval requirement
  is used because the sole owner cannot approve their own PR. Work can create PRs
  and merge after checks. The owner retains the ability to edit rules for emergency
  recovery, without a standing force-push/delete bypass.
- GitHub dependency graph, Dependabot alerts/security updates, secret scanning,
  push protection and private vulnerability reporting are enabled for this repository.
- Actions require full SHA pins. The external allowlist is limited to checkout,
  setup-java, upload-artifact, and CodeQL init/analyze. GitHub inherently also permits
  same-organization Actions under this selected policy. All external contributors
  require workflow approval. Existing read-only token defaults are retained.
- `Repository checks` adds preflight, tracked credential/workflow guards, Python
  tests, Android unit tests and lint without any signing secrets or release packaging.
- CodeQL analyzes Java/Kotlin with traced source compilation, plus Python and Actions.
  Only its analysis job requests `security-events: write` to upload results.
- Additional signing formats and dotenv files are ignored. The small local security
  guard reports rule names and paths, never matching contents; it supplements, rather
  than replaces, GitHub scanning. It does not claim exhaustive detection of secrets.
- The stable manual build retains its sequence, certificate guard and cleanup;
  security additions are the repository guard before preflight and full-history checkout.

The final conversational report records actual check outcomes, final rules and main
SHA. Enabling a scanner does not itself mean it has finished or found no issues.

## Current signing and released APK verification

Current main has no tracked `.jks`/`.keystore`, literal Gradle signing passwords,
private-key markers or recognized credential token patterns in the inspected tree.
Signing inputs come from environment variables; missing inputs block release
packaging. `restore-signing.py` uses exclusive creation, 0600 key/0700 directory,
bounded input, generic errors and no secret output. Cleanup runs even on failure.

Successful existing stable run: Actions `36867037783` at baseline main.
Its recorded Android unit results are 77 passing tests and lint/build/verification
succeeded. The downloaded actual stable APK was independently verified again:

- Package/applicationId: `ru.namaz.safadzhay`; versionName `1.3`; versionCode `33`.
- Certificate SHA-256: `b7a6e8c97ee7ea53c18d5d89082628caa594428b18fb919bd4f3dd9ed7044e62`.
- APK SHA-256: `55bd226d876e920a0e0cdd25350ea7901a05abab5b7fa50e274ae02d30a7ad78`.
- One signer, valid v1/v2 signing; same stable identity/certificate; APK content and
  permissions checks passed. Owner confirmed installation and operation on phone.
- No APK rebuild, certificate replacement, release creation or publishing in this audit.

Repository signing secrets remain usable by repository workflows, not restricted
to one workflow by GitHub authorization. See SECURITY.md for owner-run Environment
migration. GitHub cannot return their values, and this audit never requested them.

## Historical signing exposure — original baseline

First key addition: `7aab359efc2c902c4dfb20e107b9d815f26818b7` (2026-09-18).
Key removal from security lineage: `ed9a42444e236c7ec1578271e3884741a22ba0df`
(2026-09-22). The same keystore blob appears in all exposed histories.

| Remaining branch | Baseline head | Commits inspected | Snapshots containing keystore | Snapshots containing literal signing passwords |
|---|---|---:|---:|---:|
| main | 6a796349e05d7ae59dac22bb4ec6e291110fd5a1 | 468 | 60 | 54 |
| test/1.2-test3-security | 64c41c270d776d660043cd3bc64a41a636e7e973 | 464 | 57 | 51 |
| backup/main-before-stable-1.3-20261001 | cad84c8df708ffff9f894d366e14232ab129801f | 123 | 12 | 12 |

At the original baseline, main and security source had clean current trees but
retained the key through ancestors; backup contained it and literal passwords in
its current tree. All three branch histories have since been sanitized with owner
approval, including the retained backup. Full per-branch SHA
lists are in [security-history-findings.json](security-history-findings.json), containing
only metadata, no secret values. Counts overlap between branches and must not be summed.

Historical workflows: 24 unique versions inspected; 18 contain floating Action refs
and one explicit write permission. Old files are not automatically run just because
they exist in history. Manual runs at old refs and old clones remain a risk; full-SHA
policy now blocks floating Actions, but does not remove historical material.
The heuristic tree/history scans and GitHub patterns cannot prove absence of every
possible password, encoded secret, old log disclosure or externally retained copy.

## Authorized history cleanup — completed 2026-10-02

An isolated rewrite with git-filter-repo 2.47.0 removed the standalone JKS blob,
redacted literal signing passwords and sanitized 22 historical ZIP versions. The
recorded verification covered 470 original commits, preserving authors, dates and
parent relationships. Git commit signatures were removed by rewriting; this does
not change the Android signing certificate. No credentials are included in this report.

Main and the test branch retained byte-identical current trees at the cleanup point.
The sanitized backup differs only by removal of the JKS and replacement of literal
signing passwords with external variables. A future rollback must retain the current
safe signing scheme. Original recovery material must not be re-published.

| Branch | Sanitized head at cleanup / prevention checkpoint |
|---|---|
| main immediately after cleanup | 088c73df958fa946cdd5c09be77bf3e9149a37f0 |
| main after PR #3 | 7604e6c34a0c0e3d6dd8587a8eb9b4ae5180d0c6 |
| test/1.2-test3-security | a7d46f43596b6b776151e9d9fdf64cc4e8a69a44 |
| backup/main-before-stable-1.3-20261001 | 212e68680e77f5ebb55dbee2dbbe59ebddc709f4 |

The one-time import workflow, uploaded bundle and temporary permission exception
are absent from the resulting main. The active main ruleset was restored with the
same rules, main-only target and empty bypass list. Temporary maintenance branches
were removed. No blind mirror push, signing-secret migration or key rotation occurred.

## Prevention and remaining cleanup

PR #3 inspects tracked archives in memory, including nested/renamed ZIPs, credential
filenames, literal signing passwords and JKS magic. Limits are 32 MiB expanded data,
2000 entries and three levels; encrypted, malformed and excessive archives fail
closed. Reports do not print file contents or internal archive entry names.

The guard rejects the 23 known purged credential objects when reachable from HEAD.
Repository checks and stable manual builds use `fetch-depth: 0`; shallow history
fails the guard. The known-object list contains identifiers, not secret values.
This is targeted prevention, not exhaustive detection of every possible credential.

The read-only `refs/pull/1/head` and `refs/pull/2/head` still refer to the pre-cleanup
history. GitHub Support must assess dereferencing the affected PRs, server garbage
collection and cached-view removal. The prepared request includes the affected PR
count and first changed commit; it has not been submitted. Ordinary branch cleanup
cannot erase external clones or revoke an already obtained private key.

Use fresh clones. Review and transfer unpublished work onto sanitized history without
merging old ancestry. Any future signing migration needs separate owner approval,
Android/distribution compatibility checks and update/data-preservation validation.
No replacement certificate is generated automatically.

## Branch decision

Retain `main`, `test/1.2-test3-security` as the verified source and sanitized
`backup/main-before-stable-1.3-20261001` as the authorized rollback point. The
original baseline table and JSON findings are historical records, not live branch
inventories. Temporary history-cleanup and merged prevention branches were removed;
no repository or organization was deleted. Any new documentation PR branch is
maintenance work based on sanitized main.

## Owner follow-up

Enable account 2FA/passkeys and keep recovery codes privately; inspect sessions and
organization-wide hooks. Consider a separately authorized selected-repository scope
for Codex Connector without disrupting the schedules repository. Migrate signing
inputs into a main-only Environment without deleting working secrets before a
verified migration. Submit the prepared GitHub Support cleanup request. Assess a
compatible signing key migration separately after reviewing its consequences. Do not assume scanners
detect a JKS binary or revoke its private key.

References:
- https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository
- https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions
- https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages
- https://developer.android.com/studio/publish/app-signing
