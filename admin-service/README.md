# admin-service

Backend для platform admin console.

## Read-only доступ к БД сервисов

`admin-service` поднимает **5 отдельных** R2DBC connection + pool к:

- auth DB
- project DB
- issue DB
- workflow DB
- notification DB

Своя БД `admin_db` остаётся в `spring.r2dbc.*` (с Liquibase).

Health компоненты: `authDb`, `projectDb`, `issueDb`, `workflowDb`, `notificationDb`.

```bash
curl http://127.0.0.1:8086/actuator/health
```

### Env variables (Docker / Dokploy)

Внутри compose host имени БД — контейнеры (`auth-db`, …), порт Postgres **5432**.

```env
# auth
ADMIN_RO_AUTH_R2DBC_URL=r2dbc:postgresql://auth-db:5432/auth_db
ADMIN_RO_AUTH_R2DBC_USERNAME=admin_ro_auth
ADMIN_RO_AUTH_R2DBC_PASSWORD=change-me
ADMIN_RO_AUTH_POOL_INITIAL_SIZE=2
ADMIN_RO_AUTH_POOL_MAX_SIZE=10
ADMIN_RO_AUTH_POOL_MAX_IDLE_TIME_MINUTES=10

# project
ADMIN_RO_PROJECT_R2DBC_URL=r2dbc:postgresql://project-db:5432/project_db
ADMIN_RO_PROJECT_R2DBC_USERNAME=admin_ro_project
ADMIN_RO_PROJECT_R2DBC_PASSWORD=change-me
ADMIN_RO_PROJECT_POOL_INITIAL_SIZE=2
ADMIN_RO_PROJECT_POOL_MAX_SIZE=10
ADMIN_RO_PROJECT_POOL_MAX_IDLE_TIME_MINUTES=10

# issue
ADMIN_RO_ISSUE_R2DBC_URL=r2dbc:postgresql://issue-db:5432/issue_db
ADMIN_RO_ISSUE_R2DBC_USERNAME=admin_ro_issue
ADMIN_RO_ISSUE_R2DBC_PASSWORD=change-me
ADMIN_RO_ISSUE_POOL_INITIAL_SIZE=2
ADMIN_RO_ISSUE_POOL_MAX_SIZE=10
ADMIN_RO_ISSUE_POOL_MAX_IDLE_TIME_MINUTES=10

# workflow
ADMIN_RO_WORKFLOW_R2DBC_URL=r2dbc:postgresql://workflow-db:5432/workflow_db
ADMIN_RO_WORKFLOW_R2DBC_USERNAME=admin_ro_workflow
ADMIN_RO_WORKFLOW_R2DBC_PASSWORD=change-me
ADMIN_RO_WORKFLOW_POOL_INITIAL_SIZE=2
ADMIN_RO_WORKFLOW_POOL_MAX_SIZE=10
ADMIN_RO_WORKFLOW_POOL_MAX_IDLE_TIME_MINUTES=10

# notification
ADMIN_RO_NOTIFICATION_R2DBC_URL=r2dbc:postgresql://notification-db:5432/notification_db
ADMIN_RO_NOTIFICATION_R2DBC_USERNAME=admin_ro_notification
ADMIN_RO_NOTIFICATION_R2DBC_PASSWORD=change-me
ADMIN_RO_NOTIFICATION_POOL_INITIAL_SIZE=2
ADMIN_RO_NOTIFICATION_POOL_MAX_SIZE=10
ADMIN_RO_NOTIFICATION_POOL_MAX_IDLE_TIME_MINUTES=10
```

### Важно

- На проде / стенде **не** использовать superuser / `taska` для RO.
- У каждого datasource свой user с правами только `SELECT`.
- Локальные defaults в `application.yml` временно используют `taska`, чтобы можно было запускать без RO users.
