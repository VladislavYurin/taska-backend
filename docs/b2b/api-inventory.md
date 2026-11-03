# Инвентаризация REST, RPC и MCP — TAS-468

Проверенный code baseline: `af7ab57ead44304b89bbd47dabeeba45b761ce16`. Это инвентаризация исходного кода, не результат проверки развёртывания. Бизнес-цель: [D01–D249, Confluence v39](ai-business-spec.md). Целевые схемы: [contracts](contracts/README.md).

Полный машиночитаемый перечень: [api-operations.json](inventory/api-operations.json). Каждая запись содержит метод/путь либо full RPC/tool name, handler с исходником и строкой, baseline, текущую область, целевую политику и implementation TAS. Публичный REST берётся из статического OpenAPI; RPC — из всех service blocks всех proto; MCP — из зарегистрированных tools.

| Поверхность | Количество |
|---|---:|
| GRPC | 148 |
| HTTP_CONTROLLER | 15 |
| HTTP_PROTOCOL | 11 |
| MCP_TOOL | 22 |
| REST_OPENAPI | 111 |

Итого 307 записей. 111 REST-операций имеют найденные handlers; все 148 RPC имеют найденные реализации. Для части RPC список содержит adapter и delegated service: оба места требуют проверки tenant-контекста. Inventory не утверждает, что одно наличие метода доказывает корректность ACL.

## Расхождения OpenAPI и фактических точек входа

Все operationId статического OpenAPI сопоставлены controller handlers. У пяти OAuth handlers и Telegram webhook Java-имя отличается от operationId; сопоставление зафиксировано явно, это не пропущенная операция. CommentController задаёт mappings вручную, остальные основные контроллеры реализуют сгенерированные интерфейсы; проверять только annotations нельзя.

Следующие 15 annotated HTTP routes не входят в static OpenAPI:

| Метод и путь | Handler / baseline | Целевая политика |
|---|---|---|
| `GET /oauth/consent` | `OAuthBackendController#consent` — [OAuthBackendController.java:45](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthBackendController.java#L45) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /oauth/grants` | `OAuthBackendController#grants` — [OAuthBackendController.java:88](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthBackendController.java#L88) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `DELETE /oauth/grants/{id}` | `OAuthBackendController#revoke` — [OAuthBackendController.java:113](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthBackendController.java#L113) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `POST /internal/grants/validate` | `OAuthBackendController#validate` — [OAuthBackendController.java:124](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthBackendController.java#L124) | `TENANT_RESOURCE_ACL_CAPABILITY` |
| `POST /oauth/register` | `OAuthBackendController#register` — [OAuthBackendController.java:157](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthBackendController.java#L157) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /internal/users/{userId}/apps` | `ConnectedAppController#list` — [ConnectedAppController.java:18](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/ConnectedAppController.java#L18) | `TENANT_DIRECTORY` |
| `DELETE /internal/users/{userId}/apps/{appId}` | `ConnectedAppController#revoke` — [ConnectedAppController.java:23](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/ConnectedAppController.java#L23) | `TENANT_DIRECTORY` |
| `GET /login/provider/{provider}` | `SocialLoginController#start` — [SocialLoginController.java:45](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/SocialLoginController.java#L45) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /login/provider/callback` | `SocialLoginController#callback` — [SocialLoginController.java:68](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/SocialLoginController.java#L68) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /login/context` | `LoginPageController#context` — [LoginPageController.java:26](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/LoginPageController.java#L26) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /api/gateway/me` | `ExampleController#testEndpoint` — [ExampleController.java:23](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/api-gateway/src/main/java/ru/taska/controller/ExampleController.java#L23) | `REMOVE_OR_LOCAL_TEST_ONLY` |
| `GET /api/gateway/public` | `ExampleController#testNotSecuredEndpoint` — [ExampleController.java:28](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/api-gateway/src/main/java/ru/taska/controller/ExampleController.java#L28) | `REMOVE_OR_LOCAL_TEST_ONLY` |
| `GET /v3/api-docs` | `ApiContractController#getContract` — [ApiContractController.java:25](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/api-gateway/src/main/java/ru/taska/controller/ApiContractController.java#L25) | `PUBLIC_SAFE_METADATA` |
| `GET /.well-known/oauth-protected-resource` | `OAuthProtectedResourceMetadata#metadata` — [OAuthProtectedResourceMetadata.java:18](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/OAuthProtectedResourceMetadata.java#L18) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `GET /.well-known/oauth-protected-resource/mcp` | `OAuthProtectedResourceMetadata#metadata` — [OAuthProtectedResourceMetadata.java:18](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/OAuthProtectedResourceMetadata.java#L18) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |

`/api/gateway/me` возвращает GatewayContext, а `/api/gateway/public` — публичный диагностический контекст: в production B2B удалить либо жёстко ограничить test profile (TAS-316); их отсутствие в OpenAPI не является защитой. `/v3/api-docs` — публикация контракта, её не требуется добавлять в бизнес-OpenAPI. OAuth internal endpoints остаются private service-authenticated, их нельзя считать пользовательскими routes по отсутствию static contract.

HTTP protocol/view handlers задаются Spring Security, view registry и MCP SDK:

| Метод и путь | Владелец | Политика |
|---|---|---|
| `GET /.well-known/oauth-authorization-server` | `OAuth2AuthorizationServerMetadataEndpointFilter` — [OAuthSecurityConfiguration.java:45](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L45) | `PUBLIC_SAFE_METADATA` |
| `GET /oauth2/authorize` | `OAuth2AuthorizationEndpointFilter` — [OAuthSecurityConfiguration.java:112](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L112) | `OAUTH_BROWSER_CONSENT` |
| `POST /oauth2/authorize` | `OAuth2AuthorizationEndpointFilter` — [OAuthSecurityConfiguration.java:112](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L112) | `OAUTH_BROWSER_CONSENT` |
| `POST /oauth2/token` | `OAuth2TokenEndpointFilter` — [OAuthSecurityConfiguration.java:51](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L51) | `OAUTH_PROTOCOL_BOUND_GRANT` |
| `POST /oauth2/revoke` | `OAuth2TokenRevocationEndpointFilter` — [OAuthSecurityConfiguration.java:153](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L153) | `OAUTH_PROTOCOL_BOUND_GRANT` |
| `POST /oauth2/introspect` | `OAuth2TokenIntrospectionEndpointFilter` — [OAuthSecurityConfiguration.java:112](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L112) | `SERVICE_AUTHENTICATED` |
| `GET /login` | `LoginPageController.Pages view controller` — [LoginPageController.java:36](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/LoginPageController.java#L36) | `PUBLIC_AUTH_UI` |
| `GET /consent` | `LoginPageController.Pages view controller` — [LoginPageController.java:37](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/LoginPageController.java#L37) | `OAUTH_BROWSER_CONSENT` |
| `POST /login` | `UsernamePasswordAuthenticationFilter` — [OAuthSecurityConfiguration.java:358](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L358) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `POST /logout` | `LogoutFilter` — [OAuthSecurityConfiguration.java:112](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java#L112) | `AUTH_PROTOCOL_OR_GLOBAL_IDENTITY` |
| `POST /mcp` | `MCP Streamable HTTP SDK` — [application.yml:17](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/resources/application.yml#L17) | `TENANT_BOUND_INTEGRATION` |

MCP baseline использует `protocol: STATELESS`: каталог отражает POST `/mcp`, не обещает GET/SSE или DELETE stateful session support. Bootstrap/discovery, OAuth code/token/revoke и browser login проверяются отдельно от бизнес-инструментов. Framework static assets, `/openapi.yml`, справочный `/v3/api-docs/generated`, Swagger UI и Actuator health/info/prometheus — публикация/эксплуатация, не дополнительные доменные команды; их network exposure проверяется в rollout.

## Все текущие RPC

| Proto | RPC |
|---|---:|
| [v1/admin-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/admin-service.proto) | 10 |
| [v1/auth-service/admin-invite-user.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/auth-service/admin-invite-user.proto) | 1 |
| [v1/auth-service/admin-user-management.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/auth-service/admin-user-management.proto) | 4 |
| [v1/auth-service/profile.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/auth-service/profile.proto) | 7 |
| [v1/auth-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/auth-service.proto) | 7 |
| [v1/issue-attachment.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/issue-attachment.proto) | 5 |
| [v1/issue-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/issue-service.proto) | 36 |
| [v1/issue-type-deletion.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/issue-type-deletion.proto) | 6 |
| [v1/notification-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/notification-service.proto) | 3 |
| [v1/notification-settings.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/notification-settings.proto) | 6 |
| [v1/oauth-identity.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/oauth-identity.proto) | 5 |
| [v1/oauth.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/oauth.proto) | 6 |
| [v1/personal-access-token.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/personal-access-token.proto) | 3 |
| [v1/project-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/project-service.proto) | 22 |
| [v1/user-directory.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/user-directory.proto) | 1 |
| [v1/workflow-binding-operations.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/workflow-binding-operations.proto) | 11 |
| [v1/workflow-service.proto](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/grpc-common-lib/src/main/proto/v1/workflow-service.proto) | 15 |

`common.proto` не объявляет service: `Header.request_id=1`, `node_id=2`, `action_channel=3`, `token_id=4`; `UserContext` имеет reserved `2`/`login`, далее email=3, display_name=4, status=5, global_role=6, token_kind=7, token_id=8, scopes=9, token_expires_at=10. Номера не переиспользуются. Новая библиотека контекста — [b2b.proto](contracts/b2b.proto), без редактирования baseline proto/generated code.

## Все текущие MCP tools

| Tool | Scope | Handler |
|---|---|---|
| `whoami` | `read` | `ReadTools#whoami` — [ReadTools.java:30](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L30) |
| `list_projects` | `read` | `ReadTools#listProjects` — [ReadTools.java:34](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L34) |
| `get_project` | `read` | `ReadTools#getProject` — [ReadTools.java:38](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L38) |
| `search_issues` | `read` | `ReadTools#searchIssues` — [ReadTools.java:43](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L43) |
| `get_issue` | `read` | `ReadTools#getIssue` — [ReadTools.java:48](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L48) |
| `list_comments` | `read` | `ReadTools#listComments` — [ReadTools.java:53](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L53) |
| `list_worklogs` | `read` | `ReadTools#listWorklogs` — [ReadTools.java:58](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L58) |
| `get_board` | `read` | `ReadTools#getBoard` — [ReadTools.java:63](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L63) |
| `search_users` | `read` | `ReadTools#searchUsers` — [ReadTools.java:68](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L68) |
| `list_notifications` | `read` | `ReadTools#listNotifications` — [ReadTools.java:73](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/ReadTools.java#L73) |
| `create_issue` | `write` | `WriteTools#createIssue` — [WriteTools.java:44](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L44) |
| `update_issue` | `write` | `WriteTools#updateIssue` — [WriteTools.java:52](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L52) |
| `transition_issue` | `write` | `WriteTools#transitionIssue` — [WriteTools.java:59](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L59) |
| `assign_issue` | `write` | `WriteTools#assignIssue` — [WriteTools.java:66](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L66) |
| `add_comment` | `write` | `WriteTools#tools (inline lambda)` — [WriteTools.java:73](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L73) |
| `log_work` | `write` | `WriteTools#logWork` — [WriteTools.java:85](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L85) |
| `add_label` | `write` | `WriteTools#tools (inline lambda)` — [WriteTools.java:92](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L92) |
| `remove_label` | `write` | `WriteTools#tools (inline lambda)` — [WriteTools.java:98](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L98) |
| `link_issues` | `write` | `WriteTools#linkIssues` — [WriteTools.java:104](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L104) |
| `watch_issue` | `write` | `WriteTools#tools (inline lambda)` — [WriteTools.java:110](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L110) |
| `unwatch_issue` | `write` | `WriteTools#tools (inline lambda)` — [WriteTools.java:115](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L115) |
| `mark_notifications_read` | `write` | `WriteTools#markNotification` — [WriteTools.java:120](https://github.com/VladislavYurin/taska-backend/blob/af7ab57ead44304b89bbd47dabeeba45b761ce16/mcp-server/src/main/java/ru/taska/mcp/WriteTools.java#L120) |

MCP resource/prompt/resolver paths также получают tenant из аутентифицированного grant, не из resource URI, prompt или аргумента tool. Новые компании/биллинг не становятся MCP tools автоматически: будущие инструменты обязаны вызывать эти же gateway policies, а отдельный DTO whitelist и catalog snapshot входят в implementation.

## Target policy

Baseline не содержит tenant isolation. `currentScope` явно помечает это для каждой записи; существующие user/project ACL не эквивалентны tenant boundary. `currentEndpointSecurity`/`scope` для REST фиксируют текущий gateway guard (включая shared helper для OAuth callback и workflow mutations); для MCP scope — read/write. `targetPolicy` — проектируемая классификация, а не заявление об уже действующей tenant-защите; для внутренних RPC поле scope обозначает целевую область.

| Policy | Обязательное целевое поведение |
|---|---|
| TENANT_* | Signed target tenant совпадает с ресурсом/route; актуальное membership, project ACL, capabilities и квоты. GET/list/search/batch проверяют ту же область. |
| GLOBAL_SELF_SESSION_FOR_MUTATION | Общий профиль/credentials/avatar и личная привязка Telegram; для изменения только web-session по D221, чтение профиля другого участника ограничено directory ACL. |
| AUTH_PROTOCOL_OR_GLOBAL_IDENTITY | Identity/token flow не выбирает tenant из внешнего header; авторизация/выдача нового PAT и refresh различаются. |
| ADMIN_EXPLICIT_SCOPE | Полная административная web-session либо отдельный administrative PAT/OAuth; role + grant scope + level + target, account-management отдельно. |
| PUBLIC_SAFE_METADATA | Только открытые метаданные, без секретов, private actor context или tenant-проекций. |
| VERIFIED_INTEGRATION | Проверенный callback, inbox дедупликации; внешний payload не является actor/tenant authority. |
| SERVICE_AUTHENTICATED | mTLS peer + service allowlist + bound request assertion, online recheck владельца операции. |
| TENANT_BOUND_INTEGRATION_AND_DOMAIN_ACL | read/write scopes не расширяют выбранную компанию; MCP/REST downstream проверяют одинаковые условия. |

В проектных routes `projectId` из пути и issue/comment/file из payload должны совпасть по tenant и владельцу; загрузить по одному ID и довериться остальным нельзя. Для issue links проверяются оба конца. Файлы, directory, aggregate counts и admin generic read-only tables входят в тот же принцип. Нормативные политики каналов и ошибки — [contracts README](contracts/README.md).

Настройки обычных уведомлений `GET/PATCH /api/v1/users/me/notification-preferences` и RPC `GetPreferences`/`UpdatePreferences` требуют выбранную компанию: `TENANT_SELF`, ключ `(tenant_id,user_id)` в `notification_preferences`. Префикс `/users/me` не делает эти настройки глобальными. Привязка Telegram (`POST /api/v1/users/me/telegram/link`, `POST .../confirm`, `DELETE /api/v1/users/me/telegram` и RPC `CreateTelegramLink`/`ConfirmTelegramLink`/`DisconnectTelegram`) принадлежит глобальному `account_notification_settings`: `GLOBAL_SELF_SESSION_FOR_MUTATION`, без копирования привязки по компаниям. Webhook/`ProcessTelegramUpdate` сохраняют `VERIFIED_INTEGRATION`. Вся эта группа принадлежит TAS-325; разделение согласовано с [моделью данных](target-data-model.md) и `notification.preferences` в [матрице доступа](inventory/access-matrix.json).

## Повторяемая проверка

```sh
python3 docs/b2b/inventory/generate_api_inventory.py
python3 docs/b2b/inventory/generate_api_inventory.py --check
python3 docs/b2b/contracts/validate_contracts.py
```

Генератор использует Python stdlib и локальный Ruby/Psych для baseline YAML. Validator требует PyYAML/openapi-spec-validator. Генератор рассчитан на указанный baseline: при будущем изменении baseline переснять и явно изменить SHA, а не выдавать новые данные за старый срез. Runtime-тесты из этой инвентаризации не следуют; target схемы не реализованы и не развёрнуты.

Генератор проверяет наличие и классификацию всех 12 текущих REST/RPC операций настроек/Telegram, включая границу tenant/global и владельца TAS-325. `--check` дополнительно сравнивает полный результат с сохранённым JSON и не пишет файлы.
