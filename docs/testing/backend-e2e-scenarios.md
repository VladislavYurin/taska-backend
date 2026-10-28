# Сценарии E2E публичного backend API

Задача: [TAS-439](https://jira.ozero.dev/browse/TAS-439). Документ содержит повторяемые сценарии и ожидаемое поведение, а не результаты прогонов. Источник HTTP-контракта — [`openapi.yml`](../../api-gateway/src/main/resources/static/openapi.yml), опубликованный также как `/openapi.yml` и `/v3/api-docs`. Swagger UI — `${BASE}/swagger-ui.html`. Те же запросы можно отправлять HTTP-клиентом: способ отправки не меняет сценарий.

## Подготовка и обозначения

- `BASE` — origin согласованного тестового gateway без завершающего `/` и без `/api/v1`, например `https://api.taska.ozero.dev`. Не выполнять сценарии на рабочей системе с чужими данными. Все маршруты запросов ниже полные относительно origin: итоговый URL — `${BASE}` + маршрут, например `${BASE}/api/v1/projects`. Плейсхолдеры `{P}`, `{id}`, `{W}` и другие перед отправкой заменить реальными UUID/ключами из текущего сценария; параметры query кодировать как URL. Для PUT/GET presigned URL использовать целиком URL из ответа storage, без добавления BASE или API-префикса.
- Для каждого запуска выбрать уникальный `RUN`; ключи проектов `E2E<RUN>A`, `E2E<RUN>B`, префикс имён и текстов `E2E <RUN>`. Сохранять UUID только в отдельном журнале прогона вне Git.
- `A` — активный пользователь, создатель проекта P и его ADMIN. `M` — активный MEMBER P. `V` — активный VIEWER P. `O` — активный пользователь без членства в P, создатель Q. `G` — GLOBAL_ADMIN; его глобальная роль сама по себе не даёт доступа к P. `I` — отдельный приглашённый тестовый пользователь. Для проверок изменения имени, аватара, настроек, блокировки и lockout нужны выделенные тестовые учётные записи.
- Для позитивной активации и регистрации нужны контролируемый почтовый ящик/SMTP-приёмник и доступ к письму. `deliveryQueued=true` означает постановку в очередь, а не доставку. При отсутствии почты полный цикл активации не считается выполненным.
- Нужны S3 endpoint, доступный клиенту по возвращённым presigned URL, и небольшие подготовленные PNG/PDF. Для Telegram — отдельный тестовый бот/чат и действующий webhook; для OAuth — настроенные приложения провайдеров и тестовые аккаунты.
- Не помещать пароли, JWT/refresh/PAT, invitation/OAuth/Telegram tokens и presigned URL в Git, Jira или скриншоты. В шаблонах ниже используются только `${PLACEHOLDER}`. Не читать секреты через Data/Kafka ради обхода почтового или OAuth-сценария.
- Защищённые запросы: `Authorization: Bearer ${ACCESS}`; JSON: `Content-Type: application/json`. Перед каждой независимой командой создавать `X-Request-Id`; сохранять его для разбора ошибки. Для повторяемых команд workflow использовать один ключ для одного неизменяемого запроса. `Idempotency-Key` создания задачи — отдельный UUID. Для PATCH задачи `If-Match` — её текущая положительная версия, число либо ETag в кавычках.
- Сначала выполнить E2E-01–08, затем функциональные сценарии на собственных проектах; удаление и архивирование — в конце. В негативном сценарии сравнивать состояние до и после: одного правильного HTTP-кода недостаточно.
- Типовые ошибки: отсутствие/невалидная авторизация — 401; отсутствие достаточной роли — 403; невалидное тело/параметры — 400; неизвестный объект после проверки доступа — 404; устаревшая версия задачи/workflow — 409. Проверять также `code`, `details.reason` и `X-Request-Id`. Не заменять конкретное ожидание произвольным «любой 4xx».
- Асинхронные эффекты проверять ограниченным опросом с заранее выбранным таймаутом, например 30 секунд для inbox; при истечении срока фиксировать фактическое состояние вне Git. Не повторять запись после timeout вслепую.

## E2E-01. Публичный контракт и словари

**Условия:** контракт доступен по BASE; для защищённого `/api/v1/meta` есть JWT либо read PAT активного пользователя. Swagger и auth methods проверять без авторизации.

1. Открыть Swagger; получить `/openapi.yml` и `/v3/api-docs`. Сравнить методы, paths, обязательные параметры, схемы и ответы; JSON является представлением того же авторитетного YAML. `/v3/api-docs/generated` не использовать как замену контракту.
2. Выполнить `GET /api/v1/meta` с JWT/read PAT: 200; без авторизации — 401. В успешном ответе непустые словари приоритетов, типов, связей, статусов пользователей и уведомлений; стабильные коды и английские имена. Набор типов в meta — начальный посев, а не текущий реестр P. `actionChannels` содержит пары code/name UI→UI, API_TOKEN→API, MCP→MCP, по одной на канал; название/UUID личного токена не раскрывается.
3. Повторить с `If-None-Match` из ETag: 304 без тела. Неверный ETag возвращает актуальный 200.
4. `GET /api/v1/auth/methods`: 200 с фактическим signup flag и настроенными провайдерами, без ключей/секретов, `Cache-Control: no-store`.

5. На изолированной совместимой фикстуре будущих ответов подставить неизвестные строковые значения downstream: `kind` в `POST /api/v1/auth/invitations/preview`, `status` и `signup.provider` в `POST /api/v1/auth/oauth/exchange`. Gateway сохраняет каждое значение как строку, без 500 и подмены на UI/известный provider. Проверка относится к открытым полям ответов, не разрешает произвольный provider во входном path или каталоге методов. На общем стенде не подменять внутренние сервисы.

**Очистка:** не требуется.

## E2E-02. Вход, профиль и ротация сессии

**Условия:** отдельная активная учётная запись с известным паролем.

1. `POST /api/v1/auth/login` с `{"email":"${EMAIL}","password":"${PASSWORD}"}`: 200, непустые access/refresh, положительный `expiresIn`.
2. С access выполнить `GET /api/v1/users/me`: 200, UUID и email правильного пользователя, displayName и global role; пароля и refresh нет. Без Bearer/с повреждённым Bearer: 401.
3. `POST /api/v1/auth/refresh` с текущим refresh: 200, новая пара. Старый refresh повторно не создаёт сессию (401); новый access читает `/api/v1/users/me`. Пустой refresh — ошибка валидации/аутентификации по контракту, без 500.
4. На выделенной учётной записи проверить неверный пароль и неизвестный email: одинаковая ошибка аутентификации без раскрытия существования. Проверку порога lockout выполнять только в E2E-26, не блокировать общие аккаунты.
5. `PATCH /api/v1/users/me` с `{"displayName":"  E2E Имя 🧪  "}`: 200 с нормализованным именем; последующий me, каталог и новые записи автора используют это имя, UUID не меняется.
6. Пустое имя, null, перевод строки/управляющий символ, более 128 Unicode code points и строки только из невидимых форматных символов U+200B, U+FEFF, U+2060 либо их смеси с пробелами: 400 INVALID_ARGUMENT, `details.reason=DISPLAY_NAME_INVALID`; прежнее имя сохраняется. В JSON передавать `\u200b`, `\ufeff`, `\u2060`, чтобы намерение проверки было видно. Проверить граничные 1 и 128 code points и допустимое видимое имя с emoji; правило требует хотя бы один видимый символ, не запрещает все форматные символы внутри видимого имени.

**Очистка:** вернуть исходное имя; секреты сессии удалить из внешнего журнала/памяти клиента после прогона.

## E2E-03. Приглашение и активация

**Условия:** G, новый контролируемый email I и почтовый приёмник.

1. Под G `POST /api/v1/auth/invitations` с `{"email":"${INVITEE_EMAIL}","displayName":"E2E Invited"}`: 201, UUID I, INVITED, `deliveryQueued=true`; нет raw token. Под обычным пользователем тот же вызов — 403 без создания аккаунта.
2. Повторить с пробелами/другим регистром того же email без resend: 200, тот же UUID/expiry, `deliveryQueued=false`; нового письма нет. Повторная доставка/поиск не меняют пароль и членство.
3. Получить ссылку из тестового письма. `POST /api/v1/auth/invitations/preview` с `{"token":"${INVITE}"}`: 200, нужный email/displayName; повторный preview не потребляет ссылку. `kind` обозначает происхождение аккаунта createdVia, а не вид ссылки: приглашённый администратором пользователь сохраняет INVITATION и после запроса email-регистрации. Неверный токен — 401 без чужих данных.
4. Попытаться принять с недопустимым именем либо слабым/превышающим 72 UTF-8 байта паролем: 400; валидный токен остаётся пригодным. Пароль передавать только по HTTPS и не логировать.
5. `POST /api/v1/auth/invitations/accept` с token, допустимым newPassword и displayName: 204 без access/refresh. Затем login: 200; I стал ACTIVE USER, имя нормализовано, не получил чужие проекты.
6. Повтор accept/preview использованного, просроченного или отозванного токена: одинаковый отказ невалидного токена; нет второй активации. Две одновременные активации имеют ровно одного победителя.
7. На другом выделенном INVITED проверить `resend:true`: в cooldown — 429 с Retry-After и `details.retryAfterSeconds`, действующий token сохранён; после cooldown — 200 с новым письмом, прежняя ссылка не работает.
8. Приглашение ACTIVE/LOCKED/BLOCKED email: 409, профиль/status/password/membership не меняются. Невалидный email/имя — 400 без аккаунта.

9. Для отдельного выделенного INVITED I2 получить новую действующую ссылку и последовательно отправить в `POST /api/v1/auth/invitations/accept` имена только из U+200B/U+FEFF/U+2060 и пробелов: каждый ответ 400 INVALID_ARGUMENT / DISPLAY_NAME_INVALID, аккаунт остаётся INVITED, пароль не установлен, token не потреблён. Затем принять **эту же** ссылку с допустимым именем и паролем: 204 и успешный login. Для невалидного имени в `POST /api/v1/auth/invitations` аккаунт и письмо не создаются.

**Очистка:** аккаунты I/I2 — выделенные фикстуры, учитывать отсутствие публичного удаления пользователей; ненужную приглашённую фикстуру блокировать по согласованной политике тестового стенда. Не удалять её напрямую из БД.

## E2E-04. Самостоятельная email-регистрация

**Условия:** отдельный контролируемый email и почта; фактический flag из E2E-01.

1. При выключенной регистрации `POST /api/v1/auth/signup` с валидными email/displayName: 403; аккаунт/письмо не создаются. Эта проверка не заменяет полный цикл при включённой регистрации.
2. При включённой — 202 без раскрытия состояния адреса. Повторить для свободного, INVITED, ACTIVE, LOCKED и BLOCKED тестовых адресов вне rate limit: внешне один и тот же пустой 202; BLOCKED не получает письмо; ACTIVE/LOCKED получают только напоминание о входе, не новый пароль.
3. Для свободного адреса получить письмо и принять через invitation accept, затем войти; до активации пользователь отсутствует в каталоге и не может стать участником проекта.
4. Существующий INVITED сохраняет своё прежнее имя и правила resend; свежий административный инвайт не раскрывается и не заменяется немедленно.
5. Невалидные email/имя — 400. Повтор в пределах email/IP лимита — 429 с Retry-After; аккаунт/ссылка не дублируются. Полный IP-limit проверять на изолированном окружении, не исчерпывая общий budget стенда.

6. При включённой регистрации имена только из невидимых форматных символов дают 400 DISPLAY_NAME_INVALID до создания аккаунта/письма. Проверку INVITED из шага 4 дополнить preview текущей ссылки: `kind=INVITATION` остаётся происхождением аккаунта, даже если запрос пришёл через email signup.

**Очистка:** как для I; незавершённая регистрация истекает по настройкам сервера, активную фикстуру учитывать отдельно.

## E2E-05. Социальный вход и регистрация

**Условия:** настроенные Google/GitHub/Apple, контролируемые аккаунты и браузер с cookie. Если провайдер выключен, проверить фиксированный отказ и не считать позитивный путь выполненным.

1. `GET /api/v1/auth/oauth/{provider}/start`: 302 на разрешённый провайдер, свежий state, PKCE S256 для Google/GitHub; Secure HttpOnly SameSite=None cookie `__Host-taska_oauth_<state>`, Path=/, без Domain. Не следовать на случайные адреса из входных параметров.
2. Пройти согласие провайдера; Google/GitHub возвращаются через `GET /api/v1/auth/oauth/{provider}/callback`, Apple — через `POST /api/v1/auth/oauth/{provider}/callback` с form-urlencoded body. 302 ведёт только на настроенный UI с одноразовым exchange code либо фиксированным reason; no-store/no-referrer, без provider tokens.
3. `POST /api/v1/auth/oauth/exchange` с code: 200, сессия существующего пользователя либо SIGNUP_REQUIRED и ограниченный signup grant. Повтор/expiry code не создаёт вторую сессию.
4. Для нового пользователя `POST /api/v1/auth/oauth/signup` с signupToken/displayName: 200, ACTIVE USER без пароля/чужого членства. Ошибка имени — 400 DISPLAY_NAME_INVALID до потребления grant; исправленное имя ещё можно отправить. Повтор уже использованного grant отклоняется.
5. Existing email сохраняет UUID, имя и существующий пароль; INVITED активируется с отзывом старых invitation tokens. BLOCKED/неистекший LOCKED сессию не получают. При выключенном signup новый пользователь не создаётся.
6. Callback без/с чужой cookie, повторный state, подмена provider, неверный method, отказ пользователя, неподтверждённый email: контролируемый фиксированный reason, без stacktrace/provider response и без потребления чужого легитимного state.
7. IP budget общий для start/callback/exchange/signup и провайдеров; исчерпывать его только изолированно. На `GET /api/v1/auth/oauth/{provider}/start`, `GET /api/v1/auth/oauth/{provider}/callback` и Apple `POST /api/v1/auth/oauth/{provider}/callback` отказ лимита — **302**, Location на UI origin `/auth/callback?error=RATE_LIMITED&requestId=...&retryAfter=...`, retryAfter — положительные секунды. На JSON `POST /api/v1/auth/oauth/exchange` и `POST /api/v1/auth/oauth/signup` — **429 RESOURCE_EXHAUSTED**, `Retry-After` и строковый `details.retryAfterSeconds` согласованы. Квота не потребляет state/code/signupToken; callback не удаляет browser-binding cookie. После освобождения тестового budget та же легитимная привязка/код пригодна. Произвольный X-Forwarded-For не обходит лимит.

8. Отключённый/недоступный provider и недоступный auth-service на навигационных start/callback возвращают 302 `PROVIDER_UNAVAILABLE`, а не сырой JSON на API origin. Остальные фиксированные причины — PROVIDER_CANCELLED, STATE_INVALID, EMAIL_NOT_VERIFIED, ACCOUNT_BLOCKED, ACCOUNT_LOCKED, SIGNUP_DISABLED. Во **всех ошибочных** Location есть requestId; он совпадает с `X-Request-Id` ответа и находится в gateway log. Повторить без входящего `X-Request-Id`: генерируется один UUID для ответа/Location/лога. При UI base с путём callback всё равно лежит в корне UI origin. Ответы no-store/no-referrer, внутренние подробности не отражаются в URL.
9. Для контроля расхода grants на отдельном аккаунте получить действующий code, затем временно заблокировать аккаунт LOCKED до известного `${UNTIL}` перед `POST /api/v1/auth/oauth/exchange`: 403 PERMISSION_DENIED, reason ACCOUNT_LOCKED и `details.lockedUntil` как UTC date-time. Повтор этого code — 401 STATE_INVALID. Аналогично получить signupToken нового аккаунта, подготовить совпадающий LOCKED аккаунт в контролируемой фикстуре и вызвать `POST /api/v1/auth/oauth/signup`: тот же 403/lockedUntil, повтор token — 401. Сессии/связи не создаются. BLOCKED и SIGNUP_DISABLED — 403 с собственной причиной; PROVIDER_UNAVAILABLE после успешного claim — 503. Не блокировать общего пользователя.
10. На отдельном действующем signupToken имена U+200B/U+FEFF/U+2060 и их смесь с пробелами дают 400 DISPLAY_NAME_INVALID **до** claim; корректное видимое имя с тем же token завершается успешно. Отличать это от отказов аккаунта/provider после claim из шага 9 и квоты до claim из шага 7. Во всех 429 публичного API Retry-After описан строкой; для OAuth JSON проверить совпадение с details.retryAfterSeconds.

**Очистка:** завершить/закрыть только собственные OAuth flows; не менять настройки приложений провайдеров.

## E2E-06. PAT, scope и канал действий

**Условия:** JWT A; P создан в E2E-08. Уложиться в policy выпуска, не исчерпать budget общих пользователей.

1. `GET /api/v1/users/me/access-tokens`: 200 со списком и policy. `POST /api/v1/users/me/access-tokens` с `{"name":"E2E read","scopes":["read"],"expiresInDays":1}`: 201; secret показывается ровно в ответе выпуска. Выпустить второй PAT read/write. `GET` не возвращает secret/hash, только безопасные метаданные.
2. Read PAT читает свой me, проекты, задачу, board и комментарии, но создание/изменение получает 403 `INSUFFICIENT_SCOPE`, requiredScope=write. Write без read, пустой список, неизвестный/повторный scope, недопустимый срок/имя — 400 без выпуска.
3. Read/write PAT создаёт отдельную задачу и комментарий в P. История/комментарий содержат канал API_TOKEN, без имени/UUID PAT; CRUD ограничен теми же project ACL. Произвольные клиентские MCP marker headers не превращают прямой PAT в доверенный MCP.
4. Любой PAT получает 403 SESSION_REQUIRED на управлении PAT, admin/readonly и иных session-only операциях; GLOBAL_ADMIN PAT также не обходит этот запрет.
5. Под JWT `DELETE /api/v1/users/me/access-tokens/{tokenId}`: 204; следующий запрос отозванного PAT — 401, не положительный cache. Чужой tokenId не отзывает чужой токен и не раскрывает secret.
6. Выпуск сверх фактической policy/budget на отдельном пользователе — предусмотренный отказ; rate limit — 429 с Retry-After и retryAfterSeconds. Отзыв остаётся доступным.

**Очистка:** отозвать все PAT с меткой RUN, удалить созданные ими тестовые задачи; не отзывать существующие токены.

## E2E-07. Каталог пользователей

**Условия:** A/M с членством в проекте, V, O и выделенный I.

1. `GET /api/v1/users?query=${PARTIAL_NAME}&page=0&pageSize=1`: 200 с items/hasMore; поля только id/displayName/email/nullable avatarUrl. Поиск по email и имени нечувствителен к регистру, пробелы по краям обрезаны.
2. На тестовых пользователях проверить порядок lower(displayName), lower(email), id; продолжить page до пустой страницы. Пустой результат — 200, items=[]; `%`, `_`, `!` ищутся буквально, не превращаются в wildcard.
3. ACTIVE/INVITED/временно LOCKED видны, BLOCKED и неподтверждённый SIGNUP скрыты. Само появление в каталоге не даёт права на P или назначение.
4. Query <3/>100 code points, page вне 0..1000, pageSize вне 1..50: 400. Ответы no-store. При включённом signup обычный пользователь без членства хотя бы в одном проекте получает 403 PERMISSION_DENIED с `details.reason=PROJECT_MEMBERSHIP_REQUIRED`; GLOBAL_ADMIN получает 200. После добавления пользователя в свой проект запрос становится доступен; при выключенном signup дополнительное требование членства не применяется.
5. Для avatar недоступного storage отсутствие URL конкретной строки не срывает весь каталог; такую fault-injection проверку делать на изолированной среде.

**Очистка:** не требуется.

## E2E-08. Жизненный цикл проекта и участники

**Условия:** A, M, V, O; уникальные ключи P/Q.

1. Под A `POST /api/v1/projects` с `{"projectKey":"${P_KEY}","name":"E2E ${RUN}","description":"Scenario fixture","color":"#0052CC"}`: 201 с UUID P, A становится ADMIN. Повтор ключа — конфликт без второго проекта; недопустимый ключ/пустое имя/не-HEX цвет — 400.
2. `GET /api/v1/projects`, `GET /api/v1/projects/{P}`, `GET /api/v1/projects/{P}/members`, `GET /api/v1/projects/{P}/context`, `GET /api/v1/project-stats`: 200 с согласованными P, ролью, реестром типов, workflow, участниками, метками и начальными счётчиками. Списки содержат только доступные проекты, чужие не утекают в stats/context.
3. `PATCH /api/v1/projects/{P}` с одним name: имя обновлено, остальные поля сохранены. Явные null description/color очищают, blank description нормализуется в null; null name — 400. projectKey не меняется.
4. `POST /api/v1/projects/{P}/members` для M MEMBER и V VIEWER: 201; `GET /api/v1/projects/{P}/members` включает правильные UUID/профили/роли. Добавление неизвестного/BLOCKED/неактивированного SIGNUP пользователя не создаёт пригодного членства.
5. `PATCH /api/v1/projects/{P}/members/{M}` меняет MEMBER→VIEWER→MEMBER: 200, следующий запрос сразу использует новые права. `DELETE /api/v1/projects/{P}/members/{M}` выделенного участника: 204, следующие обращения к P запрещены.
6. Попытка удалить/понизить последнего ADMIN отклоняется; A остаётся ADMIN. MEMBER не меняет имя проекта, реестр/workflow и состав участников. GLOBAL_ADMIN без членства не читает P только за счёт глобальной роли.
7. O создаёт Q; P и Q не смешиваются в context, issue labels, links, stats и поиске. Неверный projectId — 400, несуществующий доступный объект — 404; чужой P не выдаёт данные при отрицательном ACL.

**Очистка:** P/Q архивировать только после зависимых сценариев; собственных участников удалить до архивации, сохранив одного ADMIN.

## E2E-09. Реестр типов, оформление и порядок

**Условия:** ADMIN P; начальный реестр прочитан.

1. `GET /api/v1/projects/{P}/issue-types`: 200, для нового P TASK/STORY square, BUG circle, без icon. Все коды уникальны, порядок sortOrder/code; display name/code различаются.
2. `POST /api/v1/projects/{P}/issue-types` с `{"code":"E2E_CUSTOM","name":"E2E Custom","color":"teal","shape":"diamond","icon":"flag","sortOrder":5}`: 201, плоский DTO с generation/workflowId. GET/context/board возвращают актуальный реестр.
3. `PATCH /api/v1/projects/{P}/issue-types/E2E_CUSTOM` с name/color/shape и `icon:null`: 200, имя/оформление меняются, icon очищен; пропущенные поля сохраняются, code не меняется.
4. `PUT /api/v1/projects/{P}/issue-types/order` с `{"codes":[...все текущие коды в новом порядке...]}`: 200, порядок сохраняется на последующих чтениях; одновременно применённое несовместимое изменение — 409 без частичного порядка.
5. Повторный code, неверный формат code, неизвестный color/shape/icon, null обязательного поля, лишнее поле, повтор/пропуск code в order: отказ валидации/конфликта без повреждения реестра. MEMBER/VIEWER не меняют реестр (403).
6. Удалить пустой отдельный тип через `DELETE /api/v1/projects/{P}/issue-types/{code}`: 204; последний оставшийся тип удалить нельзя. Полный перенос непустого типа — E2E-13.

7. Поле `generation` в каждом DTO реестра обязательно, UUID и не null; успешный POST дополнительно возвращает workflowId. В изолированной legacy фикстуре с пустым реестром `POST /api/v1/projects/{P}/issue-types` без workflowId — 400 FAILED_PRECONDITION / ISSUE_TYPE_WORKFLOW_REQUIRED, без intent/binding/типа. Та же команда с **новым** X-Request-Id и явным UUID собственного либо общего default workflow — 201; появляется первый тип с generation/workflowId, последующее создание задачи выбирает этот workflow. Пустой реестр готовить только на своей копии стека, не удалением последнего типа через обход защиты общего стенда.

**Очистка:** убрать только созданные RUN типы после удаления/переноса их задач.

## E2E-10. Собственный workflow и граф

**Условия:** ADMIN P; тип E2E_CUSTOM, без задач.

1. `GET /api/v1/projects/{P}/workflows`: 200, текущие binding/версии; общий default присутствует с shared=true даже без привязанных типов. mainWorkflowId указывает workflow первого типа реестра по sortOrder/code; при пустом реестре null, default остаётся в списке. `GET /api/v1/projects/{P}/workflow?issueType=E2E_CUSTOM`: 200, эффективный граф типа.
2. `POST /api/v1/projects/{P}/workflows` с уникальным X-Request-Id и телом ниже: 201, свой UUID W, version=1, E2E_CUSTOM привязан к W. Default не изменён.

```json
{
  "name": "E2E graph",
  "issueTypes": ["E2E_CUSTOM"],
  "statuses": [
    {"statusKey":"TODO","name":"To do","category":"TODO","sortOrder":10},
    {"statusKey":"REVIEW","name":"Review","category":"IN_PROGRESS","sortOrder":20},
    {"statusKey":"DONE","name":"Done","category":"DONE","sortOrder":30}
  ],
  "transitions": [
    {"fromStatusKey":"TODO","toStatusKey":"REVIEW","name":"Review","sortOrder":10,"hidden":false},
    {"fromStatusKey":"REVIEW","toStatusKey":"DONE","name":"Finish","sortOrder":20,"hidden":false}
  ]
}
```

3. `POST /api/v1/projects/{P}/workflows/{W}/statuses` добавить QA category IN_PROGRESS, sortOrder=40: 201. `PATCH /api/v1/projects/{P}/workflows/{W}/statuses/{QA_ID}` с текущей version, name/category/sortOrder: 200; immutable statusKey не меняется. Устаревшая version — 409, текущее состояние сохранено.
4. `POST /api/v1/projects/{P}/workflows/{W}/transitions` добавить REVIEW→QA: 201. `PATCH /api/v1/projects/{P}/workflows/{W}/transitions/{T}` изменить name/order/hidden: 200; endpoints immutable. В `/api/v1/projects/{P}/workflows` скрытый переход виден, в эффективном `/workflow?issueType=` скрыт. Его программное применение проверяется отдельно.
5. Удалить неиспользуемый переход `DELETE /api/v1/projects/{P}/workflows/{W}/transitions/{T}?version=${CURRENT}`: 200, сохранив минимально допустимый граф (минимум один видимый переход); затем QA `DELETE /api/v1/projects/{P}/workflows/{W}/statuses/{QA_ID}?version=...`: 200. Связанные переходы удалённого статуса также отсутствуют.
6. Пустой issueTypes — 400 INVALID_ARGUMENT с details.reason WORKFLOW_ISSUE_TYPES_EMPTY до записи графа; пропущенные обязательные коллекции графа, неизвестные типы/endpoints, duplicate keys/orders, отрицательный order, null/unknown поля — 400 без частичного графа; конфликт конкурирующей версии — 409. Не путать пропущенную коллекцию с допустимым в legacy create пустым набором transitions; минимумы последующих мутаций проверяются отдельно. Default и workflow другого проекта не мутируются, VIEWER/MEMBER получают 403.

**Очистка:** W оставить для следующих сценариев; в конце удалить с текущей version/нужными mappings.

## E2E-11. Перенос типов между workflow и выбор workflow нового типа

**Условия:** ADMIN P; W из E2E-10; дополнительный W2. На типе E2E_CUSTOM есть задачи в REVIEW и TODO, снимки полей сохранены вне Git.

1. Создать W2 с issueTypes=[E2E_CUSTOM] и графом TODO/IN_PROGRESS/DONE, без mappings: если REVIEW отсутствует, 400 WORKFLOW_ISSUES_UNMAPPED. issueCount/statusKeys/targetStatusKeys описывают реальные проблемные задачи, ни binding, ни версии/статусы не меняются.
2. Повторить исправленную команду **с новым** X-Request-Id и `statusMappings:{"REVIEW":"IN_PROGRESS"}`: 201. Только несовместимые/явно сопоставленные статусы мигрируют, остальные поля и тип задачи сохраняются. На изменение статуса одна история TRANSITIONED; нет UUID операции/токена в публичных DTO.
3. W, потерявший последний тип, остаётся собственным workflow с пустым issueTypes и прежним графом, виден в списке, пригоден для повторного использования/удаления. Это не эквивалент разрешения целевого PATCH с `issueTypes:[]`.
4. `PATCH /api/v1/projects/{P}/workflows/{W}` с текущей version и непустым полным набором типов возвращает их W. Удалённые из набора типы уходят в общий default, даже если основной workflow проекта иной. Mappings проверяются для фактического назначения каждого типа; дополнительные details по типам согласованы.
5. В PATCH с issueTypes отсутствие version/дубликаты/null/[]/неизвестный тип — 400; stale version — 409. Name-only PATCH без issueTypes сохраняет bindings; пропуск version допустим для name-only. Default остаётся immutable.
6. Изменить порядок реестра: без workflowId новый тип наследует workflow **первого** типа по sortOrder/code. Явный workflowId позволяет выбрать W, пустой W или общий default. Чужой/несуществующий workflowId — отказ без опубликованного типа; null — 400.
7. Повтор POST/PATCH с тем же X-Request-Id и тем же телом не создаёт второй объект/миграцию. Другой command с тем же ключом — 409; повтор после отзыва ADMIN заново проверяет ACL.

8. Перенести все типы с общего default на собственные workflow: `GET /api/v1/projects/{P}/workflows` всё ещё содержит общий default shared=true с issueTypes=[]; его UUID можно выбрать при добавлении нового типа. Переставить первый тип реестра: mainWorkflowId меняется согласно **эффективному** workflow нового первого типа и совпадает с `GET /api/v1/projects/{P}/workflow?issueType=${FIRST_CODE}`. Пустой собственный workflow сохраняет граф и доступен для reuse/delete.
9. В изолированном стеке создать гонку изменения реестра между чтением и созданием типа без workflowId, сменой набора workflow либо удалением типа с переносом: если сервер обнаружил смену поколения/набора, это 409 ABORTED / ISSUE_TYPES_CHANGED, без частичного применения. Пока другая команда с типами удерживает gate, конфликтующая новая команда получает 409 ABORTED / ISSUE_TYPE_OPERATION_PENDING. Другой payload с уже использованным X-Request-Id — 409 REQUEST_ID_REUSED; повтор исходной команды не создаёт второй эффект.

**Очистка:** задачи удалить/нормализовать, собственные типы и workflow удалить после зависимых сценариев.

## E2E-12. Начальный статус и удаление статуса/workflow

**Условия:** собственный граф, задачи TODO/REVIEW; отдельный граф без TODO и без transitions. Если используется W из E2E-10, заранее добавить видимый переход TODO→DONE с уникальным sortOrder: он должен сохраниться после удаления REVIEW, чтобы оставался хотя бы один видимый переход.

1. Создание задачи без statusKey использует TODO и обязано проверить его принадлежность текущему workflow; в графе без TODO — 400 STATUS_NOT_IN_WORKFLOW без задачи. Явный допустимый statusKey работает и в графе без transitions. Пустой/плохой ключ — 400 INVALID_ARGUMENT; синтаксически правильный чужой — 400 STATUS_NOT_IN_WORKFLOW.
2. `DELETE /api/v1/projects/{P}/workflows/{W}/statuses/{REVIEW_ID}` без target при наличии задач отклоняется, задачи/граф неизменны. С текущей version и `targetStatusKey=TODO`: 200, задачи **удаляемого** статуса перенесены, связанные переходы удалены, board/history согласованы.
3. Если в выделенной legacy фикстуре есть задачи со старыми ключами вне графа, они остаются unplaced; удаление другого статуса их не мигрирует. Не создавать legacy повреждения прямой правкой общего стенда.
4. `DELETE /api/v1/projects/{P}/workflows/{W}` с текущей version: 200, типы возвращаются на default. При несовместимых статусах без mappings — отказ; с допустимыми mappings переносится необходимый набор, identity не меняет версии задач. Неправильная version — 409.
5. Default нельзя удалить/редактировать; чужой граф не становится собственным. Пустой свой W после переноса последнего типа можно удалить с сохранением остальных bindings.

**Очистка:** удалить только собственные оставшиеся workflow/задачи; не менять default.

## E2E-13. Удаление непустого типа с переносом задач

**Условия:** типы SRC/DST в P, свои workflow с несовместимыми статусами; две активные SRC-задачи и одна soft-deleted, сохранены UUID/generation/снимки.

1. `DELETE /api/v1/projects/{P}/issue-types/SRC` без тела: 400 ISSUE_TYPE_IN_USE с точным числом живых задач, ни одна не изменена. Последний тип защищён LAST_ISSUE_TYPE.
2. `DELETE /api/v1/projects/{P}/issue-types/SRC` с `{"moveIssuesTo":"DST","expectedGeneration":"${SRC_GENERATION}"}` без полного mappings: 400 ISSUE_STATUSES_UNMAPPED со всеми statusKeys/targetStatusKeys, состояние атомарно сохранено.
3. Исправленный запрос с `statusMappings` и актуальным generation: 204; SRC отсутствует в реестре/context, все живые задачи стали DST, их id/key/контент/оценки/исполнитель/метки/наблюдатели сохранены, статус валиден для DST. Soft-deleted задачи не мигрировали.
4. На каждой перенесённой задаче одна история UPDATED со старым/новым типом и статусом, а не отдельный лишний TRANSITIONED. После повтора той же завершённой команды дополнительных переносов/истории/уведомлений нет.
5. SRC=target, неизвестный target, неправильное mapping, stale generation — отказ без изменений. После повторного создания SRC нужен expectedGeneration; старое поколение не удаляет новое (ISSUE_TYPE_CHANGED/ISSUE_TYPE_GENERATION_REQUIRED).
6. 5001 живая SRC-задача: WORKFLOW_MIGRATION_LIMIT до изменений. Проверять только в изолированной нагрузочной фикстуре; для удаления типа предел считает **все** живые SRC, для workflow binding — только требующие изменения статуса задачи.

7. Для **пустого отдельного** кода E2E_RECREATE сохранить generation g1; `DELETE /api/v1/projects/{P}/issue-types/E2E_RECREATE` без тела с ключом `${DELETE_REQUEST}`: 204. Повтор тем же ADMIN/ключом — 204 без новой операции; другой requestId либо другой ADMIN при отсутствующем коде — 404. Создать E2E_RECREATE снова с новым ключом: generation g2 отличается от g1. Запоздалый повтор исходного DELETE с тем же `${DELETE_REQUEST}` — 204, но g2, его workflow binding и задачи остаются неизменны. Свежий requestId уже может удалить пустой g2. Проверить, что в журнале не возникла вторая deletion от старого ключа.
8. В изолированной фикстуре растянуть удаление без тела дольше короткого обычного gRPC deadline: оно использует такой же длительный срок команды, как удаление с переносом, и не падает в ранний 504. При потере ответа повторять тот же requestId; завершённая операция не повторяет побочные эффекты. Конкурентная **иная** команда удаления во время pending получает 409 ISSUE_TYPE_OPERATION_PENDING, а не 503 с чужим id; 503 ISSUE_TYPE_DELETION_PENDING оставлен для действительно принятой исходной команды.

**Очистка:** удалить/перенести тестовые задачи и типы, восстановить компактный граф. Не снимать внутренние fences вручную.

## E2E-14. Создание задачи, идемпотентность и чтение

**Условия:** MEMBER M, P с допустимым TASK/TODO.

1. `POST /api/v1/projects/{P}/issues` с новым UUID Idempotency-Key и телом ниже: 201, новый UUID, issueKey с P prefix, reporter M, status TODO, version>0 и заданные поля. Порядок issueNumber монотонен, не требовать отсутствия пропусков.

```json
{
  "issueType":"TASK", "summary":"E2E ${RUN} primary",
  "description":"E2E body ${RUN}", "priority":"HIGH", "storyPoints":3.5,
  "startDate":"${START_DATE}", "dueDate":"${DUE_DATE}",
  "originalEstimateMinutes":120, "remainingEstimateMinutes":90
}
```

2. `GET /api/v1/issues/{id}` и `GET /api/v1/issues/by-key/{key}`: 200, одна и та же issue/history; `GET /api/v1/projects/{P}/issues` содержит задачу. Проверить профили reporter/assignee, labels/watchers/links/attachments/commentCount, planning fields и timeSpentMinutes.
3. Повтор create с тем же key/телом, в том числе одновременный: тот же id, одна задача/создание/номер. Изменение statusKey, storyPoints, дат или оценок при том же key — конфликт, не молчаливое сохранение старого тела. Отсутствующий/невалидный key — отказ.
4. Невалидные type/priority, отрицательные оценки, недопустимые даты/summary — 400 без задачи/истории. VIEWER не создаёт (403). Replay после удаления членства/архива проверяет актуальные права и не раскрывает сохранённый ответ.

**Очистка:** удалить собственные задачи E2E-23 после остальных проверок.

## E2E-15. Изменения задачи и оптимистичная блокировка

**Условия:** две сессии M с одинаковой прочитанной version N.

1. `PATCH /api/v1/issues/{id}` с `If-Match:"N"` и `{"summary":"E2E changed","description":null,"storyPoints":null}`: 200, три поля изменены/очищены, пропущенные значения сохранены; version увеличена, история описывает фактические изменения.
2. Вторая сессия с N — 409, без потери данных победителя и лишней истории. Два параллельных PATCH N имеют одного победителя. Missing/malformed/nonpositive If-Match — 400; summary/priority null и отрицательные числовые поля — 400.
3. `PUT /api/v1/issues/{id}` с полным допустимым summary/description/priority и planning: 200, последующие GET/list/by-key/search/board согласованы. Отличать семантику PUT и PATCH; отсутствующие PATCH поля нельзя очищать.
4. PATCH issueType на зарегистрированный тип с подходящим статусом: 200, тип/история обновлены. Несовместимый статус — 400 STATUS_NOT_IN_TARGET_WORKFLOW и исходный тип сохранён. Удалённый/неизвестный тип не принимается.
5. Под V/O те же записи запрещены, включая точный id/key. Во вложенных маршрутах проверять ACL фактического проекта issueId. Если маршрут разрешает навигационный projectId без проверки иерархии, одно несовпадение path не считать нарушением; пользователь без прав на фактическую задачу не получает доступ.

**Очистка:** не требуется сверх удаления фикстур.

## E2E-16. Назначение, переходы и история

**Условия:** M и A в P, фактический граф прочитан через `/workflow`.

1. `PUT /api/v1/issues/{id}/assignee` с `{"assigneeId":"${M_ID}"}`: 200; карточка/list/search/board показывают M и профиль, история и уведомления согласованы. При включённом auto-watch M подписан ровно один раз.
2. PATCH assigneeId:null с актуальным If-Match снимает назначение; пропуск не снимает. Неучастник/VIEWER/BLOCKED не становится исполнителем в обход правил записи; отказ не меняет прежнего исполнителя.
3. `PUT /api/v1/issues/{id}/transition/{T_ID}` с `{"payload":{}}` и актуальным If-Match: 200, целевой статус, новая version и одна TRANSITIONED. UUID перехода брать из реального графа; не подставлять захардкоженные UUID.
4. Переход из другого исходного статуса/другого workflow, устаревший If-Match (409), VIEWER/O (403), несуществующий transition не изменяют задачу. Разрешённый hidden переход доступен программно, хотя отсутствует в UI-списке.
5. Проверить публичную историю после создания, изменения, назначения, перехода: actorUserId, участники действия и канал корректны; возвращаемые UserSummary содержат только предусмотренные id/displayName/avatarUrl. Email и неизменяемый снимок имени в публичной истории не обещаются; административные снимки автора проверяются в audit по E2E-27. Поля публичной истории не содержат имя/UUID PAT, raw комментарии, secret или внутренний operationId. Старые nullable channel не выдумываются.

**Очистка:** вернуть задачу в нужный статус допустимым переходом либо удалить её.

## E2E-17. Списки, поиск и статистика

**Условия:** несколько задач P с разными статусами, приоритетами, исполнителями, типами/метками; чужой Q.

1. `GET /api/v1/projects/{P}/issues?page=0&pageSize=1`, затем следующие страницы: каждая запись принадлежит P; фильтры status, assigneeId, labelId дают ожидаемое пересечение. Пустая страница не означает исчезновение totalCount.
2. `GET /api/v1/issues/search?query=${UNIQUE_TEXT}&projectId=${P}`: 200 items/totalCount; найти по части summary/description/key. Добавить statusKey, assigneeId, reporterId, priority, issueType; результат соответствует каждому фильтру и ACL.
3. Без projectId поиск не раскрывает Q/O; после удаления участника его результаты P исчезают сразу. Soft-deleted/архивированные по контракту объекты не возвращаются как активные.
4. Query менее 3 символов, page/pageSize вне границ и неверные UUID/enum — 400, не 500. Проверить пустой результат с 200 и totalCount=0.
5. `GET /api/v1/project-stats`: счётчики доступных проектов согласованы с задачами до/после перехода/удаления; нет статистики чужого P у O.

**Очистка:** не требуется сверх удаления фикстур.

## E2E-18. Доска, unplaced и курсор колонки

**Условия:** не менее трёх задач в одной колонке, задачи типа со своим графом и DONE category под нестандартным ключом.

1. `GET /api/v1/projects/{P}/board?pageSizePerColumn=1`: 200, колонки по эффективным workflow реестра, первая карточка/totalCount/hasMore каждой колонки согласованы. Метки и профиль исполнителя согласованы с карточкой; planning и timeSpentMinutes не требовать от BoardIssueDto, если эти поля не предусмотрены его схемой.
2. `GET /api/v1/projects/{P}/board/columns/{statusKey}/issues?pageSize=1`: 200, порядок createdAt asc,id asc. Продолжать с afterCreatedAt/afterId **одной последней карточки** и теми же фильтрами до пустой страницы: без повторов/пропусков стабильной выборки, totalCount полный независимо от cursor.
3. Повторить при issueType, assigneeId, labelId, includeDone, priority, query. includeDone=false исключает все ключи категории DONE, а не только литерал DONE; true возвращает их.
4. Неизвестный/невалидный issueType даёт пустую доску; неизвестный statusKey колонки — пустую страницу после проверки ACL. Одна половина cursor, неверная дата/UUID или pageSize вне 1..200 — 400.
5. Удалить карточку, на которую указывает cursor, и продолжить: cursor остаётся пригодным. Вставка после cursor видна в продолжении; вставка до него — после обновления доски. Это чтение текущих данных, не snapshot между запросами.
6. В legacy фикстуре задачи со статусом вне workflow **своего** типа находятся в unplacedIssues, не попадают в одноимённую колонку другого типа и не выдаются через column endpoint. Для проверки не портить общий стенд прямой записью БД.

**Очистка:** удалить добавленные задачи, не удалять чужие курсорные объекты.

## E2E-19. Комментарии и счётчик

**Условия:** M/A/V в P, задача M.

1. `POST /api/v1/projects/{P}/issues/{id}/comments` с `{"body":"E2E ${RUN} comment"}`: **200**, UUID автора/комментария. `GET /api/v1/projects/{P}/issues/{id}/comments` с page/pageSize: 200, нужный текст/профиль/channel; commentCount карточки увеличен на один.
2. `PUT /api/v1/projects/{P}/issues/{id}/comments/{commentId}` с новым body: 200; GET показывает новое содержимое, количество не увеличилось. Проверить автора, updatedAt и канал последней записи.
3. Пустой/body>10000/неверная пагинация — 400, количество/текст не меняются. VIEWER и O не пишут; подстановка навигационного projectId не обходит ACL фактической задачи. Редактировать/удалять комментарий может только его автор с правом записи в проекте: другой MEMBER и ADMIN-неавтор получают 403, текст/счётчик сохраняются.
4. История/outbox/inbox не копируют raw текст комментария и не раскрывают приватный UUID PAT. Собственная успешная запись ровно одна, без дубликатов при чтении страниц.
5. `DELETE /api/v1/projects/{P}/issues/{id}/comments/{commentId}`: 204; GET больше не показывает комментарий, commentCount уменьшается. Повтор не удаляет другие комментарии.

**Очистка:** удалить комментарии RUN.

## E2E-20. Worklogs и суммарное время

**Условия:** M/A в P, задача без worklogs.

1. `POST /api/v1/projects/{P}/issues/{id}/worklogs` с `{"spentMinutes":30,"workDate":"${TODAY}","comment":"E2E work"}`: 201, authorUserId M. Добавить второй worklog на 45 минут; list 200 и timeSpentMinutes задачи=75, независимо от pageSize=1.
2. `PUT /api/v1/projects/{P}/issues/{id}/worklogs/{worklogId}` с `{"spentMinutes":40}`: 200, дата/комментарий сохранены; сумма=85. Изменить дату/комментарий допустимыми значениями; detail, by-key и список задач проекта продолжают показывать правильную сумму. Search и board не обязаны возвращать timeSpentMinutes: это поле отсутствует в их DTO.
3. Проверить list пагинацию и profile автора, в том числе после удаления автора из P: authorUserId сохраняется, исторический профиль или null не ломают список.
4. spentMinutes 0/negative, дата за допустимым будущим пределом, >2000 comment, неверная пагинация — 400 без изменения суммы. MEMBER меняет свой, чужой — только ADMIN; VIEWER читает, но не пишет. Чужой worklogId не позволяет менять другой объект; доступ проверяется по фактической задаче/проекту, а не только по навигационному projectId.
5. `DELETE /api/v1/projects/{P}/issues/{id}/worklogs/{worklogId}`: 204, сумма уменьшается ровно на удалённые минуты, запись не возвращается. После удаления обоих сумма=0; original/remaining estimate не выдумываются из суммы, если контракт этого не требует.

**Очистка:** удалить worklogs RUN.

## E2E-21. Связи, метки и наблюдатели

**Условия:** две задачи P, задача Q и участники M/A/V.

1. `POST /api/v1/issues/{I1}/links` с `{"targetIssueId":"${I2}","linkType":"BLOCKS"}`: 201; `GET /api/v1/issues/{I1}/links` и `GET /api/v1/issues/{I2}/links` 200, противоположные направления/viewLinkType и targetIssue согласованы. Самоссылка, дубликат, неизвестный linkType и связь в чужой проект отклоняются по правилам доступа.
2. `DELETE /api/v1/issues/{I1}/links/{linkId}`: 204; связь отсутствует с обеих сторон.
3. A `POST /api/v1/projects/{P}/labels` с `{"name":"E2E ${RUN}","color":"#0052CC"}`: 201; `GET /api/v1/projects/{P}/labels`: 200. `PATCH /api/v1/projects/{P}/labels/{labelId}` с name/color: 200; повторное имя/неправильный HEX/слишком длинное имя — отказ без порчи прежней метки. MEMBER не создаёт/редактирует/удаляет проектную метку.
4. M `POST /api/v1/projects/{P}/issues/{id}/labels` с labelId: 201; list labels и карточка/list/search/board содержат одну метку с актуальными name/color. Чужая project label не привязывается. `DELETE /api/v1/projects/{P}/issues/{id}/labels/{labelId}` у задачи — 204, проектная метка остаётся.
5. `PUT /api/v1/projects/{P}/issues/{id}/watchers/me`: 200; повтор не дублирует подписку, GET watchers и isWatching согласованы. `DELETE /api/v1/projects/{P}/issues/{id}/watchers/me`: 200, повтор не затрагивает других. Проверить auto-watch reporter/assignee при включённых настройках.
6. A `POST /api/v1/projects/{P}/issues/{id}/watchers` с `{"userId":"${M_ID}"}`: 200; `DELETE /api/v1/projects/{P}/issues/{id}/watchers/{M}`: 200. M может подписывать себя, но не управлять чужими подписками (403); VIEWER не создаёт подписку при стандартной политике. Чужой/nonmember/BLOCKED target не должен обходить проверки допуска.
7. A удалить проектную метку через `DELETE /api/v1/projects/{P}/labels/{labelId}`: 204; она исчезает из всех привязок/карточек, остальные метки остаются.

**Очистка:** убрать связи, тестовые метки и лишние подписки.

## E2E-22. Вложения и аватар через object storage

**Условия:** выделенная активная учётная запись без чужого аватара, задача P, небольшой валидный PNG и доступный клиенту S3.

1. `POST /api/v1/projects/{P}/issues/{id}/attachments/upload-url` с `{"fileName":"e2e.png","contentType":"image/png","sizeBytes":${SIZE}}`: 200, objectKey/presigned upload URL. До PUT вложение не считается подтверждённым.
2. Загрузить **точные байты** отдельным PUT на выданный URL с указанным contentType, без JWT Taska. 2xx от storage. `POST /api/v1/projects/{P}/issues/{id}/attachments/confirm` с objectKey/fileName/contentType: 201, attachmentId.
3. `GET /api/v1/projects/{P}/issues/{id}/attachments`: 200; `GET /api/v1/projects/{P}/issues/{id}/attachments/{attachmentId}/download-url`: 200. Скачать и сравнить хеш/размер/тип с исходным файлом. VIEWER может читать; посторонний не получает URL.
4. Размер 0/>2097152, запрещённый MIME, чужой objectKey, подтверждение отсутствующего объекта, UUID вложения недоступной задачи — контролируемый отказ без чужой привязки. Для download/delete права проверяются по фактическому вложению; навигационные projectId/issueId сами по себе не дают и не отнимают доступ к нему. MEMBER удаляет своё вложение; чужое удаляет только ADMIN по стандартной политике.
5. `DELETE /api/v1/projects/{P}/issues/{id}/attachments/{attachmentId}`: 204; list/карточка больше не содержат его, новый download URL не выдаётся. Не требовать немедленной недействительности уже выданного presigned URL, если storage-политика этого не обещает.
6. Аналогично `POST /api/v1/users/me/avatar/upload-url`: 200; PUT байтов; `POST /api/v1/users/me/avatar/confirm`: 200; me/каталог и `GET /api/v1/users/{userId}/avatar` возвращают аватар. Скачать и сравнить байты.
7. Неверный размер/MIME, чужой avatar objectKey и confirm до PUT отклоняются. `DELETE /api/v1/users/me/avatar`: 204; последующий get avatar — предусмотренный 404, профиль/каталог корректно работают без него.

**Очистка:** удалить только загруженные RUN файлы/аватар через публичный API. Не заменять/удалять существующий чужой аватар; при недоступном внешнем S3 позитивный upload/download остаётся непроверенным.

## E2E-23. Удаление задач и архив проекта

**Условия:** зависимые проверки завершены, идентификаторы собственных объектов сохранены.

1. `DELETE /api/v1/issues/{id}` под MEMBER/ADMIN: 204; get/by-key не возвращают активную задачу, lists/search/board/stats исключают её. Связанные read/write вложенных ресурсов не обходят удаление.
2. VIEWER/O не удаляют. Удаление одной задачи не удаляет другие задачи, реестр типов или чужие worklogs/вложения.
3. `DELETE /api/v1/projects/{P}` под A: 200, проект архивирован. My projects/stats/context и записи по архивному проекту соответствуют контракту архива; создать/изменить задачу/тип/workflow/member нельзя. Отрицательный ACL продолжает действовать.
4. MEMBER/VIEWER не архивируют; повторный запрос не затрагивает другой проект. Повтор сохранённого idempotent create ответа заново проверяет архив/ACL.

**Очистка:** Q и оставшиеся RUN проекты архивировать; не выполнять массовое удаление или SQL cleanup общего стенда.

## E2E-24. Inbox и настройки доставки

**Условия:** отдельные отправитель A и получатель M, M участник/наблюдатель задачи; исходные настройки M сохранены.

1. `GET /api/v1/users/me/notification-preferences`: 200, правильный email, независимые emailEnabled/telegramEnabled, честная доступность/статус Telegram.
2. `PATCH /api/v1/users/me/notification-preferences` с `{"emailEnabled":false,"telegramEnabled":false}`: 200; не переданное поле не меняется, null/unknown field/пустой command — 400. Включение неподтверждённого/недоступного Telegram — 409 без ложного connected.
3. A назначает M задачу, меняет её/добавляет комментарий. Дождаться `GET /api/v1/notifications` под M: 200, событие нужной задачи/проекта, правильный тип/автор/ссылка. При обоих каналах false inbox всё равно работает; собственные действия могут отфильтровываться, поэтому использовать двух авторов.
4. Проверить unread/read фильтры и пагинацию. `PATCH /api/v1/notifications/{N}/read`: 200, N прочитано; повтор не создаёт уведомление. Чужой N нельзя прочитать/изменить. `POST /api/v1/notifications/read-all` на **выделенном** M: 200 с корректным количеством, все его непрочитанные прочитаны, inbox других не меняется.
5. Включить email и повторить отдельное действие A: inbox и письмо в тестовом приёмнике согласованы, текст/ссылка безопасны, raw comment body/секреты не копируются в производные данные. Отсутствие письма нельзя считать успешной доставкой только по записи inbox.
6. Выключить email/Telegram и повторить: новых отправок отключённого канала нет. Включение Telegram не досылает уведомления, созданные при выключенном канале; старый backlog не уходит новому подключению.

**Очистка:** восстановить исходные настройки выделенного M; свои уведомления читать только на этом аккаунте, глобальный inbox не очищать.

## E2E-25. Подключение Telegram и webhook

**Условия:** отдельный тестовый bot/chat; при выключенной интеграции проверять UNAVAILABLE/предусмотренные 409/503 и не выдавать это за позитивный цикл.

1. `POST /api/v1/users/me/telegram/link`: 200 с linkId/url/expiresAt/preferences; PENDING, TTL 10 минут. Второй link отменяет первый; GET settings согласован с возвращённым snapshot.
2. В личном тестовом чате пройти `/start` по ссылке. Настоящий webhook `POST /api/v1/integrations/telegram/webhook` с секретным header обрабатывается 200; статус становится AWAITING_CONFIRMATION, **не** автоматически CONNECTED.
3. `POST /api/v1/users/me/telegram/confirm` с текущим linkId: 200, CONNECTED, telegramEnabled=true, emailEnabled сохранён. Повтор/expired/старый linkId — 409. Подменённый linkId не подключает чужой chat.
4. Действием A сформировать уведомление M: inbox и Telegram-получатель/ссылка верны. Повтор update_id не дублирует доставку/привязку. Команды из группы и чужого аккаунта не захватывают чужую заявку.
5. Webhook без/с неверным `X-Telegram-Bot-Api-Secret-Token`: 403; malformed payload — 400, тело >32 KiB — 413, rate-limit — 429. Позитивный webhook не синтезировать чужими chat_id на общем боте.
6. `DELETE /api/v1/users/me/telegram`: 200, NOT_CONNECTED и канал выключен; pending delivery отменена. `/stop` эквивалентно разрывает привязку. Выключение только toggle сохраняет связь, но не очередь; переподключение на новый chat не отправляет старые задания.

**Очистка:** disconnect только собственного тестового пользователя/чата, восстановить исходные настройки; не перенастраивать/не удалять общий webhook.

## E2E-26. Глобальное администрирование пользователя

**Условия:** G, выделенные INVITED I и ACTIVE U; никаких блокировок A/M/G общего назначения.

1. `PATCH /api/v1/admin/users/{I}` с displayName: 200, корректное нормализованное имя; directory/profile отражают изменение, UUID неизменен. Неверное имя — 400; обычный project ADMIN получает 403.
2. `POST /api/v1/admin/users/{I}/block` с `{"reason":"E2E ${RUN}"}`: 200 BLOCKED; I не логинится/не виден в каталоге. `POST /api/v1/admin/users/{I}/unblock` с reason: 200 **INVITED**, не ACTIVE; старую неистекшую ссылку можно принять после разблокировки.
3. Для отдельного ACTIVE U блокировка запрещает и login, и следующий запрос с ранее выданным access/PAT; unblock возвращает ACTIVE и не меняет пароль/членство. Проверить audit actor/target/reason/requestId, без секретов.
4. Только на U выполнить контролируемые неправильные login до LOCKED. Уже активный access при временном LOCKED работает по политике; новый login/refresh запрещены. `POST /api/v1/admin/users/{U}/reset-lockout` с reason: 200, корректный пароль снова работает; BLOCKED не превращается этим в ACTIVE.
5. Пустой reason, неизвестный userId, невалидный UUID, неразрешённая self/last-admin операция — предусмотренный 400/404/409; MEMBER/VIEWER/PAT — 403. Проверять отсутствие незапланированных изменений.

6. В `PATCH /api/v1/admin/users/{I}` имя из одних U+200B/U+FEFF/U+2060 либо их смеси с пробелами — 400 INVALID_ARGUMENT / DISPLAY_NAME_INVALID; UUID/status/исходное имя и credentials неизменны. Допустимое видимое имя обновляется штатно; после проверки вернуть исходное.

**Очистка:** восстановить исходные имена/status выделенных фикстур; не менять credentials других пользователей.

## E2E-27. Data, аудит и outbox

**Условия:** JWT G, собственные объекты с RUN; негативная проверка под обычным пользователем и PAT G.

1. `GET /api/v1/readonly/catalog`: 200, актуальные service/table/column metadata/capabilities. Под обычным пользователем/PAT — 403, без каталога.
2. Для каждой доступной каталожной service/table сделать `GET /api/v1/readonly/{service}/{table}?page=0&pageSize=1` и допустимые sort/order/filters. 200, колонки/типы/пагинация согласованы с metadata; full email отображается везде, а пароли/hash/JWT/PAT/invitation/OAuth/Telegram secrets замаскированы, включая вложенный JSON.
3. Для таблицы со single-row capability взять UUID своей строки: `GET /api/v1/readonly/{service}/{table}/{id}` — 200 той же строки. Неизвестная таблица/service/id — 404; запрещённая колонка/operator, невалидная пагинация/sort/инъекция — 400 без выполнения SQL или раскрытия схемы/секретов.
4. `GET /api/v1/readonly/audit-entries` с actorUserId/action/targetService/targetTable/targetId/requestId/date range: 200, записи выполненных административных операций найдены, снимки автора/reason корректны. Страницы и hasMore/total по контракту; обратный диапазон/неверная дата — 400.
5. `GET /api/v1/readonly/outbox/problematic-summary`: 200, counters по сервисам и события согласованы; serviceKey filter ограничивает результат.
6. На **специально подготовленном собственном** проблемном событии `POST /api/v1/admin/outbox/{service}/{eventId}/retry` с reason: 200, событие переведено в разрешённое повторяемое состояние, audit записан. Не повторять рабочие/чужие события ради теста. Неизвестный UUID — 404; обычный user/PAT — 403; отсутствие reason — 400.
7. Retry с потерянным ответом сначала сверить по audit/state; не создавать неконтролируемые повторные внешние эффекты. Fault injection Kafka недоступности выполнять только в изолированной среде.

**Очистка:** не удалять audit и не очищать shared outbox; собственную фикстуру довести до терминального состояния по штатным средствам.

## E2E-28. Изоляция, повтор после сбоя и границы полного прогона

**Условия:** P/Q, A/M/V/O/G, read и read/write PAT, отдельная изолированная копия стека для fault injection.

1. Применить таблицу ролей ниже к чтениям и записям каждого семейства API. Проверять прямой id/key и вложенные projectId/issueId/commentId/worklogId/attachmentId/labelId/linkId/watch userId. Для маршрутов, где projectId является навигационным и не валидируется как иерархия, решающим остаётся ACL фактического проекта issueId; несовпадение path само по себе не означает ожидаемый 404. Чтение списка не должно раскрывать объект, который запрещён прямым GET.
2. Для workflow create/rebind и удаления типа в изолированной копии потерять ответ после commit либо временно остановить зависимость. При 503 WORKFLOW_BINDING_CHANGE_PENDING / ISSUE_TYPE_CREATION_PENDING / ISSUE_TYPE_DELETION_PENDING сохранять исходную команду и ключ; повтор и recovery доводят её вперёд ровно один раз. Все history/outbox и версии согласованы; fences не снимаются вручную.
3. Пока операция pending, конкурентные writers не создают задачу в удаляемом/переносимом поколении. Отзыв роли после commit не ломает фоновое завершение; новый публичный повтор проверяет текущие права.
4. Проверить запрос workflow JSON до лимита 1 MiB и превышение (413), лимиты внутренних планов и 5000 изменяемых статусов на изолированной среде. Не отправлять объёмные нагрузки на общий стенд без отдельной тестовой фикстуры.
5. Остановки сервисов, сетевые fault injection, 5001-объектные наборы и секретные интеграции не считаются проверенными обычным счастливым HTTP-путём. MCP Streamable HTTP и отдельный oauth-server имеют собственные протокольные сценарии вне gateway Swagger; их клиентскую приёмку вести по [MCP README](../../mcp-server/README.md), [OAuth backend](../tas-413-oauth-backend.md) и соответствующим Jira задачам.

**Очистка:** восстановить только собственный изолированный стек/фикстуру; не перезапускать и не перенастраивать общий стенд ради этих проверок.

### Матрица ролей стандартной конфигурации

Параметризуемые allowed-roles сверять с фактической конфигурацией: отличие настройки от default не объявлять багом без продуктового требования.

| Действие | ADMIN P | MEMBER P | VIEWER P | Неучастник, включая G | PAT |
| --- | --- | --- | --- | --- | --- |
| Читать P/issues/context/board/comments/worklogs/labels/watchers | Да | Да | Да | Нет | read + тот же ACL |
| Создать/изменить/назначить/перевести/удалить issue, написать comment, свой worklog | Да | Да | Нет | Нет | read/write + тот же ACL |
| Изменить проект/участников/реестр/workflow/проектную метку | Да | Нет | Нет | Нет | Только если operation x-pat-scope допускает; ACL сохраняется |
| Привязать метку, смотреть/загрузить своё вложение | Да | Да | Только чтение | Нет | По x-pat-scope |
| Подписать себя / управлять чужими watchers | Да / Да | Да / Нет | Нет / Нет | Нет | По x-pat-scope |
| Изменить чужой worklog/удалить чужое вложение | Да | Нет | Нет | Нет | По x-pat-scope + ACL |
| Admin/Data/invite | Только с GLOBAL_ADMIN | Нет | Нет | G имеет глобальные права только здесь | Нет, session required |

## E2E-29. Подключённые OAuth-приложения

**Условия:** собственные активные JWT-сессии A и O, личные PAT и делегированный MCP token; настроенная изолированная связка gateway→auth-service→oauth-server с собственным PostgreSQL и зарегистрированными разрешёнными клиентами C1/C2. Для полноценного внешнего подключения нужны реальные клиент и аккаунт; локальный OAuth-клиент/stub подтверждает внутренний протокол, не приёмку Google/GitHub/Apple/Claude/ChatGPT. Настройки OAuth и отказные HTTP-ответы менять только в своей копии стека.

1. Под JWT A `GET /api/v1/users/me/connected-apps`: 200, available=true, items=[] при отсутствии активных grants. Создать у A два активных grant одного C1 (сначала read, затем read/write/offline_access), у O — собственный grant C1; grant C2 создать только у O. Повтор GET A возвращает **одну строку C1**, без C2/данных O. appId — UUID зарегистрированного клиента, name/host/metadataUrl соответствуют C1; для CIMD metadataUrl равен client_id, для DCR null.
2. Scopes — объединение scopes активных grants C1 без повторов, отсортировано; незнакомый scope на контролируемой fixture передаётся как есть. connectedAt — минимум authorization_code_issued_at, lastUsedAt — максимум access_token_issued_at, expiresAt — максимум сроков access/refresh среди активных grants. Проверить refresh с обновлением lastUsedAt и точность времени UTC; сортировка строк connectedAt desc, appId desc при равенстве. Просроченные/отозванные grants не дают строки и не добавляют scopes; оставшийся активный refresh сохраняет строку при истёкшем access.
3. Без Bearer/с отозванным токеном GET/DELETE получают 401. Под read/write PAT (включая GLOBAL_ADMIN PAT) и делегированным MCP на `GET /api/v1/users/me/connected-apps` и `DELETE /api/v1/users/me/connected-apps/{appId}` — 403 PERMISSION_DENIED / SESSION_REQUIRED, без обращения к управлению grants. В изолированной прямой gRPC-проверке подмена user_id при настоящем JWT A также запрещена, никакой чужой owner не достигает oauth-server.
4. Под JWT A `DELETE /api/v1/users/me/connected-apps/{C1_APP_ID}`: 204 без тела, отозваны **все** grants C1 у A и удалено согласие. Следующий MCP-запрос каждого ранее выпущенного token A/C1 — 401; refresh прежних семейств не выпускает новые токены; ранее выданная delegation также не проходит онлайн-проверку родителя. GET A больше не содержит C1. Grants C1/C2 пользователя O продолжают работать, его список не изменён.
5. Повтор DELETE C1, случайный неизвестный UUID и C2, подключённый только O: 204, без изменений O и без различий, раскрывающих чужое подключение. При последующей новой авторизации C1 пользователю A снова требуется согласие; это новое подключение независимо от отозванных token families.
6. При отключённом OAuth `GET /api/v1/users/me/connected-apps` — 200 с available=false и items=[]; DELETE — 204 без вызова выключенного сервера. Это проверка режима выключенной интеграции, не успешного отзыва активного подключения.
7. При включённом OAuth и недоступном oauth-server, timeout либо upstream 4xx/5xx GET/DELETE — 503 UNAVAILABLE с X-Request-Id, не пустой успешный список/ложный 204. В отдельной HTTP fixture вернуть upstream 302/307 на DELETE: gateway также отвечает 503, redirect не выполняется, Authorization не передаётся на Location, исходные grants остаются пригодными. Восстановить маршрут и подтвердить настоящий DELETE 204 с фактическим отзывом из шага 4.
8. GET и DELETE не кэшируются; ответы и логи не содержат access/refresh/delegation token, Basic credential или metadata с секретом. Публичный контракт содержит обе операции, session-only policy и ошибки 403/503.

**Очистка:** отозвать только созданные RUN подключения A/O, PAT и клиентские сессии, убрать собственную изолированную fixture. Не менять общие registered clients, секреты, чужие grants или deployment.

## E2E-30. Повторный старт auth-service и сохранность истории миграций

**Условия:** собственная копия auth-service/gateway и PostgreSQL16 с ролью `taska`, стандартным `search_path = "$user", public` и артефактом с исправлением TAS-449. Для upgrade нужен также проверенный артефакт до исправления. Изменять схемы/журналы и запускать конфликтные фикстуры только в своей БД; общий стенд вручную не перезапускать.

1. На свежей БД без схемы taska запустить приложение: readiness успешна, журнал создаётся в public. После появления схемы taska новый SQL connection имеет current_schema=taska, но это не меняет выбранную схему истории миграций.
2. Создать собственные данные через API: `POST /api/v1/auth/login`, `POST /api/v1/users/me/access-tokens` и отдельное контролируемое приглашение из E2E-03. Сохранить токены только во внешнем защищённом состоянии. Зафиксировать данные и снимок существующего журнала (changeset id/author/filename/checksum, порядок и время выполнения), затем дважды остановить и запустить тот же артефакт на той же БД без override схемы. Оба старта успешны, история не переигрывается, новый shadow-журнал не появляется; данные и пароль сохранены.
3. Повторить upgrade с уже существующим полным журналом сначала в public, затем в taska, подготовив каждую БД старым артефактом. Исправленная версия сохраняет исходный полный журнал и не создаёт второй журнал вместо него. Applied changesets в исходниках не редактировать.
4. На отдельной свежей БД воспроизвести старый сбой: первый старый старт успешен, второй выбирает taska-журнал и падает на уже удалённом credentials.locked_until. После установки исправленного артефакта startup успешен: полный совместимый public-журнал сохраняется, частичный shadow в taska не переигрывается, не удаляется и не редактируется. Повторный старт также успешен.
5. В собственной конфликтной fixture подготовить две несовместимые истории: одинаковые changeset identities с разными checksums либо разные identities. Старт останавливается с диагностикой Conflicting Liquibase histories до миграций; обе истории и данные неизменны. Сознательный выбор проверенного журнала через `SPRING_LIQUIBASE_LIQUIBASE_SCHEMA` имеет приоритет; значения checksum/exectype не игнорируются ради автоматического выбора.
6. После каждого успешного перезапуска старые JWT/PAT читают `GET /api/v1/users/me` под исходным владельцем; `POST /api/v1/auth/refresh` принимает сохранённую refresh-сессию; `POST /api/v1/auth/invitations/preview` не потребляет действующую ссылку. Последующая `POST /api/v1/auth/invitations/accept` даёт204 и обычный login работает. Отозвать созданный PAT: `DELETE /api/v1/users/me/access-tokens/{tokenId}` даёт204, следующий запрос этого PAT —401.
7. На общем согласованном стенде допустима проверка через штатную автоматическую выкладку: подготовить только собственные PAT/сессию, дождаться подтверждённой версии и проверить их сохранность как выше. Не готовить конфликтующие журналы и не менять БД стенда для этого сценария.

**Очистка:** удалить свою изолированную БД/процессы, отозвать созданные PAT, убрать session/refresh/invitation секреты из внешнего состояния. Журналы общей среды не перемещать и не чистить.

## E2E-31. Страницы OAuth: вход, согласие, отказ и смена аккаунта

**Условия:** `AS` — отдельный согласованный HTTPS origin oauth-server, `RESOURCE` — канонический HTTPS URL `/mcp`; два активных тестовых пользователя A/O с паролем. Разрешённые точные redirect URI принадлежат тесту: HTTPS web callback и HTTP loopback callback с конкретным портом. Клиенты зарегистрированы через разрешённый DCR либо одобренный CIMD; секреты/cookies/code/verifier хранятся только вне Git. Для полного browser-прогона нужны настоящие Chromium, Firefox и WebKit; Safari как приложение отмечать отдельно. MockMvc и HTTP-клиент не доказывают поведение CSP в браузере.

1. Открыть `${AS}/login` без authorization request: страница показывает истёкший запрос без формы входа. `GET ${AS}/login/context` возвращает JSON с `pending_request=false` и raw CSRF contract (`csrf_parameter`, `csrf_token`). Прямой вход с этим raw token ведёт на `${AS}/login?done`; случайный URL не создаёт подключение.
2. Создать независимые случайные state и PKCE verifier, вычислить S256 challenge. Открыть `${AS}/oauth2/authorize?response_type=code&client_id=${CLIENT_ID}&redirect_uri=${ENCODED_REDIRECT}&scope=read&state=${STATE}&resource=${ENCODED_RESOURCE}&code_challenge=${CHALLENGE}&code_challenge_method=S256`. До входа — redirect на канонический `${AS}/login`; context теперь `pending_request=true`. Проверить HTML-навигацию также с `Accept: text/html`, `*/*` и отсутствующим Accept: это redirect на login, а JSON защищённого consent endpoint проверяется отдельно.
3. Войти A через отображённую форму. Raw CSRF принят, идентификатор session меняется, сохранённый authorize возобновляется на AS origin; JWT/refresh из обычного gateway login браузеру OAuth не выдаются. Без/с неверным CSRF на POST `/login` — redirect на `?error=SESSION_EXPIRED` и карточка истёкшего запроса, без входа. Неверный пароль даёт `INVALID_CREDENTIALS`; отдельный временно LOCKED пользователь — `ACCOUNT_LOCKED`, `until` при наличии дедлайна в UTC ISO; недоступный identity service — `SERVICE_UNAVAILABLE`. Последнюю проверку выполнять только в своём стеке, не останавливать общий auth-service.
4. Перед Allow страница `/consent` читает `GET ${AS}/oauth/consent?state=${CONSENT_STATE}`. Проверить поля `client_id`, нормализованное `client_name`, `client_host` (нижний регистр, явный порт сохранён; IPv6 в скобках), `metadata_host` (null для DCR), `redirect_kind=web|loopback`, запрошенные/ранее выданные scopes, account email/display_name, state, submit_uri и CSRF. Текст клиента рендерится как текст, bidi controls удалены; длинное имя сокращено. Для каждого вида callback проверить верное предупреждение о месте возврата; DCR не показывается как проверенный CIMD.
5. Нажать Allow: POST `${AS}/oauth2/authorize` содержит ровно `decision=allow`, client_id, consent state, raw CSRF и выбранные scopes; callback получает code, исходный client state и `iss=${AS}`. Обменять code на `${AS}/oauth2/token` с тем же client_id/redirect_uri/resource и verifier: 200; повтор code либо неверный verifier/resource не выпускает новую пару. Повторить вход в чистой browser session с тем же сохранённым read consent: цепочка login POST→authorize→callback работает без новой карточки согласия и без CSP violation.
6. С работающим read token запросить step-up `read write offline_access`. Проверить прежний read в `granted_scopes`, нажать Deny: callback содержит `error=access_denied` и исходный state, code отсутствует. Старый live token продолжает работать; согласие удалено. В независимых новых consent requests отправить отсутствующий и дублированный decision: отказ без code, не неявный Allow. С неверным CSRF даже при неизвестном scope — 403; с валидным CSRF, но чужим/неизвестным state — отказ без выдачи code. Невалидный scope при собственном валидном pending state не расширяет права.
7. В новом consent request нажать Use a different account. POST `${AS}/logout` содержит CSRF и `consent_state`, session инвалидируется; возврат на канонический authorize сохраняет client_id/точный redirect/resource/scopes/исходный client state/PKCE. После входа O согласие показывает O, A не подставляется из старой session. Чужой/подменённый consent_state не возобновляется. Отдельно проверить server-side expiry pending consent (10 минут от создания) и уже выданный code: context consent недоступен, logout ведёт на `/login?logout`, повторной авторизации по старому consent state нет.
8. После удаления cookies запрос `GET ${AS}/oauth/consent?state=...` с `Accept: application/json` получает 401 `login_required`, не HTML-login с 200. Статические ошибки 4xx/5xx при HTML Accept отдают предусмотренную HTML-страницу. На страницах/статиках проверить CSP `form-action 'self'` плюс только одобренные callback origins, `frame-ancestors 'none'`, `X-Frame-Options: DENY`, отсутствие inline script и сторонних запросов. В каждом доступном движке повторить Allow/Deny/saved-consent/account-switch цепочки для HTTPS и точного HTTP loopback callback; слушать `securitypolicyviolation`, не объявлять недоступный движок успешно проверенным.

**Очистка:** отозвать только grants тестовых клиентов/пользователей, закрыть callback listeners и собственные процессы/контейнеры. Удалить временные cookies, code/verifier/token stores; другие пользовательские grants не трогать.

## E2E-32. Социальный вход в OAuth browser session

**Условия:** стек с TAS-447 и сценарием E2E-31, отдельные provider accounts, связанные с ACTIVE A/O. Для локального протокола — свой provider stub и управляемые часы/отказные ответы; для внешней приёмки — реально настроенные Google/GitHub/Apple и доступ к соответствующему аккаунту. Stub не подтверждает доступность настоящего провайдера. Обычный UI OAuth из E2E-05 также должен сохраниться.

1. Начать authorize, получить pending login. `/login/context` содержит только доступные provider methods; кнопки ведут на `${AS}/login/provider/{provider}`. Отсутствующий/выключенный provider не начинает flow; прямой provider start без pending request и start уже вошедшего пользователя завершаются безопасным отказом. Произвольный provider/returnTo/issuer не становится redirect destination.
2. Нажать provider. AS создаёт nonce в своей session и отправляет браузер на `${BASE}/api/v1/auth/oauth/{provider}/start?target=oauth&binding=${BINDING}`; передаётся SHA-256 binding, сам nonce не идёт через URL. Gateway сохраняет отдельный защищённый browser binding cookie, auth-service сохраняет target/binding в server-side flow. Завершить provider callback и gateway callback: возврат идёт только на фиксированный `${AS}/login/provider/callback?code=...`, без JWT/refresh/provider tokens в URL.
3. Существующий ACTIVE A после одноразового claim получает новую AS session с ротацией session id/CSRF; сохраняются PKCE/resource/client state и исходный authorize request. Consent показывает A; Allow завершает обычный authorization code flow. Одновременный/повторный claim не создаёт вторую session; код/nonce другого браузера, отсутствующий cookie, изменённый state/target/binding и устаревший code отклоняются. Неуспешная проверка binding не даёт права на чужой flow.
4. На отдельной provider identity без существующего Taska аккаунта получить `SIGNUP_REQUIRED`: AS показывает предложение создать аккаунт штатно, но этот flow не создаёт Taska user и не выдаёт UI/AS токен. Неустановленный email verification, BLOCKED/LOCKED и отмена провайдера дают соответствующий различимый отказ; никакой успешной session. Нельзя подставить произвольный error или redirect, чтобы завершить вход.
5. Проверить раздельность target: обычный `target=ui` flow остаётся совместим с E2E-05, UI exchange не потребляет oauth sign-in code; OAuth claim не принимает UI grant. Неизвестный target и неверный binding отвергаются. Для POST callback поддерживаемого провайдера проверить нужный метод/content-type; неверный callback method не потребляет легитимный state. Rate-limit проверять на своей fixture, без блокировки общего IP.
6. В своём provider stub вернуть timeout, отказ и подменённые/повторные ответы; в своём auth-service проверить недоступность identity. Страницы не получают ложный успех, provider credentials/nonce/code не попадают в сообщения. Связать HTTP `X-Request-Id` с gRPC/ошибкой без записи secrets. После неудачи новый независимый flow всё ещё работает.

**Очистка:** отозвать grants только своих тестовых сессий/клиентов, удалить временные browser cookies/stub и токены. Не менять общие provider apps или профили браузеров. Если внешний provider/client недоступен, сохранить этот пробел во внешнем журнале/Jira, не в сценариях и не как продуктовый баг без воспроизведения расхождения.

## E2E-33. Публичный OAuth ingress и приватные HTTPS endpoints

**Условия:** согласованный собственный deployment с TAS-448; разрешённый публичный hostname AS и ресурс MCP, точные inspected Traefik IP/CIDR, отдельные service credentials. Изменения Compose, сетей, сертификатов и отказные ответы — только в собственной fixture; общую конфигурацию не менять ради негативного теста. Все HTTP-проверки выполнить после финального AI review на фактически развёрнутом revision.

1. Отрендерить Compose без optional overlays: OAuth profile не активен, bridge выключен. С обоими overlays и `--profile oauth`: persistent OAuth DB, независимые secrets, canonical issuer/resource, точный redirect allowlist, включённый только одобренный DCR/CIMD, loopback-only опубликованный AS порт. Private router использует socket `ClientIP` проверенного CIDR и Basic, auth-service резолвит canonical hostname в проверенный Traefik IP для HTTPS/SNI; доверие forwarded headers ограничено точным peer regex.
2. С публичной сети получить AS `/.well-known/oauth-authorization-server` и MCP `/.well-known/oauth-protected-resource` (также `/mcp` suffix metadata): issuer, resource, authorization/token/registration endpoints канонические. Неподдерживаемые Host/X-Forwarded-Host/Proto/Port не меняют issuer и сохранённый login redirect. `Accept-Encoding: gzip, br` не включает компрессию OAuth HTML/JSON. Обычные password/PAT HTTP и MCP вызовы по прежним сценариям работают.
3. Снаружи POST `${AS}/oauth2/introspect`, `/internal/grants/validate`, внутренние user/apps endpoints и GET `/actuator/health` не маршрутизируются в private handlers: 400/404 даже с поддельными X-Forwarded-For/Proto и действующим выделенным Basic. Из разрешённой внутренней auth-service сети с правильным Basic неизвестный token introspection возвращает 200 `active:false`; неверный/отсутствующий Basic отклонён. Для `/internal/grants/validate` с отсутствующими grant_id/token_id — положительный внутренний контроль 200 `active:false`.
4. Отправить raw request-target без нормализации (например `curl --path-as-is`) для POST `/login/..;/internal/grants/validate`, `/login/%2E%2E%2Finternal/grants/validate`, `/login%2f..%2finternal/grants/validate`, `/login%5c..%5cinternal/grants/validate`, с JSON собственного несуществующего grant и выделенным Basic. Ожидать 400/404, никогда 200 private-handler response. Выполнить через реальный ingress и AS: одной проверки PathPrefix rules либо mock upstream недостаточно для границы между proxy и backend decoding.
5. Проверить plan provisioner: нет writes/deploy/генерации secrets, вывод не содержит env/token values. Apply в своей deployment fixture сохраняет чужие env/domains, создаёт недостающие разные secrets и только разрешённые path records; повтор идемпотентен. Неожиданный host-wide `/`, private path record, rewrite/forwardAuth middleware, слабые/повторные secrets и конкурентная env/command правка отвергаются до перезаписи; backup вне Git имеет mode0600. API redirect не пересылает Dokploy key.
6. На своей fixture проверить полный enable→disable→enable: marker выбирает ожидаемый Compose command; rollback прекращает публичное обслуживание OAuth и выключает bridge, при этом OAuth volume/данные не удалены. Убедиться по контейнерам и HTTP, что ранее запущенный inactive-profile AS действительно остановлен; удаления `--profile` из команды недостаточно. После повторного включения сохранились данные/consents и штатно отзываются только собственные grants. Общий стенд не отключать ради этой проверки.

**Очистка:** остановить только собственную deployment fixture, удалить её временные маршруты/secret files по согласованной политике; сохранить volume, если он нужен для проверки восстановления. Никаких raw env, API responses или результатов прогона в Git.

## E2E-34. Полный OAuth flow настоящего MCP Inspector

**Условия:** установленный зафиксированный MCP Inspector CLI с известной версией, выделенные Taska A/O и браузер, доступный HTTPS MCP resource; разрешённый точный callback текущего клиента. Для проверенной конфигурации Inspector 2.12.0 используется DCR callback `http://127.0.0.1:6276/oauth/callback`; при смене версии сначала подтвердить фактический redirect клиента. Не расширять allowlist wildcard или предположительным callback. Хранилище токенов — отдельное временное вне Git, без пользовательской production-конфигурации.

1. Запустить настоящий клиент и подключиться к `${RESOURCE}` без заранее подставленного access/PAT. Получить protected-resource challenge/metadata, перейти к каноническому AS discovery, выполнить реальную DCR только с разрешённым redirect. Проверить `response_type=code`, S256, отдельный случайный state, resource и scopes `read offline_access`; не считать ручной curl обмен эквивалентом клиентскому discovery/connect.
2. Пройти browser login/consent из E2E-31 (и отдельно social при доступном настоящем provider из E2E-32). Callback слушается только на loopback, клиент сверяет state/issuer, получает code и сам обменивает его с verifier/resource. Выполнить реальное MCP initialize, tools/list и `whoami`/безопасное чтение собственного проекта: identity A, scopes и ACL согласованы, секреты/личное имя токена не возвращаются.
3. Перезапустить/переподключить тот же Inspector со своим сохранённым token store. Клиент использует штатный refresh/reconnect; старый refresh не переиспользуется после rotation, не появляется новая лишняя пара browser consent. Проверить обработку expired/отозванного refresh и `access_denied`: нет бесконечного login loop и ложного connected.
4. Не меняя пользовательских данных, проверить write tool с read token: 403 `insufficient_scope`, без gateway dispatch/записи. Новый независимый step-up с явно выбранным `read write` позволяет только ACL-разрешённую собственную операцию; повторная запись после потери ответа не выполняется автоматически. Для приёмки read-only клиента успешное расширение прав не предполагать.
5. Выполнить отзыв из E2E-35 и reconnect тем же Inspector/token store: отозванные credentials больше не авторизуют MCP, свежий flow требует разрешённого входа/согласия. Другой владелец/клиент продолжает работать. Сопоставить request_id безопасных HTTP/MCP вызовов без вывода auth URL/query/code/token store.

**Очистка:** штатно отозвать все grants этого запуска, остановить owned callback listener и удалить только временный Inspector token store. Недоступность отдельного клиента или внешнего провайдера отмечается как пробел внешней приёмки вне Git; успех Inspector не означает проверку Claude Code/Cursor/других клиентов.

## E2E-35. Scopes, отзыв одного grant и изоляция OAuth владельцев

**Условия:** A/O, два разрешённых клиента C1/C2, реальные OAuth read и read/write tokens, refresh families и делегированный MCP вызов. Подготовить у A минимум два живых grant C1 и один C2, у O — C1. Выделенный JWT и AS browser session используются для управления своими подключениями; исходные пользовательские данные общих аккаунтов не трогать.

1. Read OAuth token A/C1 читает только доступные A проекты/задачи. Известный валидный write tool получает HTTP403 `WWW-Authenticate: Bearer error="insufficient_scope", scope="write"`; БД и outbox неизменны. Read/write A/C1 меняет только разрешённый объект; чужой объект остаётся недоступен при любых scopes. Публичные management/admin endpoints не становятся доступны OAuth/delegated token по наличию write.
2. `GET ${AS}/oauth/grants` из browser session A показывает только A. Выбрать один собственный grant, выполнить `DELETE ${AS}/oauth/grants/{GRANT_ID}` с raw CSRF: 204; его access/refresh и уже созданная delegation при следующем online-check родителя не принимаются. Другой grant A/C1 и grant A/C2 продолжают работать; удаление consent само по себе не отзывает их. Чужой grant O, неизвестный id и DELETE без CSRF не затрагивают данные O и не получают успешный чужой отзыв.
3. Под JWT A выполнить `DELETE ${BASE}/api/v1/users/me/connected-apps/{APP_C1_ID}`: 204 отзывает уже все оставшиеся A/C1 grants и consent, как E2E-29. Проверить каждый ранее сохранённый access/refresh/delegation, а не только исчезновение app из списка. A/C2 и O/C1 продолжают работать. PAT/read/write/delegated credentials не могут выполнить этот session-only endpoint.
4. На собственном стеке остановить/задержать introspection или parent-grant validation: MCP/gateway/auth-service не принимают delegation из локального cache как живую и не объявляют ложный успешный отзыв. Ожидаемый 503/UNAVAILABLE различим от 401 отозванного токена; после восстановления валидные grant работают, отозванные не возрождаются. Проверить, что redirect от private revoke/upstream не пересылает Basic/Bearer на Location.
5. На новой отдельной family выполнить refresh rotation и replay старого refresh, а также конкурентный code exchange: исходный authorization code выдаёт токены ровно один раз; reuse refresh не расширяет scopes и не оживляет отозванную family. Проверить неверный resource/audience/issuer и просроченные credentials — отказ без gateway операции. Методы/тела и ожидаемую family-revocation политику сверять с актуальным OAuth backend контрактом, а не с поведением другого клиента.

**Очистка:** отозвать оставшиеся собственные grants C1/C2 у A/O, удалить только созданные этим сценарием объекты и временные token stores. Восстановить свою fault-injection fixture. Не удалять общий OAuth volume/согласия других пользователей.

## Как фиксировать расхождение

Результаты прогонов и доказательства хранить вне репозитория. В Jira для подтверждённого бага указывать: BASE/версию deployment и время, ID сценария, предусловия/роли, минимальные запросы с безопасными placeholders, фактический HTTP/domain code и поля, ожидаемое поведение со ссылкой на контракт/требование, requestId и влияние. Секреты и чужие данные удалить из доказательств. Проверять отсутствие дубля и связывать баг с TAS-439. Недоступная почта/бот/провайдер или неготовая фикстура — ограничение проверки, а не автоматически баг продукта.

## Соответствие операций сценариям

Таблица перечисляет публичные операции gateway. Для каждого семейства позитивная запись должна подтверждаться последующим чтением, негативная — неизменностью состояния и отсутствием побочных эффектов. Это список покрытия сценариями, не отчёт об исполнении.

| Метод и полный маршрут | operationId | Сценарий | Позитивный HTTP |
| --- | --- | --- | --- |
| `POST /api/v1/auth/signup` | `signup` | E2E-04 | 202 |
| `GET /api/v1/auth/methods` | `getAuthMethods` | E2E-01 | 200 |
| `GET /api/v1/users/me/connected-apps` | `listConnectedApps` | E2E-29 | 200 |
| `DELETE /api/v1/users/me/connected-apps/{appId}` | `revokeConnectedApp` | E2E-29 | 204 |
| `POST /api/v1/users/me/access-tokens` | `createPersonalAccessToken` | E2E-06 | 201 |
| `GET /api/v1/users/me/access-tokens` | `listPersonalAccessTokens` | E2E-06 | 200 |
| `DELETE /api/v1/users/me/access-tokens/{tokenId}` | `revokePersonalAccessToken` | E2E-06 | 204 |
| `GET /api/v1/users/me/notification-preferences` | `getNotificationPreferences` | E2E-24 | 200 |
| `PATCH /api/v1/users/me/notification-preferences` | `updateNotificationPreferences` | E2E-24 | 200 |
| `POST /api/v1/users/me/telegram/link` | `createTelegramLink` | E2E-25 | 200 |
| `POST /api/v1/users/me/telegram/confirm` | `confirmTelegramLink` | E2E-25 | 200 |
| `DELETE /api/v1/users/me/telegram` | `disconnectTelegram` | E2E-25 | 200 |
| `POST /api/v1/integrations/telegram/webhook` | `processTelegramWebhook` | E2E-25 | 200 |
| `POST /api/v1/auth/login` | `login` | E2E-02 | 200 |
| `POST /api/v1/auth/refresh` | `refresh` | E2E-02 | 200 |
| `POST /api/v1/auth/invitations` | `inviteUser` | E2E-03 | 201, 200 |
| `GET /api/v1/users` | `searchUsers` | E2E-07 | 200 |
| `GET /api/v1/auth/oauth/{provider}/start` | `startOAuth` | E2E-05 | 302 |
| `GET /api/v1/auth/oauth/{provider}/callback` | `completeOAuthGet` | E2E-05 | 302 |
| `POST /api/v1/auth/oauth/{provider}/callback` | `completeOAuthPost` | E2E-05 | 302 |
| `POST /api/v1/auth/oauth/exchange` | `exchangeOAuth` | E2E-05 | 200 |
| `POST /api/v1/auth/oauth/signup` | `signupOAuth` | E2E-05 | 200 |
| `POST /api/v1/auth/invitations/preview` | `previewInvitation` | E2E-03 | 200 |
| `POST /api/v1/auth/invitations/accept` | `setPasswordByToken` | E2E-03 | 204 |
| `PATCH /api/v1/users/me` | `updateMyProfile` | E2E-02 | 200 |
| `GET /api/v1/users/me` | `getMyInfo` | E2E-02 | 200 |
| `GET /api/v1/notifications` | `listNotifications` | E2E-24 | 200 |
| `PATCH /api/v1/notifications/{notificationId}/read` | `markNotificationAsRead` | E2E-24 | 200 |
| `POST /api/v1/notifications/read-all` | `markAllNotificationsAsRead` | E2E-24 | 200 |
| `POST /api/v1/projects` | `createProject` | E2E-08 | 201 |
| `GET /api/v1/projects` | `listMyProjects` | E2E-08 | 200 |
| `GET /api/v1/project-stats` | `getProjectStats` | E2E-08 | 200 |
| `DELETE /api/v1/projects/{projectId}` | `archiveProject` | E2E-23 | 200 |
| `GET /api/v1/projects/{projectId}` | `getProject` | E2E-08 | 200 |
| `PATCH /api/v1/projects/{projectId}` | `updateProject` | E2E-08 | 200 |
| `GET /api/v1/projects/{projectId}/context` | `getProjectContext` | E2E-08 | 200 |
| `GET /api/v1/projects/{projectId}/issue-types` | `listProjectIssueTypes` | E2E-09 | 200 |
| `POST /api/v1/projects/{projectId}/issue-types` | `createProjectIssueType` | E2E-09 | 201 |
| `PUT /api/v1/projects/{projectId}/issue-types/order` | `reorderProjectIssueTypes` | E2E-09 | 200 |
| `PATCH /api/v1/projects/{projectId}/issue-types/{code}` | `updateProjectIssueType` | E2E-09 | 200 |
| `DELETE /api/v1/projects/{projectId}/issue-types/{code}` | `deleteProjectIssueType` | E2E-13 | 204 |
| `GET /api/v1/projects/{projectId}/members` | `getProjectMembers` | E2E-08 | 200 |
| `POST /api/v1/projects/{projectId}/members` | `addProjectMember` | E2E-08 | 201 |
| `PATCH /api/v1/projects/{projectId}/members/{userId}` | `changeProjectMemberRole` | E2E-08 | 200 |
| `DELETE /api/v1/projects/{projectId}/members/{userId}` | `removeProjectMember` | E2E-08 | 204 |
| `PATCH /api/v1/admin/users/{userId}` | `updateUserDisplayName` | E2E-26 | 200 |
| `POST /api/v1/admin/users/{userId}/block` | `blockUser` | E2E-26 | 200 |
| `POST /api/v1/admin/users/{userId}/unblock` | `unblockUser` | E2E-26 | 200 |
| `POST /api/v1/projects/{projectId}/issues` | `createIssue` | E2E-14 | 201 |
| `GET /api/v1/projects/{projectId}/issues` | `listIssues` | E2E-17 | 200 |
| `GET /api/v1/issues/{issueId}/links` | `listIssueLinks` | E2E-21 | 200 |
| `POST /api/v1/issues/{issueId}/links` | `createIssueLink` | E2E-21 | 201 |
| `DELETE /api/v1/issues/{issueId}/links/{linkId}` | `deleteIssueLink` | E2E-21 | 204 |
| `GET /api/v1/issues/{issueId}` | `getIssue` | E2E-14 | 200 |
| `PUT /api/v1/issues/{issueId}` | `updateIssue` | E2E-15 | 200 |
| `DELETE /api/v1/issues/{issueId}` | `deleteIssue` | E2E-23 | 204 |
| `PATCH /api/v1/issues/{issueId}` | `patchIssue` | E2E-15 | 200 |
| `GET /api/v1/issues/by-key/{issueKey}` | `getIssueByKey` | E2E-14 | 200 |
| `PUT /api/v1/issues/{issueId}/assignee` | `assignIssue` | E2E-16 | 200 |
| `PUT /api/v1/issues/{issueId}/transition/{transitionId}` | `transitionIssue` | E2E-16 | 200 |
| `GET /api/v1/issues/search` | `searchIssues` | E2E-17 | 200 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/worklogs` | `listIssueWorklogs` | E2E-20 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/worklogs` | `addIssueWorklog` | E2E-20 | 201 |
| `PUT /api/v1/projects/{projectId}/issues/{issueId}/worklogs/{worklogId}` | `updateIssueWorklog` | E2E-20 | 200 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/worklogs/{worklogId}` | `deleteIssueWorklog` | E2E-20 | 204 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/attachments` | `listAttachments` | E2E-22 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/attachments/upload-url` | `createAttachmentUploadUrl` | E2E-22 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/attachments/confirm` | `confirmAttachmentUpload` | E2E-22 | 201 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}/download-url` | `getAttachmentDownloadUrl` | E2E-22 | 200 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}` | `deleteAttachment` | E2E-22 | 204 |
| `GET /api/v1/projects/{projectId}/board` | `getBoard` | E2E-18 | 200 |
| `GET /api/v1/projects/{projectId}/board/columns/{statusKey}/issues` | `listBoardColumnIssues` | E2E-18 | 200 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/comments` | `listComments` | E2E-19 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/comments` | `addComment` | E2E-19 | 200 |
| `PUT /api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}` | `updateComment` | E2E-19 | 200 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}` | `deleteComment` | E2E-19 | 204 |
| `GET /api/v1/projects/{projectId}/workflow` | `getWorkflowForProject` | E2E-10 | 200 |
| `GET /api/v1/projects/{projectId}/workflows` | `listProjectWorkflows` | E2E-10 | 200 |
| `POST /api/v1/projects/{projectId}/workflows` | `createProjectWorkflow` | E2E-10 | 201 |
| `POST /api/v1/projects/{projectId}/workflows/{workflowId}/statuses` | `addWorkflowStatus` | E2E-10 | 201 |
| `POST /api/v1/projects/{projectId}/workflows/{workflowId}/transitions` | `addWorkflowTransition` | E2E-10 | 201 |
| `GET /api/v1/projects/{projectId}/labels` | `listProjectLabels` | E2E-21 | 200 |
| `POST /api/v1/projects/{projectId}/labels` | `createProjectLabel` | E2E-21 | 201 |
| `PATCH /api/v1/projects/{projectId}/labels/{labelId}` | `updateProjectLabel` | E2E-21 | 200 |
| `DELETE /api/v1/projects/{projectId}/labels/{labelId}` | `deleteProjectLabel` | E2E-21 | 204 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/labels` | `listIssueLabels` | E2E-21 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/labels` | `addIssueLabel` | E2E-21 | 201 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/labels/{labelId}` | `removeIssueLabel` | E2E-21 | 204 |
| `GET /api/v1/projects/{projectId}/issues/{issueId}/watchers` | `listIssueWatchers` | E2E-21 | 200 |
| `POST /api/v1/projects/{projectId}/issues/{issueId}/watchers` | `addIssueWatcher` | E2E-21 | 200 |
| `PUT /api/v1/projects/{projectId}/issues/{issueId}/watchers/me` | `watchIssueMe` | E2E-21 | 200 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/watchers/me` | `unwatchIssueMe` | E2E-21 | 200 |
| `DELETE /api/v1/projects/{projectId}/issues/{issueId}/watchers/{userId}` | `removeIssueWatcher` | E2E-21 | 200 |
| `GET /api/v1/readonly/{service}/{table}` | `listTableRows` | E2E-27 | 200 |
| `GET /api/v1/readonly/audit-entries` | `listAuditEntries` | E2E-27 | 200 |
| `GET /api/v1/readonly/{service}/{table}/{id}` | `getTableRowById` | E2E-27 | 200 |
| `GET /api/v1/readonly/outbox/problematic-summary` | `getProblematicOutboxEventsSummary` | E2E-27 | 200 |
| `GET /api/v1/readonly/catalog` | `getCatalog` | E2E-27 | 200 |
| `POST /api/v1/admin/outbox/{service}/{eventId}/retry` | `retryOutboxEvent` | E2E-27 | 200 |
| `POST /api/v1/admin/users/{userId}/reset-lockout` | `resetCredentialLockout` | E2E-26 | 200 |
| `POST /api/v1/users/me/avatar/upload-url` | `createAvatarUploadUrl` | E2E-22 | 200 |
| `POST /api/v1/users/me/avatar/confirm` | `confirmAvatarUpload` | E2E-22 | 200 |
| `DELETE /api/v1/users/me/avatar` | `deleteMyAvatar` | E2E-22 | 204 |
| `GET /api/v1/users/{userId}/avatar` | `getAvatarDownloadUrl` | E2E-22 | 200 |
| `GET /api/v1/meta` | `getMeta` | E2E-01 | 200, 304 |
| `PATCH /api/v1/projects/{projectId}/workflows/{workflowId}` | `updateProjectWorkflow` | E2E-11 | 200 |
| `DELETE /api/v1/projects/{projectId}/workflows/{workflowId}` | `deleteProjectWorkflow` | E2E-12 | 200 |
| `PATCH /api/v1/projects/{projectId}/workflows/{workflowId}/statuses/{statusId}` | `updateWorkflowStatus` | E2E-10 | 200 |
| `DELETE /api/v1/projects/{projectId}/workflows/{workflowId}/statuses/{statusId}` | `deleteWorkflowStatus` | E2E-12 | 200 |
| `PATCH /api/v1/projects/{projectId}/workflows/{workflowId}/transitions/{transitionId}` | `updateWorkflowTransition` | E2E-10 | 200 |
| `DELETE /api/v1/projects/{projectId}/workflows/{workflowId}/transitions/{transitionId}` | `deleteWorkflowTransition` | E2E-10 | 200 |
