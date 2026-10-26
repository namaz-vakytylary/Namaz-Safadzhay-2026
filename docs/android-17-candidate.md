# Android 17 — candidate migration

Scope: `candidate/stable-1.4`, starting from `605e568103e89b2aedb9b02547dae166e8a09551`.
The owner authorized continuing the proposed SDK migration. main, TEST, server data,
application identity/version, signing certificate and publication remain outside this step.

## Build configuration

- minSdk 23; compileSdk/targetSdk 37.
- AGP 9.1.1 with Gradle 9.3.1, the documented compatible minimum for API 37.
- Remove the standalone Kotlin Android plugin and use AGP's built-in Kotlin 2.2.10.
- Java source/bytecode target 17 is retained; built-in Kotlin derives the same target
  from compileOptions. CI and Robolectric tests run on Java 21.
- Gradle distribution is pinned to the official SHA-256. The existing, independently
  pinned 8.10.2 bootstrap JAR is retained; it launches the pinned 9.3.1 distribution.
- No new runtime dependencies, permissions, foreground services or R8 enablement.
- Explicitly retain both Debug and Release unit-test components, since AGP 9 otherwise
  enables only the tested build type. CI audits the real release task graph in dry-run
  mode to ensure signing, both unit-test variants and lint still precede packaging.
- Preflight, the merged-manifest audit and the APK verification script require the
  new target SDK. APK tools use AGP's default Build Tools 36.0.0.

## UI and lifecycle

The existing single-column style and phone portrait setting are retained. At target 37,
Android ignores orientation/resizability restrictions on large displays; no opt-out is
added. The existing adaptive Today layout is exercised in tablet portrait/landscape,
short and narrow windows. Settings panels now add side system-bar/cutout insets and
respect the bottom mandatory-gesture inset, preserving the original inner padding.

`ModernAdaptiveLayoutTest` uses native View measurement on API 36 and 37, all three
themes, normal/enlarged fonts and the existing Ramadan artwork/text. It checks that
prayer rows remain reachable, text is not ellipsized, and saved calendar/settings/manual
city state survives recreation into a landscape configuration. CI retains rendered
`modern-*` images for inspection.

These are framework-model tests, not proof of physical device rotation, OEM behavior,
audio playback or notification delivery. Test-only rendered Ramadan content does not
alter production dates or alarms.

## Target-specific review

- Production does not reflect on MessageQueue or mutate static-final Android fields.
- Notifications use the standard Notification builder and system-managed channel
  sound, not custom RemoteViews or background MediaPlayer playback. No new background
  service/permission is introduced to work around Android 17 audio restrictions.
- Schedule transport connects to the approved public HTTPS repository; it does not
  discover LAN devices. No local-network permission is requested.
- Device checks must include actual notification sound/channel behavior, exact-access
  denial/restoration, time/zone changes and offline boot on the newer target.

## Required physical/emulator checks before release

1. API 37 tablet/foldable: rotate, resize, split-screen; all settings and Qibla;
   side cutouts, gesture/three-button navigation; light/dark/system and large fonts.
2. Rotate while a city is selected manually, while viewing another calendar date and
   while a data download is active. No duplicate reminders or lost manual guard.
3. Android 6 and modern phones: same visual style, permissions, sounds and offline cache.
4. Approved production-signed installation migration from 1.3 with no data deletion.

No application APK is prepared by the repository-check workflow. Release metadata,
candidate APK authorization, PR, merge and publication remain separate decisions.

Official references:
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://gradle.org/release-checksums/
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored
