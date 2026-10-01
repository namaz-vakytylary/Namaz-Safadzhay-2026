# Намаз Вакытлары 1.3

Стабильное приложение: `ru.namaz.safadzhay`, `versionName = 1.3`, `versionCode = 33`.
Предыдущие стабильные APK 1.2 из сборок 71 и 156 имеют versionCode 32.

## Ручная проверка и сборка

В GitHub Actions откройте **Build Namaz Vakytylary 1.3 RELEASE**, выберите `main`
и нажмите **Run workflow**. Push и создание веток сборку не запускают.

Workflow последовательно выполняет preflight, тесты восстановления ключа,
Android unit tests, Android lint, release build и проверку готового APK.
Ошибка любого обязательного этапа блокирует выдачу release-артефакта.

Готовый файл: `Namaz_Vakytylary_1.3.apk`, артефакт `Namaz-Vakytylary-1.3-RELEASE`.
Отчёты и изображения тестовых экранов: `Namaz-Vakytylary-1.3-verification`.

Сборка использует существующие GitHub Secrets `NAMAZ_TEST_KEYSTORE_BASE64`,
`NAMAZ_TEST_STORE_PASSWORD`, `NAMAZ_TEST_KEY_ALIAS`, `NAMAZ_TEST_KEY_PASSWORD`.
Это внутренние имена ранее настроенных секретов. Идентичность готового APK стабильная;
сертификат проверяется по `verification/release-certificate.sha256` и должен совпадать
с предыдущими stable APK. Секреты нужны только шагу release build.

Локальная проверка без создания APK, при наличии JDK 17 и Android SDK 35:

```sh
python3 scripts/preflight.py
python3 -m unittest discover -s scripts -p 'test_*.py'
bash gradlew --no-daemon :app:testReleaseUnitTest
bash gradlew --no-daemon :app:lintRelease
```

Полная готовность релиза требует успешного ручного workflow и проверки APK на телефоне
поверх установленной стабильной версии, с сохранением настроек и данных.

## Точка возврата

`backup/main-before-stable-1.3-20261001` → `cad84c8df708ffff9f894d366e14232ab129801f`.
Не удалять до ручной проверки stable 1.3 на телефоне.
Старые ветки приложения сохраняются до успешной release-сборки.
`namaz-vakytylary/namaz-schedules` используется только как источник данных.

Исторические документы `TEST3_README.md`, `verification/test3/` и `verification/baseline-1.1/`
описывают прежние сборки и не являются подтверждением проверок stable 1.3.
Подробный отчёт подготовки: `verification/STABLE_1.3_PREPARATION.md`.
