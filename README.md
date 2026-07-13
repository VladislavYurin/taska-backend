# АС Tasks

Система управления задачами и процессами разработки (аналог Jira)

## Архитектурная схема

```mermaid
flowchart LR
    U[Web UI] -->|HTTPS/REST| GW[API Gateway / BFF]

    GW -->|gRPC| AUTH[auth-service]
    GW -->|gRPC| PROJ[project-service]
    GW -->|gRPC| ISSUE[issue-service]
    GW -->|gRPC| WF[workflow-service]
    GW -->|gRPC| NOTIF_API[notification-service<br/>in-app inbox API]

    ISSUE -->|gRPC: CheckProjectRole| PROJ
    ISSUE -->|gRPC: ValidateTransition| WF

    subgraph K[Kafka]
        IE[(topic: issue.events)]
        PE[(topic: project.events)]
        UE[(topic: user.events)]
    end

    ISSUE --> IE
    PROJ --> PE
    AUTH --> UE

    IE --> NOTIF[notification-service<br/>event consumer]
    PE --> NOTIF
    UE --> NOTIF

    AUTH --- ADB[(PostgreSQL<br/>auth DB)]
    PROJ --- PDB[(PostgreSQL<br/>project DB)]
    ISSUE --- IDB[(PostgreSQL<br/>issue DB)]
    WF --- WDB[(PostgreSQL<br/>workflow DB)]
    NOTIF --- NDB[(PostgreSQL<br/>notification DB)]

    ISSUE --- OUT[(Outbox table<br/>in issue DB)]
    PROJ --- POUT[(Outbox table<br/>in project DB)]
    AUTH --- UOUT[(Outbox table<br/>in auth DB)]

%% Optional extensions (not required for MVP)
    IE -.-> SEARCH[(optional)]
SEARCH -.-> ES[(OpenSearch/Elastic)]
```

## Swagger API Gateway

TODO


## Kafka-UI
#### Запуск

Из корня проекта, где лежит `docker-compose.yml`:

```bash
    docker compose up -d --build kafbat-ui
```
Если нужно поднять весь стек (Kafka + Kafka UI + остальные сервисы) — выполните обычную полную сборку:
```bash
    docker compose up -d --build
```

#### Доступ

После запуска интерфейс доступен по адресу:

**[http://localhost:8088](http://localhost:8088)**

Название кластера в UI: `taska-kafka`.

## Логирование (Loki + Promtail + Grafana)
#### Запуск

Из корня проекта, где лежит `docker-compose.yml`:
```bash
    docker compose up -d loki promtail grafana
```
Если нужно поднять весь стек (Loki + Promtail + Grafana + остальные сервисы) — выполните обычную полную сборку:
```bash
    docker compose up -d --build
```

#### Доступ к интерфейсу
* **Grafana URL**: [http://localhost:3000](http://localhost:3000)
* **Логин/Пароль**: `admin` / `admin` (переменные заданы в `.env.docker`)

#### Как посмотреть логи:
1. Перейдите в Grafana по адресу `http://localhost:3000`.
2. В левом меню выбрать **Dashboards** (Дашборды).
3. Открыть дашборд **AC Taska Logs**.
4. Отобразится дашборд логов. Сверху доступны фильтры:
    * **Сервис**: выбор конкретного микросервиса (`auth-service`, `issue-service` и т.д.). Можно выбрать несколько.
    * **Request ID**: текстовый поиск по requestId запроса.
    * **Node ID**: текстовый поиск по nodeId.
    * **Уровень лога**: фильтрация по уровню логирования (`INFO`, `WARN`, `ERROR` и т.д.). Можно выбрать несколько.

## admin-service

Backend-слой для **platform admin console**.  
Не подключается к пользовательскому frontend и не участвует в обычном user flow через `api-gateway`.

#### Порты (с хоста)

| Назначение | Порт |
|------------|------|
| HTTP / Actuator | `8086` |
| gRPC | `9097` |
| PostgreSQL (`taska_admin_db`) | `5438` |

#### Запуск

Из корня проекта, где лежит `docker-compose.yml`:

```bash
docker compose up -d --build admin-db admin-service
```

Проверка health:

```bash
curl http://localhost:8086/actuator/health
```

Ожидаемый ответ: `{"status":"UP", ...}`.

## Билд и деплой

### Деплой в Docker

### Локальный запуск