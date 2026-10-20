# Stable 1.4 candidate — preparation only

Base: main `93afcbf7e8f5853ad7522638240274baa6f1bae8`.
Selected source: TEST `94409673553e281bd7b675c04d8eb9c8824e5cf9`.
Branch: `candidate/stable-1.4`. Neither original branch is modified.

## Selected behavior

- GitHub-only schedules, no runtime prayer tables; compatible `downloaded-schedules.json` schema 1 cache.
- Shared verified repository for UI and reminders; offline retry and honest missing-date state.
- Accepted revision history, coverage preservation, atomic cache, aggregate download progress and cancellation.
- Shared reminder queue with current-city/revision/time checks, durable duplicate guard and offline restoration.
- Exact alarms on API 23–30 restored; production notification defaults and local sound URI validation retained.
- Bounded network-first location with GPS fallback and manual guard reset only for a fresh process.
- TEST notification-card styling, contrast/touch/TalkBack improvements, original three-theme setting.
- Validated Ramadan boundaries with optional stable IDs, future published-year support and smaller approved iftar image.
- Normal settings card **Данные расписаний**, without diagnostic traces or artificial clocks.

The artificial Ramadan preview, diagnostic tools and app-update implementation remain in TEST. No new RuStore SDK or Tink dependency is added to production. Mandatory Ed25519 is not activated. Source schedules, signing keys/certificate, application ID and version metadata remain unchanged.

## Checks and limitations

- Local Python policy/security checks, preflight and diff checks run before commits.
- Candidate push runs Debug/Release unit tests, both lint variants and merged-manifest audit. This workflow has no APK packaging or signing step.
- Main's privacy, remote Ramadan, security tests and guarded stable-signing workflow are retained.
- Additional tests cover API 23/30 exact alarms and legacy defaults, modern exact-access fallback, production data-card content and API 36/37 compatibility.
- Robolectric 4.17 supports API 23–37; tests use Java 21, while Android source/bytecode remains Java/Kotlin target 17. These are framework-model tests, not physical-device delivery proof.
- `compileSdk`/`targetSdk` remain 35 pending review of the newer target behavior and toolchain migration. Tests on Android 16/17 do not by themselves raise the app's target SDK.
- Production APK 1.3→candidate installation migration, real Doze/OEM delivery, TalkBack and actual-device Android 16/17 tests still require an explicitly authorized candidate APK and available devices.
- No release APK, PR, merge or publication is part of this preparation.

## Next release decisions

Choose the exact target SDK/toolchain after compatibility review, confirm release version metadata, authorize a candidate APK for migration tests, review the final diff and verification results, then separately authorize any PR/merge/publication.

Official compatibility references:

- https://robolectric.org/compatibility_table/
- https://github.com/robolectric/robolectric/releases/tag/robolectric-4.17
- https://developer.android.com/build/releases/about-agp
