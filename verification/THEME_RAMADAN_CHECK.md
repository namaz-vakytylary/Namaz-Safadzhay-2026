# Три темы и тестовый Ramadan UI

Репозиторий: `namaz-vakytylary/Namaz-Safadzhay-2026`.
Единственная изменяемая ветка: `test/1.2-test3-security`.
Исходный коммит: `a7d46f43596b6b776151e9d9fdf64cc4e8a69a44`.

## Реализация

- `AppTheme.kt`: режимы System/Light/Dark, семантическая палитра. Все прежние тёмные значения сохранены; геометрия обычных экранов не менялась.
- Светлая тема использует фон `#F7F9F8`, белые поверхности, тёмный текст, зелёный `#007C4D`, лёгкую границу и светлое выделение активной карточки. Контраст текста и зелёных подписей проверен автоматически.
- `ThemeSettings` сохраняет только `app_theme` в существующем SharedPreferences `settings`; default и неизвестное значение — System. Город, уведомления, звук и татарские названия не сбрасываются.
- System читает `Configuration.UI_MODE_NIGHT_MASK`. Android пересоздаёт Activity при смене системной темы; явные Light/Dark игнорируют этот режим. Выбор темы также пересоздаёт Activity, сохраняя дату, месяц, вкладку, открытый экран и диалог «О приложении».
- «Настройки → Тема приложения» открывает существующее полноэкранное окно настроек с тремя radio-вариантами и требуемыми подписями.
- Цвета применены к обычным экранам, календарю, уведомлениям, выбору города, компасу и диалогам. WindowCompat управляет светлыми/тёмными системными иконками; на Android 6/7 светлая тема использует тёмную navigation bar для белых системных иконок. Автоматический Force Dark отключается только на поддерживаемых API.
- В Light Ramadan UI использует читаемые светлые карточки вместо тёмных фотографий. Dark сохраняет прежние изображения и размеры. После начала ифтара Light скрывает пустые строки таймера.

## Искусственная дата — только debug

`app/src/debug/java/ru/namaz/safadzhay/RamadanUiPreview.kt` содержит `TEST_RAMADAN_START_DATE = "2026-08-10"` и 30-дневный UI fixture. Это не реальная дата Рамадана.

Debug APK: «Настройки → Проверка Рамадана». Доступны обычный день 09.08, сухур, дневной пост, ожидание ифтара и начало ифтара 10.08.2026, а также возврат к текущему времени устройства. Сцены замораживают только UI clock; время начала ифтара берётся из существующего расписания выбранного города плюс три минуты для сцены «ифтар наступил». Сами времена намазов не меняются. Заголовки показывают «Тест UI».

`app/src/release/java/ru/namaz/safadzhay/RamadanUiPreview.kt` — отдельная реализация: fixture недоступен, список сцен пуст, часы возвращают настоящее время, synthetic Ramadan day всегда null. Release не читает debug-настройку. Даже сохранённое `debug_ramadan_ui_scene` не включает подмену в release.

Нативные Android source sets исключают debug-файл из release-компиляции. Проверено содержимое скомпилированных классов: строка `2026-08-10` и имя `TEST_RAMADAN_START_DATE` присутствуют в debug `RamadanUiPreview.class` и отсутствуют в release-классе. Дата `2026-08-10` закономерно остаётся в настоящем списке дат расписания; этот список не изменялся.

Реальный `HolidayCalendar`, исламские даты, праздничные данные, источники обновлений и планировщики уведомлений не модифицированы. UI fixture не используется как fallback настоящей даты.

## Проверки

Проверено 2026-10-04, JDK 17, Gradle 8.10.2, Android SDK 35.

| Проверка | Результат |
|---|---|
| `:app:assembleDebug` | PASS; debug APK собран и подпись v1/v2 проверена |
| `:app:compileReleaseKotlin` | PASS; release-код скомпилирован |
| `:app:testDebugUnitTest` | 78 tests, 0 failures, 0 errors |
| `:app:testReleaseUnitTest` | 78 tests, 0 failures, 0 errors |
| `:app:lintDebug` / `:app:lintRelease` | PASS; 0 errors, 62 warnings в каждом варианте; lint не отключён |
| `scripts/preflight.py` | PASS, включая точные исходные таблицы обоих городов, 1530 времён |
| Python security tests | 7 tests, PASS |
| Compiled fixture isolation | PASS; искусственная дата исключена из release-класса |
| `:app:verifyReleaseSigning` без секретов | Ожидаемый отказ: `Release signing is not configured`; защита сохранена |
| `:app:connectedDebugAndroidTest` | Задача завершилась успешно; instrumentation-тестов и подключённого устройства нет, фактический запуск тестов на Android не выполнен |
| Native UI screenshots | 40 изображений, Robolectric native graphics, API 33, 360×800 |

Подписанный release APK локально не создавался: signing inputs не предоставлены и не извлекались. CI сохраняет прежний защищённый шаг release-подписи и добавляет debug unit tests, lint и отдельный debug UI APK. GitHub Actions в этой сессии не запускался.

Снимки ниже — отрисовка настоящих Android Views через Robolectric, не скриншоты устройства/эмулятора. System-mode смена, выбор темы, перезапуск и сохранение настроек проверены Robolectric. Физические датчики, геолокация телефона, доставка уведомлений Android и фактический вид системных панелей на устройстве требуют проверки устройства. Цвета стрелки, Каабы и букв компаса дополнительно отрисованы с детерминированным heading/location fixture; отдельно проверен экран отсутствующих датчиков.

## Сценарии

| Сценарий | Результат |
|---|---|
| System + Android Light | Light, PASS |
| System + Android Dark | Dark, PASS |
| Light + Android Dark | Light, PASS |
| Dark + Android Light | Dark, PASS |
| Смена Android uiMode при открытых настройках | Тема обновляется, экран сохранён, PASS |
| Перезапуск и theme selector | Режим сохраняется, правильный radio отмечен, PASS |
| Город/уведомления/татарские названия/звук | Настройки сохраняются, PASS |
| Today/Schedule/Settings/Qibla/notifications/city/about/selector | Light/Dark отрисованы и проверены |
| Debug 09.08.2026 | Обычный UI, PASS |
| Debug 10.08.2026 | Первый искусственный день Рамадана, PASS |
| Ramadan Light / Dark | Сухур, ожидание ифтара, начало ифтара, календарь, PASS |
| Ramadan System + Android Light/Dark | Соответствующая палитра, PASS |
| Debug UI clock и notification scheduler | Scheduler использует настоящую дату; подпись настроек не меняется, PASS |
| Release с сохранённой debug-настройкой | Настоящие часы, fixture не включается, PASS |
| Настоящий Ramadan 19.02–19.03.2026 | Исходная логика сохранена и проверена |

Новые проверки находятся в `ThemeAndRamadanTest.kt` (14 тестовых методов для обоих вариантов) и `scripts/test_theme_security.py` (3 проверки изоляции и сохранения CI/signing gates). Существующие regression, notification, calendar, schedule validation/cache, update checker, compass math и font-scale тесты прошли.

## Снимки отрисовки

![Обычные экраны и выбор темы](theme-ui/ordinary.webp)

![Debug Ramadan UI; искусственные даты](theme-ui/ramadan.webp)

Полный комплект воспроизводится unit-тестами в `app/build/reports/theme-ui`; CI сохраняет эту папку как verification artifact.

## Полный список изменений

```text
.github/workflows/build-apk.yml
app/src/debug/java/ru/namaz/safadzhay/RamadanUiPreview.kt
app/src/main/java/ru/namaz/safadzhay/AppTheme.kt
app/src/main/java/ru/namaz/safadzhay/DesignViews.kt
app/src/main/java/ru/namaz/safadzhay/MainActivity.kt
app/src/main/java/ru/namaz/safadzhay/UiPreviewScene.kt
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml
app/src/release/java/ru/namaz/safadzhay/RamadanUiPreview.kt
app/src/test/java/ru/namaz/safadzhay/ThemeAndRamadanTest.kt
scripts/test_theme_security.py
verification/THEME_RAMADAN_CHECK.md
verification/theme-ui/ordinary.webp
verification/theme-ui/ramadan.webp
```

`app/build.gradle.kts`, AndroidManifest, исходные расписания, реальные праздники, signing scripts/credentials, backup flags и validation/cache/security-код сохранены. `main` и `namaz-schedules` не изменялись в рамках этой задачи. Merge и PR не создавались.
