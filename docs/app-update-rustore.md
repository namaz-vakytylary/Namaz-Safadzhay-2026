# RuStore app updates — TEST implementation

Implemented only in `test/1.2-test3-security`; stable main 1.3/33 is unchanged.

The approved B dialog uses the original `app/src/main/res/drawable/ic_launcher_source.png`,
the existing AppColors palette, 18 dp corners and padding, 56 dp square logo,
wrap-content Russian text, scrolling when needed and a 200 ms fade respecting disabled animations.

Only the official RuStore SDK `ru.rustore.sdk:appupdate:10.5.1` is integrated.
Availability, version name/code and release notes come from `getAppUpdateInfo()`.
A prompt appears after the home screen and timetable are ready, without covering
permissions/settings/schedule-update dialogs, at most once per rolling 24 hours.
The timestamp survives restarts. Configuration recreation restores an already-open
dialog instead of counting it as a new prompt.

Download is started by the user using `SILENT` with fresh single-use AppUpdateInfo.
Percentages use only SDK byte totals. Unknown totals use an indeterminate bar.
Continue dismisses the window while RuStore continues downloading. A small notice
lets the user open installation after background download completes.
`DOWNLOADED`, not 100% downloaded bytes, authorizes `completeUpdate(FLEXIBLE)`.
RuStore provides confirmation/restart. Cancellation/failure leaves the app usable.
A success notice is shown only after an actual increase of the installed versionCode;
first installation, cancelled installation and same-version reinstall cannot claim success.

No custom APK URL, downloader, REQUEST_INSTALL_PACKAGES or other stores were added.
All update preferences use the separate `app_updates` file.
City, theme, notifications, prayer schedules, Ramadan, signing inputs and version metadata are preserved.

## TEST preview

Settings → About → Check update window · TEST (Russian labels in the APK).
Choose a state manually. All fixtures are explicitly labelled as examples;
preview invokes no SDK and modifies no real update history.
Production `src/release` supplies a fixture-free disabled preview; the existing
TEST release source-set selection deliberately uses `src/debug`.

## Real-store validation

The TEST package `ru.namaz.safadzhay.test` is separate from the production RuStore listing.
The preview and SDK adapter tests do not prove a live production-store installation.
For end-to-end validation, use a store-recognized package/signature and two eligible
versionCodes published/available to the testing account; check cancellation,
background download, process restart, real installation and the subsequent success notice.
Do not transfer this change to main or publish without separate user authorization.

Official SDK reference: https://www.rustore.ru/help/sdk/updates/kotlin-java/10-5-1

The TEST build workflow retains its manual trigger and additionally runs for updater
source/test pushes in the TEST branch only. Existing tests/lint/signing/APK gates stay intact.
