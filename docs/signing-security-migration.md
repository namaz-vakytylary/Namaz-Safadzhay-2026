# Stable signing security migration

Scope: `namaz-vakytylary/Namaz-Safadzhay-2026` only. Preserve `ru.namaz.safadzhay`,
stable 1.3/33, installed user data and the current release certificate until a
compatible signing migration is verified. No APK build, Release or publishing
is authorized by this procedure itself. Account 2FA is left disabled by the
owner's explicit choice. Do not change `namaz-schedules` or organization access.

## Current protection and remaining work

The manual stable build job uses `stable-signing`. Its saved GitHub rules allow
only branch `main`, require reviewer `emilzhianshin-dot` and disallow administrator
bypass. Self-review is allowed for the sole owner. PR checks and CodeQL do not
use this Environment or signing secrets.

The Environment currently has no secrets. The existing four repository secrets
still provide working signing inputs. This intermediate approval gate does not
isolate repository secrets: other authorized workflows could reference them.
Moving secrets does not revoke a previously copied signing key.

## Move the existing signing inputs

1. The owner privately locates the original protected keystore and its passwords.
   GitHub does not reveal saved secret values. Do not recover them through logs,
   artifacts, chat, historical public objects or an extraction workflow. If the
   owner has no protected copy, stop and agree on a recovery plan first.
2. In repository Settings -> Environments -> `stable-signing`, add these four
   secrets using their existing names and values:

   | Environment secret name | Input |
   |---|---|
   | `NAMAZ_TEST_KEYSTORE_BASE64` | Base64 encoding of the current keystore |
   | `NAMAZ_TEST_KEY_ALIAS` | Current signing alias |
   | `NAMAZ_TEST_KEY_PASSWORD` | Current private-key password |
   | `NAMAZ_TEST_STORE_PASSWORD` | Current keystore password |

   Enter values privately in GitHub; do not upload the keystore to the repository.
   Verify that all four names appear under Environment secrets. Keep the existing
   repository secrets until the Environment inputs have been verified.
3. Environment secrets override repository secrets with the same names. Missing
   Environment secrets can still fall back to repository values. Therefore, a
   successful run alone does not prove that all four were migrated. Verify all
   four Environment entries first; only approve the expected `main` revision.
4. After the owner separately authorizes a verification APK build, run the stable
   workflow and personally approve the deployment. Inspect the signing certificate
   and update the installed 1.3 without uninstalling it, preserving preferences
   and downloaded schedules. Do not publish the verification APK automatically.
5. After success, remove only the four duplicate repository secrets. Retain the
   Environment copies and a protected recovery copy. A separately authorized
   verification then confirms there is no repository fallback. If it fails, fix
   the Environment entries rather than weakening the approval rules.

Expected current certificate SHA-256:
`b7a6e8c97ee7ea53c18d5d89082628caa594428b18fb919bd4f3dd9ed7044e62`.

## Replace the exposed signing key compatibly

The current app supports Android API 23 and above. Android APK Signature Scheme
v3 supports signing-certificate rotation from API 28. `apksigner` can use a
certificate lineage and a chosen rotation threshold; earlier platforms use the
original key. AOSP explicitly does not recommend key rotation on API 31 and
earlier; the default tool threshold is API 33. Prefer evaluating the default
Android 13+ path first, with any lower threshold requiring additional compatibility
assessment. Retaining the original key on older supported platforms leaves the
compromise risk there. A new password for the same key does not revoke copied
private-key material.

Do not merely replace the JKS or edit the pinned certificate. A new certificate
without a supported proof of rotation can block in-place updates. RuStore's
documented signature replacement through support archives existing versions and
requires re-upload; it does not establish update compatibility for this app.

Before implementation, obtain RuStore's written answer for this package:

- Does RuStore accept an APK with a valid old-to-new signing lineage, using the
  old certificate below API 33 and the new certificate for API 33+? Is a lower
  threshold supported, and what additional compatibility checks does it require?
- Can this be published without deactivating the current certificate and archiving
  all existing versions? How does the store verify the different platform signers?
- What supported process handles a compromised key while preserving updates and
  local data on existing installations? Which Android versions retain old-key risk?

After the distribution process is confirmed, the owner generates and retains a
new private key privately. Use both old and new signing keys to create a lineage
in a protected signing environment. Prepare an implementation PR to update
signing and certificate verification consistently. Test update/data preservation
on API 23, 24/27, 28/32 and 33+ with the original installed 1.3. A working lineage
on newer Android does not revoke the old key on older Android. The owner must
choose a supported-device policy before calling the compromise fully addressed.

The existing build and release remain on the current certificate until those
conditions are met. No replacement key or certificate is generated by this PR.

## References

- [GitHub Environment secrets and protection rules](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [GitHub secret precedence](https://docs.github.com/en/actions/reference/security/secrets)
- [Android app update identity and proof of rotation](https://developer.android.com/google/play/app-updates)
- [apksigner rotation options](https://developer.android.com/tools/apksigner)
- [APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3)
- [RuStore signature replacement](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature)
