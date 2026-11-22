# N-LIC — AWG3.1, XHTTP и пакетная помощь, 2026-11-12

## Подтверждено в исходном коде

1. XHTTP + REALITY/TLS в Xray: настоящий HTTP-транспорт через конфигурацию сервера, импорт и тесты из PR #35.
2. Импорт AmneziaWG 1/2/3.1 из .conf: валидируются исходные ключи, единственный peer, полный маршрут, без исполнения команд, с проверкой H1-H4/S1-S4, HeaderProtectionKey, таймеров, RandomTrailers и DisableCookies. Конфиг хранится через зашифрованный SubscriptionVault, отличается от plain WireGuard (protocol=AMNEZIAWG).
3. Режим LIC-Bay для VLESS+REALITY/RAW включает **реальные нативные сокетные манипуляции ByeDPI**, а не очередную FinalMask фрагментацию:
   - Fake TTL: fake TCP first-flight (--fake -1), TTL 8, рандомизация полей ClientHello fake (--fake-tls-mod rand).
   - TCP OOB: --disoob 3+s (внеполосный байт и обратная последовательность).
   - Legacy: прежняя стратегия без пакетной помощи.
   - В главных настройках выбор методов доступен только при отключённом VPN, исходящие SOCKS relay параметры ограничены 127.0.0.1 и фиксированным портом. Другие протоколы и XHTTP не подвергаются этой модификации.
4. Базовый Xray Auto продолжает использовать первый подтвержденный HTTPS egress и мониторинг/повторный поиск. В смешанной подписке, где среди участников есть не-RAW узлы, новые ByeDPI манипуляции не применяются автоматически.

## Чего код ПОКА НЕ делает

### Реально запускать AWG3.1

В N-LIC ещё нет нативного AWG 3.1 backend. Xray и sing-box из действующей сборки не понимают новый AWG протокол, поэтому подключение импортированного AWG3.1 профиля явно заблокировано. Передавать профиль в обычный WireGuard **нельзя**: так теряются Header Protection / padding / timelock параметры и не устанавливается рабочая защищенная сессия.

Изученные upstream варианты:
- https://github.com/amnezia-vpn/amneziawg-go — upstream Go engine;
- https://github.com/amnezia-vpn/amneziawg-android — Android native userspace bridge;
- https://github.com/wgtunnel/core — ещё один backend с поддержкой AWG3.1, но его Kotlin/AGP 2.4/9.5 требует отдельной проверки интеграции.

Доработка требует минимального, криптографически проверенного Android native binding и **одного** VpnService/TUN в приложении, тестов на ARM64, сохранения сокетов через protect() и корректного teardown. Эту работу нельзя честно заменить импортом текстового профиля.

### Реализовать NFQUEUE/raw packet injection Zapret2

Настоящая NFQUEUE / raw TCP инъекция с принудительными TTL/Seq/Checksum требует доступ к сетевому стеку, которого обычный Android VpnService не дает для защищенных физических сокетов Xray. На stock Android без root возможны только TCP-сокетные приемы (включая ByeDPI fake TTL/OOB). Полноценный Zapret2/nfqws не встроен и не должен показываться в интерфейсе как работающий.

Документация:
- https://developer.android.com/reference/android/net/VpnService
- https://github.com/hufrea/byedpi
- https://docs.amnezia.org/documentation/amnezia-wg/

## Проверки перед объединением

- Только DEV-подпись в отдельной ветке, master не менять.
- CI: assembleRelease, testReleaseUnitTest, lintRelease, наличие APK artifact.
- На телефоне: REALITY/RAW с Bay и Fake TTL, затем OOB и Legacy; проверить egress HTTPS и фактический поток, отсутствие DNS/IPv6 утечек. Разница между TCP CONNECT и реально рабочим HTTPS обязательна.
- Сравнить температуры и потери пакетов; при ухудшении отключить Fake TTL в Настройки → Обход DPI → LIC-Bay · пакеты TCP.
- Фактический провал DPI, сервер по IP, UDP blackout и недостаточная поддержка XHTTP/AWG требуют отдельной диагностики, а не бесконечного роста числа splits.
