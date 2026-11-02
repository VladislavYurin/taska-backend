# Коммерческий контур: выполненная аналитика и решения владельца

Дата исследования: 02.11.2026. Бизнес-источник — B08–B10/B17/B22 [единой спецификации](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880). O04/O08/O09 остаются идентификаторами групп, а не просьбой разработчику придумать бизнес-правило. Ниже отделены технически определённое поведение, конкретные недостающие решения и функции, которые оно блокирует. Этот документ не утверждает регистрацию продавца/merchant и не выдаёт sandbox за live.

## C01. PSP: сравнительный результат

| Критерий | Stripe | Adyen | Вывод для Taska |
| --- | --- | --- | --- |
| Hosted checkout | Checkout Sessions поддерживает redirect на страницу Stripe [1] | Pay by Link ведёт на hosted page, но документация рекомендует online integration как основной путь и не использовать Pay by Link как единственный [4] | Для первого sandbox adapter рекомендуем Stripe Checkout; конкретный merchant/live выбор требует C02. Adyen остаётся альтернативой с отдельной интеграцией Drop-in. |
| Server payment confirmation | HTTPS webhooks, подпись, async status, duplicates/order необходимо учитывать [2] | HMAC verification, отдельные ключи test/live [5] | Provider port нормализует проверенный fact; return URL не PAID, raw callback не trusted TenantContext. |
| Повторы команд | Provider idempotency поддерживается; не заменяет собственную долговечную operation запись [3] | Idempotency для API запросов, в пределах контракта провайдера [6] | Billing service хранит собственные бизнес-ключи; UNKNOWN сначала сверяется. |
| Возвраты | У provider есть pending/failed и возможность partial [7] | Конкретный adapter обязан нормализовать возврат и отдельно подтвердить финальный исход | Возможность PSP не расширяет продукт: Taska не предлагает subscription partial refund. Полный возврат ≠ отправленная команда. |
| Recurring | Встраивается через согласованный payment method/consent flow; доступность зависит от merchant/method | Token payments требуют сохранённого payment method и recurring lifecycle [8] | Taska хранит owner-specific consent; первая оплата не включает autopay. |
| Налоги, страны, методы, ставки | Доступность инструмента не подтверждает налоговый статус конкретного продавца | То же | Нужна подтверждённая commercial profile matrix. Не обещать все страны/методы на основании общей страницы PSP. |

Выбор **для технического sandbox прототипа**: Stripe hosted Checkout, собственный Taska invoice/period ledger и provider port. Это обратимое техническое решение, а не открытие реального приёма денег или утверждение договора с PSP. TAS-348 реализует test-mode adapter; guard из TAS-519 отдельно разрешает live по подтверждённой конфигурации. Автоматический налог/Adaptive Pricing/PSP subscription calendar по умолчанию не включаются: они не должны менять фиксированную конечную цену и Taska calendar rules без утверждённой настройки.

## C02. Продавец, рынки и валюта — требуется владелец (O08)

Уже решено: планируемая страна продавца Испания; B2B+B2C; базовая цена 20 EUR за месяц **включая** применимые налоги; прямой банк только EUR; USD через PSP по immutable FX snapshot. Регистрация ещё не подтверждена. Нельзя придумать seller name, tax ID, адрес, счёт и merchant account.

Владелец должен подтвердить зарегистрированного продавца/форму/налоговую регистрацию, банк и PSP (предлагается Stripe), стартовые страны и B2B/B2C предложение. Для каждой доступной комбинации конфигурация хранит sellerProfileVersion, market, buyerType, currency, paymentMethod, taxRuleId, documentTemplateVersion, refundPolicyVersion и enabled. Неподтверждённая комбинация скрыта/отклоняется до checkout, сумма 20 EUR не повышается автоматически.

FX: `FxQuoteProvider` возвращает EUR→USD decimal rate, источник, quotedAt, validUntil и source version. Неизвестный/просроченный источник блокирует **новые USD invoices**, EUR остаётся явной альтернативой. ECB указывает, что его reference rates предназначены для информации и не рекомендуются для транзакций [9]; поэтому ECB не назначен скрытым payment default. Предложение владельцу: запустить EUR, а USD включить после выбора коммерческого FX source и max quote age. Это изменение доступности валюты требует его ответа, пока target USD port остаётся в контракте.

## C03. Частичный внешний chargeback — требуется владелец (O04)

Согласовано: внешний частичный факт не игнорировать; сохранить всю сумму и связь с original payment, включить NEEDS_REVIEW/READ_ONLY, остановить новые autopay. Продуктового partial refund нет. Не определён **окончательный бизнес-исход** ручного разбора после потери части денег.

Вопрос владельцу: после подтверждённого частичного chargeback и закрытия спора разрешаем GLOBAL_ADMIN сохранить остаток прежнего оплаченного периода без доплаты, либо снимаем это покрытие и требуем новую полную оплату? Альтернатива может быть описана владельцем. До ответа endpoint не принимает произвольный `restorePaid=true`; разрешены регистрация evidence, сверка и чтение case, финальное policy action недоступно. Ни один вариант не запускает дополнительный refund уже возвращённой PSP суммы.

## C04. Банковский разбор: технические решения приняты (O04)

| Случай | Реализация без дополнительного бизнес-решения |
| --- | --- |
| Неизвестный payer/назначение | UNALLOCATED ledger + OPEN/WAITING_EVIDENCE case; ручная привязка по проверенной выписке и invoice, никакой автоактивации по email/похожей сумме. |
| Дата без времени у deadline | Сохранять precision DAY и диапазон возможного receivedAt. Если диапазон пересекает deadline, запросить банковское подтверждение в case. Не объявлять late/timely автоматически. |
| Подтверждение WIRE return | Уникальная исходящая bank entry/reference, исходный payer, amount/currency, дата и evidence hash. Решение/поручение/отправка отдельно от CONFIRMED. |
| PSP и WIRE одновременно | Lock invoice/allocations, уникальный coverage effect; второй источник сохраняется UNALLOCATED. Возврат исходит только из остатка соответствующего payment source. |
| Refund + chargeback | Сначала authoritative reconciliation, проверить reserved и confirmed returned totals; один денежный intent на остаток. Новое подтверждение внешнего факта не вызывает дополнительного расхода. |
| Недоплата/late first/late renewal/excess | Точные алгоритмы [L06](state-machines.md#l06-нормализованный-settlement-psp-и-wire) по D235–D239/D246/D247. Платёж 25 на invoice 20: покрытие 20, возврат всего излишка 5. |

Срок операторской обработки/возврата и плательщик **исходящей** комиссии ещё влияют на обещание клиенту и требуют C05. Уже принятое правило о комиссии **входящего** перевода за счёт покупателя не меняется. Срок банковского зачисления не выдаётся за гарантированный операторский SLA.

## C05. Возврат: срок и исходящая комиссия — требуется владелец (O04)

Нужно подтвердить операционный срок рассмотрения и отправки возврата и кто оплачивает комиссию исходящего банковского перевода. Предложение: оператор принимает решение/отправляет перевод в течение 5 рабочих дней после получения достаточных банковских данных, комиссия за счёт Taska; время межбанковского зачисления указывать отдельно. Это предложение, **не принятое** бизнес-правило. До ответа в коде конфигурация `returnReviewBusinessDays`/`outboundFeeBearer` unset, клиентский API не обещает дату и не удерживает комиссию из principal. Сам ledger и очередь разбирательств могут быть реализованы.

## C06. Хранение и документы после удаления — требуется владелец (O09)

Физическое удаление финансовых доказательств, backups и FREE-used marker требует подтверждённых сроков/оснований; минимизация и исключения из удаления не дают универсального «хранить всё N лет». GDPR Art.5(1)(e), 6 и 17 различают срок необходимости, основание обработки и исключения удаления [10]. Это основание разделить policy по данным, а не подставить юридический срок в код.

| Набор | Уже определённое поведение системы | Подтвердить перед live |
| --- | --- | --- |
| Аккаунт, аватар, активные memberships/grants | Логическое прекращение доступа сразу; PII очищается в повторяемом offboarding; deleted author без старых snapshots | Срок завершения физической очистки и обязательные исключения |
| Tenant рабочие данные/файлы/export | Tombstone, закрытие API, manifest cleanup; не восстанавливать из backup как живую компанию | Retention удалённых объектов/backup, сроки purge |
| Invoice/ledger/bank evidence | Immutable, отдельный защищённый архив, не обычный tenant read; personal account deletion не переписывает финансовый факт | Юридическое основание/срок, минимальный состав, legal hold и уполномоченный доступ |
| Security/admin audit | Минимальные identifiers/reasons, без raw tokens и приватного текста | Срок/основание и владелец доступа |
| FREE-used marker | Минимальный HMAC подтверждённого email + used tenant; не даёт профиль или доступ | Основание/срок и что делать при обязательном удалении marker, сохраняющем lifetime бизнес-запрет |

Policy schema: dataset, jurisdiction, purpose, legalBasis, retentionStartEvent, duration/approved permanent basis, action ERASE/ANONYMIZE/RESTRICT, hold rule, approver, effectiveAt, policyVersion. Отсутствующая политика не превращается в молчаливое бессрочное хранение: live readiness не пройдена, задача `TAS-520` реализует проверку и механизм, а не сама определяет закон.

Предложение по доступу после offboarding: финансовые документы и незавершённый refund обслуживаются через обращение к оператору с проверкой личности/полномочий, без восстановления удалённого аккаунта. Требуется подтверждение владельца и выбранной процедуры идентификации. Рабочий контент компании этим путём не выдаётся.

## Граница готовности разработки

TAS-518/519/520 преобразованы в **реализацию** case workflow, commercial readiness configuration и retention engine. Исследование выполнено здесь; перечисленные C02/C03/C05/C06 — пакет решений владельца в спецификации. При unset policy технический результат конкретен: save fact, queue review, disallow unsupported final action/live mode. Разработчик не должен выбирать PSP merchant/legal срок/исход спора самостоятельно. После ответа владельца меняется версия policy и затронутые acceptance, а не начинается новая инвентаризация проекта.

## Первичные источники

1. [Stripe Checkout](https://docs.stripe.com/payments/checkout).
2. [Stripe webhooks](https://docs.stripe.com/webhooks?locale=en-GB).
3. [Stripe API idempotency](https://docs.stripe.com/api/idempotent_requests).
4. [Adyen Pay by Link](https://docs.adyen.com/unified-commerce/pay-by-link).
5. [Adyen HMAC verification](https://docs.adyen.com/development-resources/webhooks/secure-webhooks/verify-hmac-signatures).
6. [Adyen API idempotency](https://docs.adyen.com/development-resources/api-idempotency/).
7. [Stripe refunds](https://docs.stripe.com/refunds).
8. [Adyen token payments](https://docs.adyen.com/online-payments/tokenization/make-token-payments).
9. [ECB reference exchange rates](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html).
10. [GDPR, Regulation (EU) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng).
