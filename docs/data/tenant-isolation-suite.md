# Общая проверка tenant isolation — TAS-309

Suite `TenantIsolationContractIntegrationTest` связывает executor TAS-307 с настоящими SQL templates TAS-308. Это проверки общей PostgreSQL/R2DBC инфраструктуры; сервисные repositories, project ACL, HTTP/gRPC, jobs/events/files и полный релизный gate остаются в доменных задачах и TAS-377.

## Фикстура и повторное использование

`TenantIsolationFixture` запускает собственный PostgreSQL16 Testcontainers. Bootstrap выполняется оператором, пример схемы — LOGIN deployer с явным SET ROLE migrator, затем обе таблицы защищаются shipped protect-table и проверяются shipped audit. Запросы suite используют отдельный LOGIN `taska_project_app` с временным случайным паролем. `session_user=current_user=taska_project_app` проверяется явно; superuser не используется для тестируемых бизнес-запросов.

Оператор используется только для подготовки данных, ANALYZE и наблюдения pg_stat_activity. reset удаляет исключительно таблицы своего контейнера. Один проект и три задачи на каждую из A/B имеют одинаковый project_key и номера задач 1..3, разные tenant/project/id. FK `(tenant_id,project_id)` ссылается на составной ключ проекта. Это позволяет обнаружить утечку даже при совпадающих бизнес-ключах. Контекст создаётся test-only helper в пакете verifier: production API формирования доверенного контекста не расширяется.

Fixture экспортируется в отдельном `common-lib-1.0-SNAPSHOT-tests.jar` (только `TenantIsolationFixture*.class`, без executable tests). Сервис может подключить её test dependency:

```xml
<dependency>
  <groupId>ru.taska</groupId><artifactId>common-lib</artifactId>
  <version>${project.version}</version><type>test-jar</type><scope>test</scope>
</dependency>
```

Обычная dependency common-lib также нужна для executor и SQL resources. Test dependencies не транзитивны: потребителю нужны JUnit Jupiter, Testcontainers PostgreSQL, r2dbc-pool, reactor-test и PostgreSQL driver (см. common-lib/pom.xml). Собирать reactor через `verify`/`install`, чтобы classifier был создан на package phase. Подключение к существующим сервисам не делается автоматически.

В сервисном тесте `try (var fixture = new TenantIsolationFixture(1))` создаёт изолированный стенд, `reset(3)` наполняет его, `as(A, fixture.executor.execute(db -> ...))` запускает операцию под доверенным test context. При адаптации к реальной схеме сервиса применяйте его собственные новые migrations и repositories; общая fixture использует только учебные projects/issues TAS-308. Не подключать её к стенду/production и не заменять ею доменные проверки. Класс fixture присутствует только в test classifier и не попадает в production jar.

## Проверяемые сценарии

- JOIN, EXISTS, aggregate/count, window functions: оба tenant получают ровно свои строки/счётчики; явный foreign predicate возвращает пустой результат. Запросы без tenant predicate намеренно проверяют резервную защиту RLS; production SQL обязан содержать явный tenant predicate.
- INSERT, INSERT SELECT, UPDATE/DELETE и writable CTE с RETURNING: собственные изменения успешны, чужие строки недоступны. WITH CHECK отвергает чужой INSERT/смену tenant (42501), composite FK — ссылку на чужой проект (23503). Состояние обеих компаний проверяется после отказов.
- R2DBC Statement.add с bind-параметрами: пакет с foreign второй записью полностью откатывается, собственный пакет возвращает только A.
- Nested execute работает в одной transaction/connection; смена компании в nested scope отменяет все записи, даже если ошибка поймана.
- Повтор снаружи execute после SQL error 22012 создаёт новую transaction, первая запись откатывается, повтор с тем же id успешно создаёт ровно одну запись. Context следующего B не содержит A.
- Отмена после успешного batch во время pg_sleep: наблюдается реально запущенный SQL, затем выполняется cancellation, проверяется rollback, чистый slot и освобождение PostgreSQL locks. Дополнительные случаи cancellation/error/commit failure уже находятся в `TenantTransactionExecutorIntegrationTest` и `TenantTransactionExecutorFailureTest`.
- Pool maxSize=1 детерминированно проверяет A/B reuse и отсутствие transaction-local tenant/actor вне scope. Pool maxSize=2 запускает 24 A/B операции с concurrency=2; latch требует реального одновременного владения двумя разными backend PID. После операций обе physical sessions удерживаются одновременно и проверяются на отсутствие tenant/actor.

## Планы и объём данных

Плановый сценарий создаёт 4 000 issues на компанию (8 000 всего), выполняет ANALYZE, затем под app role для каждой компании запускает `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` выборки по tenant/project и диапазону issue_number 10..20. Seqscan не отключается, planner settings не форсируются. Ожидается индексный доступ с tenant-leading ключом и ровно 11 фактическими строками. JSON plans сохраняются в ignored `common-lib/target/tenant-isolation-plans/`; timing не является порогом CI. Это воспроизводимый smoke для selective query, а не гарантия планов всех сервисных запросов или production latency. Доменные задачи фиксируют свои планы на реалистичном объёме.

## Запуск и CI

```sh
./mvnw -B -ntp -pl common-lib -am verify
python3 scripts/harness.py check --module common-lib
# Только suite во время разработки:
./mvnw -B -ntp -pl common-lib -am test \
  -Dtest=TenantIsolationContractIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Нужен работающий Docker daemon. Отсутствие Docker — ошибка, тесты не превращаются в skip. Suite входит в стандартный Surefire, а manifest common-lib.verify требует его отдельный свежий ненулевой XML report сверх общей wildcard-проверки. Поэтому удалённый/невыбранный/skipped/упавший suite ломает check в PR CI. Дополнительного ручного workflow не требуется.
