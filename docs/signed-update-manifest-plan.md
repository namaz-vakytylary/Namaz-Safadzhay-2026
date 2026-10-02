# Подписанный manifest обновлений: план миграции

Статус: архитектура и изолированный verifier, без включения в remote update flow.
Ни production private key, ни production public key здесь не созданы. Репозиторий
`namaz-vakytylary/namaz-schedules` не изменён. APK signing и update signing — независимы.

## Модель угроз

HTTPS + fixed origin + SHA-256 защищают транспорт и целостность JSON относительно
manifest. Контроль над GitHub Pages/публикацией manifest позволяет заменить и JSON,
и SHA. Валидация ограничивает вред форматом данных, но допускает ложные времена
намазов и ложные праздники. Pinning TLS GitHub Pages этот сценарий не решает и
добавляет риск отказов при смене инфраструктуры/сертификатов; не внедрён.

## Предлагаемый протокол v2

- Отдельный URL `manifest-v2.json` и detached signature с фиксированным путём;
  не менять действующий v1 до решения владельца и поддержки старых APK.
- Подписывать **точные UTF-8 bytes** manifest после domain prefix
  `namaz-update-manifest-v2` и одного байта NUL. Не пересериализовывать JSON
  между подписанием и проверкой. Исключается неоднозначная canonicalization.
- Подпись Ed25519 (64 bytes), public key Ed25519 SPKI (44 bytes) закреплён в APK.
  При ротации update keys — заранее ограниченный trust set/key ID в APK;
  ключ из manifest или fetched key не становится trust anchor.
- Verify signature **до парсинга и доверия** path, SHA, versions. Затем строгий
  JSON parser, fixed-origin paths, limits, schema, SHA и бизнес-валидация.
- Подписываемые поля: schemaVersion=2, feed identity/package, manifestSequence,
  keyId, issuedAt/expiresAt, все schedule/holiday entries и их version/path/bytes/SHA.
  Точный срок действия должен учитывать редкую публикацию годовых расписаний.
- Сохранять подписанные manifest bytes + signature + payloads атомарным snapshot;
  проверять подпись и SHA при чтении. Удерживать maximum accepted sequence и
  per-year version/hash; downgrade, same sequence/version with changed content
  отклонять. При ошибке/отсутствии подписи — предыдущий verified или встроенный.
- Не удалять предыдущий рабочий snapshot до завершения проверки и finishWrite.
  Истечение manifest блокирует новые сетевые обновления; offline последнее
  проверенное расписание остаётся доступным. Политика очень старого кэша отдельно.
- Очистка/повреждение локального состояния теряет high-water mark. Для защиты
  первого запуска/после очистки нужен minimum sequence/trusted baseline в APK.
  Подпись без anti-replay политики не защищает от старого правильно подписанного feed.

## Android API 23–32

Platform `Signature/KeyFactory Ed25519` гарантирован только API 33+. Подготовленный
`SignedManifestVerifier` возвращает false при отсутствии provider. Его **нельзя
подключать как единственный verifier ко всем поддерживаемым устройствам сейчас**.
Нужно отдельно выбрать поддерживаемый, проверенный pure-Java Ed25519 provider
(например, оценить Tink/Conscrypt по текущим версиям, CVE, размеру и minSdk), либо
рассмотреть SHA256withECDSA/P-256, доступный на старых Android. Не реализовывать
криптографию самостоятельно. В этом PR новых production dependencies нет.

## Ключи и публикация

Владелец отдельно создаёт update key в безопасной среде. Private key хранится вне
репозитория расписаний и APK signing workflow: offline signer либо независимый
защищённый signing service/Environment с review, куда непривилегированный publish
job не получает доступ. Подписывающий шаг должен проверять конкретный reviewed
manifest, а не автоматически подписывать всё содержимое скомпрометированного feed.
В GitHub Pages публикуются только public data и detached signatures. Production
private key не передаётся в чат/отчёт и не коммитится.

## Порядок миграции

1. Решение владельца о протоколе, provider для API 23..32, trust key и хранении ключа.
2. Отдельное разрешение изменить `namaz-schedules`: совместная публикация v1/v2.
3. Независимые RFC known-answer tests, wrong key/signature, tampering, missing
   signature, expired manifest, replay/downgrade, mixed-generation snapshot,
   AtomicFile interrupted write, offline startup на API 23/27/28/32/33/35/36.
4. PR интеграции, включающий v2 fail-closed режим без unsigned fallback для нового
   клиента, с предыдущим verified/built-in fallback. Старый v1 snapshot не считать
   cryptographically verified; разрешить миграционный показ до первого v2.
5. Владелец отдельно разрешает production build, подтверждает `stable-signing` и
   проверяет update поверх установленного APK. Решение о публикации отдельно.

Текущие tests verifier используют публичный RFC 8032 test seed, а не новый ключ.
Verifier подтверждает только detached подпись и bounds; sequence/expiry, pin trust
configuration и atomic signed snapshot должны быть реализованы в integration PR.

Источники: https://developer.android.com/reference/java/security/Signature
и https://www.rfc-editor.org/rfc/rfc8032 .
