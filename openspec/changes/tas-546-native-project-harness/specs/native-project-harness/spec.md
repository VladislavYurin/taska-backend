## Purpose

Owned native project runner даёт воспроизводимые сведения о создании, ACL, квоте, отказах и recovery на настоящих сервисах без ослабления production isolation.

## ADDED Requirements

### Requirement: Native fixture предоставляет необходимые источники
Fixture SHALL предоставлять минимальные trusted source dependencies всем участвующим сервисам, сохраняя точные SERVICE identity/purpose/scopes и отсутствие конфликтующих дубликатов grants. REST/proto/schema не меняются.

#### Scenario: Полный project context
- **WHEN** разрешённый actor создаёт native проект и читает его context
- **THEN** реальные project/workflow/issue primaries возвращают согласованные данные в своём tenant; foreign tenant/project и неверный purpose отклоняются

#### Scenario: Наследуемые fixture
- **WHEN** используются project, workflow, issue и производные owned runner
- **THEN** source grants остаются однозначными и ограниченными, ранее допустимые consumers работают, а чужие scopes не появляются

### Requirement: Негативные сценарии проверяют причину и состояние
Runner SHALL сверять ожидаемые ответы с текущим авторитетным REST и транспортным mapper и проверять доменное состояние, quota и outbox после отказа, сохраняя исходные request identities при повторе.

#### Scenario: Replay и отказ нового создания
- **WHEN** повторяется принятая команда, конфликтует её payload либо новая команда отклоняется admission
- **THEN** replay не создаёт вторую resource/event/quota, конфликт и отказ не меняют принятую resource; причина отказа не подменяется названием другого сценария

### Requirement: Компенсация и recovery проверяются раздельно
Runner SHALL отдельно проверять синхронную компенсацию подтверждённого отказа и bounded recovery неизвестного исхода с исходной tenant/operation/resource identity.

#### Scenario: Подтверждённый отказ bootstrap
- **WHEN** owned company не имеет нужного workflow template и конкурирующий recovery не выбран для этой company
- **THEN** проект и его grants отсутствуют, quota освобождена, успешного ProjectCreated нет, а committed provisioning history и cancellation tombstone сохраняются

#### Scenario: Неизвестный исход принятой операции
- **WHEN** workflow недоступен после принятия операции, затем возвращается после отзыва initiator и flag-off
- **THEN** слот удержан до подтверждения, partial project скрыт, worker завершает ту же resource один раз без нового admission, а новые создания запрещены

### Requirement: Приёмка отражает проверенную версию
Runner SHALL сохранять source revision, фактический scope и signed cleanup. Полный PASS допускается только после всех заявленных проверок; чужая среда не используется.

#### Scenario: Финальный прогон
- **WHEN** полный native project runner выполнен после актуального review
- **THEN** результат относится к проверенному head, все заявленные сценарии выполнены, cleanup PASS и собственных остатков нет; доставка Kafka и shared stage не выдаются за проверенные
