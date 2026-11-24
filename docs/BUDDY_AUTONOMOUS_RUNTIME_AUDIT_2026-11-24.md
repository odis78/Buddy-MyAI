# Buddy Autonomous Runtime: аудит исходной базы и план проверки

Дата аудита: 2026-11-24  
Статус: этап 1, read-only анализ исходников и подтверждённых контрольных точек  
Рабочая ветка: `feature/buddy-autonomous-runtime-audit-20261124`  
Базовый commit: `d621393e51aede80691718b5f0093d4a2af72993`  
Репозиторий: `odis78/Buddy-MyAI`

## 1. Цель

Подготовить отдельную GT-сборку Buddy MyAI, которая самостоятельно запускает и обслуживает необходимые MCP-серверы и Tunnel Client без обязательного участия Termux.

Termux не удаляется, не очищается и не перенастраивается. Существующие процессы, конфигурации, токены, ключи, профили, логи и контрольные точки сохраняются. Новая среда должна быть изолирована в приватном каталоге приложения Buddy.

Нельзя считать работу завершённой только по успешной сборке APK. Нужны проверки запуска, реального MCP handshake, каталога инструментов, сетевых соединений, восстановления и обновления APK поверх предыдущей GT-версии.

## 2. Зафиксированная исходная база Buddy

Проверено на commit `d621393e51aede80691718b5f0093d4a2af72993`:

- `BuildInfo.java`: версия `2.3.19-JITI`, versionCode `216`.
- `app/build.gradle.kts`: namespace `app.buddy.assistant`, applicationId `app.buddy.myai`, minSdk 30, compileSdk 36, targetSdk 36, Java source/target 8.
- Debug applicationId suffix: `.test219`.
- Ветка checkpoint `checkpoint/buddy-myai-2.3.19-JITI-2026-11-24` и её commit должны оставаться неизменными.
- Установленная ранее на основном телефоне Buddy 2.3.17-JITI.1 / code 215 и отдельный GT Control Center не должны удаляться или заменяться в рамках этой работы.

### Важное архитектурное открытие

Buddy уже содержит встроенную Linux-среду. Это не пустая оболочка, куда придётся с нуля встраивать Linux:

- `Linux.java` разворачивает Alpine Linux 3.24.2 для aarch64 в приватном `filesDir/linux/alpine`.
- Архив Alpine проверяется по SHA-256 до распаковки.
- PRoot и loader поставляются в native libraries приложения.
- `Linux.builder()` запускает процессы внутри Alpine через PRoot.
- `Linux.exec()` уже умеет запускать команды внутри этой среды с таймаутом и ограниченным сбором вывода.
- `McpServer.java` реализует локальный MCP HTTP endpoint Buddy на 127.0.0.1:8766.
- `McpServers.java` умеет хранить и проксировать внешние HTTP MCP-серверы через локальный endpoint Buddy, а секреты серверов отделены от JSON-конфигурации.

Следствие: первым вариантом реализации нужно исследовать расширение уже существующей Alpine/PRoot-среды и её менеджера процессов. Не следует добавлять второй Linux-дистрибутив или копировать весь Termux в APK.

Ограничение текущей базы: найденные `Linux.exec()` и `McpServers` сами по себе ещё не доказывают наличие постоянного supervisor, который запускает несколько MCP stdio-серверов и Tunnel Client, контролирует их PID/готовность и безопасно перезапускает. Такой слой предстоит спроектировать и покрыть тестами.

## 3. Tunnel Client v0.0.19

Исходный tag `openai/tunnel-client v0.0.19` разрешён в commit:

`01d67bb4da0d8359b808b4761f8db171ab682cd7`

Проверенные признаки:

- Это Go-проект, а не Node.js-пакет.
- `go.mod` требует Go 1.27 и toolchain 1.27.3.
- В репозитории есть отдельный узкий runtime target `cmd/client-runtime`, помимо полного CLI.
- Runtime Dockerfile собирает его с `CGO_ENABLED=0` для целевых GOOS/GOARCH.
- В upstream есть тесты runtime supervisor, health, process handling, конфигурации и совместимости.

Архитектурное решение для проверки: сравнить два варианта:
1. Сборка минимального Linux/arm64 runtime binary и запуск внутри Buddy Alpine/PRoot.
2. Сборка Android/arm64 бинарника и запуск вне PRoot из приватного каталога приложения.

Нельзя автоматически копировать существующий Termux Android/arm64 бинарник в Alpine: ABI/динамические библиотеки и окружение могут отличаться. Нужен воспроизводимый build и реальный smoke-test именно в целевой среде Buddy.

## 4. Подтверждённая конфигурация работающей Termux-системы

По recovery checkpoint и redacted GitHub report от 2026-11-24:

- Buddy tunnel: Tunnel Client 0.0.19, профиль `buddy-myai`, отдельный каталог `~/.config/tunnel-client-buddy/`.
- Buddy MCP local health endpoint: порт 39073; проверка `/healthz=live`, `/readyz=ready`.
- Buddy MCP после запуска сообщил authenticated и 44 tools.
- Реальный read-only вызов Buddy `device_status` завершился успешно.
- Параллельно были healthy:
  - REA-Termux-Bridge 0.1.3;
  - DroidMCP-Filesystem;
  - REA-Termux-Android.
- REA 6.5.0: 208/208 tools, 6/6 prompts, initialize и binary-session request flow успешны, stderr пустой.
- В момент snapshot Buddy Tunnel Client имел PID 3411, helper managers PID 22438/22559/22793. PID являются временными, не использовать как постоянные идентификаторы.

Это подтверждённый снимок на 2026-11-24, а не гарантия текущего состояния в момент будущего запуска.

### Известная проблема, которую нельзя скрывать

До восстановления 24.11.2026 Buddy PID-файл содержал stale PID, процесс отсутствовал, порт 39073 отказывал в соединении. JITI launcher затем восстановил запуск. Это доказывает необходимость проверки фактической идентичности процесса и endpoint readiness, а не доверия PID-файлу или зелёному UI.

В старых логах встречались Control Plane 403 `unsupported_country_region_territory` и 502/EOF. После восстановления authenticated MCP и успешного tool call эти записи считаются историческими, пока новая диагностика не подтвердит повторение.

REA-Termux-Android ранее имел транспортное подключение, но пустой каталог инструментов в ChatGPT из-за неполного/ограниченного observation результата. Нельзя считать транспортный health достаточным доказательством доступности tools.

## 5. Разделение компонентов и границы миграции

До переноса нужно для каждого компонента зафиксировать:

- точный исходный репозиторий/commit или версию;
- исполняемый entrypoint и архитектуру;
- runtime (Go, Node, Python, shell или native);
- входной и выходной транспорт (stdio, Streamable HTTP, SSE, Unix socket);
- env key names без значений;
- конфигурационные файлы и относительные пути;
- локальные порты и зависимости;
- health/readiness критерии;
- каталог состояния, логи и поведение при остановке;
- права доступа и требования Android sandbox.

Особенно важно не смешивать четыре логических компонента и четыре отдельных Tunnel Client процесса. Реальный граф зависимостей и порядок старта должен быть восстановлен из действующих профилей и launcher scripts. Нельзя угадывать команды запуска или копировать секреты.

## 6. Предлагаемая архитектура Buddy Autonomous Runtime

1. **RuntimeInstaller**: проверяет/устанавливает подписанные и хэшированные runtime-артефакты в приватную область Buddy; установка атомарная, с staging и rollback.
2. **RuntimeManifest**: описывает компонент, версию, ABI, entrypoint, env key names, зависимости, порты, readiness probes и политику рестарта. Секретных значений в manifest нет.
3. **ProcessSupervisor**: один владелец каждого процесса; защита от дублей, process identity, ограниченные логи, graceful stop, timeout и backoff.
4. **StartupOrchestrator**: запускает зависимости последовательно; следующий этап начинается только после успешной readiness-проверки предыдущего.
5. **McpHealthVerifier**: отдельно проверяет transport, initialize/discovery, tools/list и безопасный тестовый вызов.
6. **SecretStore**: Android Keystore-backed шифрование локальных секретов; секреты не попадают в APK, Git, crash logs, exported config и отчёты.
7. **Foreground Runtime Service**: поддерживает разрешённую Android модель долгой сетевой работы с корректным типом foreground service для target SDK 36, уведомлением и управляемой остановкой.
8. **RuntimeDiagnostics**: redacted отчёт с версиями, hashes, process identity, health/readiness, MCP counts, exit codes и временными метками.
9. **RecoveryController**: перезапускает только отказавший компонент, не трогает здоровые процессы; лимиты рестартов и circuit breaker.

## 7. Обязательная тестовая матрица

### A. Статические проверки и supply chain
- Проверка хэшей runtime-артефактов и подписей/происхождения.
- SBOM и лицензии для каждого включённого бинарника.
- Проверка ABI: arm64-v8a / Linux aarch64, отсутствие случайных x86/Termux-only артефактов.
- Проверка отсутствия токенов, API keys, приватных конфигов и локальных путей в APK, Git diff и CI logs.
- Проверка размера APK и размера скачиваемого Alpine/runtime payload.
- Проверка атомарной установки, целостности после прерванного скачивания и rollback.

### B. Unit tests
- Разбор manifest и валидация графа зависимостей.
- Циклы/отсутствующие зависимости/дублирующие component IDs.
- State machine процессов: stopped → starting → ready → unhealthy → stopping.
- PID reuse и process identity.
- Повторный Start не создаёт второй процесс.
- Таймаут readiness и backoff.
- Redaction секретов во всех логах и исключениях.
- Атомарная запись состояния и восстановление после повреждённого state file.
- Закрытие сокетов/потоков при отказе запуска.

### C. Integration tests в Buddy Alpine/PRoot
- Запуск реального Linux/arm64 Tunnel Client runtime.
- Проверка DNS, TLS CA bundle, loopback и доступности локальных портов.
- Запуск MCP stdio child process и корректное завершение.
- Проверка signal/exit propagation через PRoot.
- Доступность файлов, прав, временных каталогов и storage quotas.
- Проверка сетевого reconnect после Wi-Fi/VPN переходов.
- Повторные циклы start/stop без накопления orphan processes.

### D. MCP end-to-end
- `healthz` и `readyz` каждого tunnel runtime.
- Authenticated MCP initialize/discovery.
- Полный tools/list: ожидаемые и фактические имена, отсутствие пропусков и дублей.
- Реальный harmless read-only tool call.
- Отдельно проверять 44 Buddy tools и REA catalog 208 tools/6 prompts там, где компонент действительно должен их предоставлять.
- Проверка пустого/частичного каталога, observation limit, session affinity и transport EOF.

### E. Android lifecycle и нагрузка
- Первый запуск после установки, первый download Alpine, повторный запуск.
- Уход в фон, блокировка экрана, возврат в приложение.
- Система выгружает процесс; приложение восстанавливает только необходимые компоненты.
- Перезагрузка телефона и ограничения автозапуска Android.
- Потеря/возврат сети, VPN и DNS.
- Недостаток места, RAM pressure, LMK, thermal throttling.
- Уведомление foreground service и корректная остановка.
- Длительный soak test минимум 2 часа, затем ночной тест.
- Серия не менее 20 циклов start/stop и 10 сценариев аварийного завершения.

### F. Update/signing
- Release APK подписан одним постоянным release certificate.
- SHA-256 certificate fingerprint записан в отчёт; приватный ключ никогда не публикуется.
- Повторная сборка подписана тем же ключом.
- APK с тем же applicationId и тем же signer обновляет предыдущую GT-версию поверх.
- APK с другим signer должен быть отклонён тестом; старую установку не удалять.
- versionCode строго увеличивается.
- Backup/restore keystore проверяется на отдельной машине/среде до первой установки production build.

## 8. GitHub Actions: обязательные jobs

1. `source-audit`: структура проекта, package/version invariants, запрет секретов, проверка manifest/runtime manifest.
2. `unit-tests`: JVM/Java tests для supervisor/orchestrator/state machine.
3. `runtime-linux-arm64`: воспроизводимая сборка Tunnel Client runtime и SHA-256.
4. `runtime-integration`: запуск runtime в Linux ARM64-compatible test container/runner, MCP health и process tests.
5. `android-build`: debug и release APK, lint, native libraries, APK metadata.
6. `apk-security`: apksigner verify, один signer, certificate fingerprint, permissions/manifest audit, secret scan.
7. `upgrade-compatibility`: установка предыдущего тестового APK и обновление новым APK с тем же signer; negative test с другим signer.
8. `artifact-report`: APK, SBOM, hashes, тестовые результаты и redacted diagnostics.

Android emulator/device tests должны быть отдельным обязательным gate перед признанием релиза готовым. Успешный GitHub build не доказывает работу на Infinix.

## 9. Постоянный ключ подписи и обновления

Предварительная GT-линия:
- applicationId: `app.buddy.myai.gt` (проверить все package-specific authorities, intents, updater и test suffix до фиксации);
- versionName: `2.3.20-GT`;
- versionCode: `217`.

Старая `app.buddy.myai` остаётся отдельной и нетронутой. Переход на другой applicationId создаёт отдельную установку и отдельный поток обновлений.

Release keystore создаётся один раз только после утверждения безопасного хранилища и независимой зашифрованной резервной копии. GitHub Actions получает keystore и пароли только через Secrets. Нельзя коммитить keystore, пароли или base64 keystore в репозиторий. Workflow должен проверять alias, PrivateKeyEntry, сертификат и ожидаемый SHA-256 fingerprint до сборки.

Текущий workflow умеет собирать APK без release key; в проверенном run #453839 signing step был skipped, поэтому release outputs не подтверждают возможность обновления установленного приложения. Debug test APK не является production update package.

## 10. Правила защиты действующей системы

- Не менять Termux, REA, туннели, runtime.env, YAML, token files, процессы и порты.
- Не останавливать и не перезапускать здоровые процессы ради диагностики.
- Не использовать текущие Termux каталоги как writable storage новой Buddy.
- Не копировать существующие токены и ключи в APK или GitHub.
- Не использовать один и тот же loopback port для параллельного теста старой и новой системы на одном телефоне.
- Для первого полноценного испытания предпочесть отдельный телефон.
- На основном телефоне запускать новую инфраструктуру только после отдельного read-only preflight и контролируемого освобождения конфликтующих портов; Termux и его данные остаются установленными.
- Любая миграция должна иметь backup, hashes, rollback plan и отдельный commit.
- Checkpoint ветки 2.3.17, 2.3.19 и Control Center не переписывать и не объединять.

## 11. Что пока НЕ подтверждено

- Текущий live-статус Termux на момент данного аудита: доступ к REA bridge в этой сессии вернул HTTP 429; это не доказательство отказа самих туннелей. Использованы свежие подтверждённые отчёты от 2026-11-24.
- Полные актуальные команды запуска и граф зависимостей всех трёх REA helper tunnels.
- Точная совместимость Tunnel Client Linux/arm64 binary с Buddy PRoot на устройстве.
- Возможность держать все нужные процессы устойчиво в одном Android foreground service при target SDK 36.
- Точный способ безопасного хранения и восстановления нового release keystore.
- Полный device-level test новой инфраструктуры.
- Подтверждение обновления GT APK поверх предыдущей подписанной GT-версии.

## 12. Критерии допуска к первому тестовому APK

Первый APK можно выдавать для отдельного устройства только после:
- завершения source/dependency audit всех компонентов;
- добавления unit и integration tests;
- зелёного CI по всем доступным jobs;
- проверки package ID, versionCode, manifest и signer;
- проверки отсутствия секретов;
- создания отдельного runtime storage и rollback;
- прохождения MCP smoke tests в изолированной среде.

Релиз нельзя объявлять готовым до реального device test и успешного upgrade test.

---

**Итог этапа 1:** Buddy уже имеет Alpine/PRoot runtime, поэтому проект следует строить как расширение существующей среды, а не как перенос Termux целиком. Рабочая Termux-инфраструктура остаётся эталоном и резервом. Следующий этап: точная инвентаризация каждого helper server и Tunnel Client profile, затем минимальный прототип supervisor в отдельной feature-ветке.


## 13. Дополнительные findings по процессам и секретам (проверка исходников)

Дополнительно проверены `Helper.java`, `BuddyService.java`, `Secrets.java`:

- `Helper.runFor()` уже запускает долгоживущие процессы внутри встроенной Linux-среды через `Linux.builder(...).start()`. Процесс обслуживается worker thread и его stdout читается в ограниченный debug log. Это подтверждает, что Buddy уже умеет запускать Linux child processes, но текущий helper рассчитан на агентские run-процессы, а не на постоянный multi-service supervisor.
- `Helper` использует общий cached thread pool; в текущем коде не найден отдельный реестр инфраструктурных процессов с устойчивой идентичностью, dependency graph, readiness gates и контролируемой политикой рестарта.
- `BuddyService` является Android AccessibilityService и запускает собственный MCP server, а при `onDestroy()` останавливает связанные с ним RemoteSession/SSH/CodexRemote и MCP server. Это не следует автоматически считать подходящей моделью для непрерывной работы сетевых туннелей. Для Runtime нужен отдельно спроектированный Android foreground service и корректная политика lifecycle для target SDK 36.
- `Secrets.java` уже применяет Android Keystore AES/GCM, храня шифротекст в приватных SharedPreferences и не помещая значения секретов в Buddy Linux. Это хорошая существующая основа для API keys, но новый Runtime должен проверить требования к backup/restore, invalidated keystore key и миграции секретов; не создавать параллельный самодельный механизм без необходимости.
- В дереве commit `d621393e...` не обнаружены каталоги `app/src/test` и `app/src/androidTest`. Следовательно, нынешние CI helper tests не покрывают Java-классы приложения, Android lifecycle, Linux process supervision или установку/обновление APK.

### Последствие для реализации

Не переносить текущий `Helper` в роль supervisor простым переименованием. Разработать отдельные классы, например `RuntimeSupervisor`, `RuntimeOrchestrator`, `RuntimeComponent` и `RuntimeHealthProbe`, с узким интерфейсом запуска поверх `Linux.builder()`. Существующий Helper, MCP server, Android Keystore secrets и Alpine installer должны остаться без регрессий. Добавить тесты отдельно для новой подсистемы, не меняя поведение текущих агентских процессов.
