# tenant-attachment-lifecycle Specification

## Purpose

Защищать чтение tenant-вложений текущими полномочиями и доводить принятое удаление до доказанной очистки без потери retry и повторного освобождения bytes.

## Requirements

### Requirement: Чтение проверяет текущие границы доступа

Система SHALL отдавать metadata и содержимое только CONFIRMED вложений в текущих tenant/project/issue после актуальных credentials/scopes, ACL и capability. Отключение upload SHALL сохранять разрешённое чтение.

#### Scenario: Чужой объект и отозванное участие
- **WHEN** actor подставляет чужой tenant/project/issue/attachment либо его доступ отозван
- **THEN** список, download path и содержимое недоступны, приватные metadata и S3 URL не раскрываются

#### Scenario: Разрешённое чтение при выключенном upload
- **WHEN** новые uploads отключены, а actor сохраняет read и текущий ACL
- **THEN** actor получает список CONFIRMED вложений и может скачать выбранное вложение

### Requirement: Download остаётся авторизованным во время потока

Система SHALL передавать закреплённую final version через gateway с GET/HEAD и одиночным Range, private/no-store и безопасным Content-Disposition. Доступ SHALL проверяться повторно с lease не более 30 секунд; клиент SHALL не получать S3 download URL.

#### Scenario: Частичное чтение и недопустимый Range
- **WHEN** авторизованный actor задаёт допустимый диапазон либо недопустимый диапазон
- **THEN** ответ содержит точные bytes и 206/Content-Range либо 416 без содержимого

#### Scenario: Отзыв во время скачивания
- **WHEN** доступ отозван во время длинного скачивания
- **THEN** повторная проверка прекращает поток в пределах lease, а новый GET/Range запрещён

### Requirement: Принятое удаление сохраняет durable intent

Система SHALL проверять актуальную attachment.delete capability, сохранять tenant-bound intent и скрывать вложение до внешней очистки. DIRECT_DELETE SHALL запрещаться при storage READ_ONLY; PROJECT_CLEANUP SHALL требовать доказательство разрешённого project delete.

#### Scenario: Повтор и конкурентное удаление
- **WHEN** разрешённые actor повторяют или конкурентно удаляют одно вложение
- **THEN** существует один intent, файл сразу недоступен для чтения и bytes освобождаются не более одного раза

#### Scenario: Недостаточная capability
- **WHEN** VIEWER, actor при READ_ONLY либо SERVICE с одним purpose PROJECT_CLEANUP пытается начать удаление
- **THEN** metadata, S3 и квота сохраняются без нового delete intent

### Requirement: Очистка освобождает bytes только по доказательству

Система SHALL повторять принятое удаление после внешних сбоев и restart, ограничивать batch/lease и удалять только версии собственных manifest keys. Bytes SHALL освобождаться по primary DELETED receipt однократно; manifest/receipt SHALL сохраняться.

#### Scenario: Сбой S3 и restart
- **WHEN** S3 недоступен, PUT window открыт либо ответ очистки неизвестен и worker перезапускается
- **THEN** intent и расход сохраняются, последующий bounded retry завершает очистку и один RELEASE_USAGE effect

#### Scenario: Изоляция и поздний worker
- **WHEN** SERVICE с текущим STORAGE_CLEANUP обрабатывает tenant A либо просроченный lease повторяется
- **THEN** объекты B, новые uploads и global avatars не меняются, а устаревший worker не завершает чужой lease

#### Scenario: Наблюдаемая незавершённая работа
- **WHEN** cleanup не завершён или обнаружена ошибка целостности
- **THEN** доступны bounded метрики/счётчики результата и инструкция повторного запуска без сброса общей квоты или удаления retention записей
