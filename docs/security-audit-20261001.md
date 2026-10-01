# Security audit — 2026-10-01

Scope: `namaz-vakytylary/Namaz-Safadzhay-2026` only. No changes to
`namaz-vakytylary/namaz-schedules`, organization-wide settings, signing certificate,
Android sources/resources, application identity, version, user data or publication.

Baseline stable main: `6a796349e05d7ae59dac22bb4ec6e291110fd5a1`.
The owner's stable build and phone installation already succeeded. No stable APK
build or publication was launched for this audit.

## Read-only baseline

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
  the only added step is the repository security guard before preflight.

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

## Historical signing exposure

First key addition: `7aab359efc2c902c4dfb20e107b9d815f26818b7` (2026-09-18).
Key removal from security lineage: `ed9a42444e236c7ec1578271e3884741a22ba0df`
(2026-09-22). The same keystore blob appears in all exposed histories.

| Remaining branch | Baseline head | Commits inspected | Snapshots containing keystore | Snapshots containing literal signing passwords |
|---|---|---:|---:|---:|
| main | 6a796349e05d7ae59dac22bb4ec6e291110fd5a1 | 468 | 60 | 54 |
| test/1.2-test3-security | 64c41c270d776d660043cd3bc64a41a636e7e973 | 464 | 57 | 51 |
| backup/main-before-stable-1.3-20261001 | cad84c8df708ffff9f894d366e14232ab129801f | 123 | 12 | 12 |

Main and security source have clean current trees, but retain the key via ancestors.
Backup contains the key and literal passwords **in its current tree**. It remains
intentionally unchanged as the owner-requested rollback point. Full per-branch SHA
lists are in [security-history-findings.json](security-history-findings.json), containing
only metadata, no secret values. Counts overlap between branches and must not be summed.

Historical workflows: 24 unique versions inspected; 18 contain floating Action refs
and one explicit write permission. Old files are not automatically run just because
they exist in history. Manual runs at old refs and old clones remain a risk; full-SHA
policy now blocks floating Actions, but does not remove historical material.
The heuristic tree/history scans and GitHub patterns cannot prove absence of every
possible password, encoded secret, old log disclosure or externally retained copy.

## Proposed history cleanup — NOT executed

1. Owner retains an encrypted/offline backup of the current key and a private recovery
   archive, verifies existing APK certificate and inventories all distribution channels.
   Do not create a new public backup of contaminated history.
2. Freeze repository writes and record all refs, PR refs, original heads and working
   clones. Confirm a separate maintenance window and exact authorized ref scope.
3. In an isolated mirror, remove `app/namaz-release.jks` and any renamed copies from
   all affected history. Remove/redact literal `storePassword`/`keyPassword` values
   in historical Gradle configurations and any other discovered credential copies.
   Obtain replacement values privately without displaying them. Re-scan before push.
4. Review an old-to-new commit map and unchanged production trees, signing configuration
   and verification certificate before explicitly approving rewritten pushes.
   All three remaining branch heads change. At least the introduced key commit and
   its descendants are affected: baseline main 357, security source 353, backup 12.
   Password remediation may affect additional commits. Exact new SHAs require the
   approved local rewrite and are not guessed in advance. Commit/tag signatures may
   be invalidated/removed by rewriting.
5. The public backup ref must be sanitized too or it keeps the key reachable.
   Its old SHA ceases to be the public rollback ref; record the sanitized replacement
   and keep original recovery material only in owner-controlled secure storage.
6. Owner temporarily adjusts protection only for the separately approved rewrite,
   immediately restores it, and verifies main/PR/CI/manual build settings afterwards.
   App identity and certificate are unchanged by source-history cleanup; workflows
   pinned to external Action SHAs remain valid. Local refs and links to old commit
   SHAs must be updated. Existing runs/artifacts remain tied to the old commit.
7. Collaborators should freshly clone. Unpublished work must be rebased/cherry-picked
   onto sanitized history; do not merge old history back. Review caches, PR refs,
   archives, forks and artifacts with GitHub Support where necessary. No GitHub
   Releases/tags were present in this audit; this does not exclude existing downloads.
8. Rewriting does not revoke leaked key copies. Investigate an Android/RuStore-supported
   certificate migration separately and test updates/data preservation across supported
   devices before any owner-approved rotation or publication. A naive new certificate
   can prevent updating already installed APKs. No new key is generated automatically.

## Branch decision

Keep `main`. Temporarily retain `test/1.2-test3-security` as verified source and
`backup/main-before-stable-1.3-20261001` as the explicitly retained rollback point.
No existing branch was deleted during the security audit. Prior owner-authorized
release cleanup already removed the old 20260923 backup, patch-1, test2, test3,
test3-silent-updates and duplicate backup-before-stable-2026-10-01 refs.
Deleting a Git ref does not delete a repository or organization and does not erase
reachable signing secrets from history. The new backup requires a separately
approved replacement/removal plan, not deletion merely because the phone works.

## Owner follow-up

Enable account 2FA/passkeys and keep recovery codes privately; inspect sessions and
organization-wide hooks. Consider a separately authorized selected-repository scope
for Codex Connector without disrupting the schedules repository. Migrate signing
inputs into a main-only Environment. Approve history cleanup and a compatible signing
key migration separately after reviewing their consequences. Do not assume scanners
detect a JKS binary or revoke its private key.

References:
- https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository
- https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions
- https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages
- https://developer.android.com/studio/publish/app-signing
