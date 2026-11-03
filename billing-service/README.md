# Billing provider boundary — TAS-320

Этот Maven-модуль пока содержит **только** реактивный `PaymentProvider`, типы его ответов и управляемый `FakePaymentProvider`. Это библиотека без Spring Boot application, HTTP/gRPC endpoint, БД, Dockerfile и live-платежей. Каталог тарифов, счета, долговечные payment attempts, ledger, settlement и entitlement реализуются следующими карточками, начиная с TAS-313. Внедрение в работающий сервис будет проверяться отдельно.

Порт поддерживает hosted checkout, fetch статуса, полный refund, проверку webhook и явные capabilities. `Merchant` всегда включает account ID и `TEST`/`LIVE`. `Invoice` содержит server-owned UUID, minor units и валюту. Тот же provider idempotency key в рамках merchant возвращает прежнюю попытку; другое содержимое/operation ID даёт `IDEMPOTENCY_CONFLICT`. Повтор refund под новым ключом отвергается. Fake работает только в `TEST`; для `LIVE` возвращает `UNSUPPORTED_CAPABILITY` и не регистрируется Spring bean по умолчанию.

Fake driver позволяет выбрать исход **следующей** операции (`SUCCESS`, `DECLINE`, `TIMEOUT_ACCEPTED`, `UNKNOWN`), затем отдельно подтвердить payment/refund и получить подписанный fixture webhook. `verifyWebhook` проверяет HMAC и merchant scope и возвращает нормализованное наблюдение с суммой и исходом. Тестовый hosted URL использует `fake.invalid`; сетевой запрос не отправляется. Секрет HMAC задаётся тестом и не выводится в сообщениях ошибок.

`UNKNOWN_OUTCOME` означает, что вызывающий не может делать вывод об оплате. В fake повтор с тем же ключом позволяет прочитать ту же попытку, а `fetchPayment` и поздний webhook дают дальнейшие наблюдения. В настоящем billing решение о `PAID`, durable inbox, бизнес-дедупликация, reconciliation и права компании принадлежат другим компонентам. Fake держит состояние в памяти: его данные не переживают рестарт и не являются финансовым журналом.

Проверка: `python3 scripts/harness.py check --module billing-service`. Публичный REST и proto не меняются. [Целевые B2B-контракты](../docs/b2b/contracts/README.md) и [коммерческие решения](../docs/b2b/commercial-decisions.md) определяют будущую интеграцию, без подключения реального Stripe.
