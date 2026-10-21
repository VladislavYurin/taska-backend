# TAS-298: comment text and durable events

Comment create/update/delete history and outbox payloads retain comment/actor/issue/project IDs,
issue keys and watcher routing metadata. They no longer copy comment text or previous versions.
Both history protobuf mappers also redact legacy text on reads, including during rolling upgrades.
The comment table retains its existing soft-delete behavior; this change does not erase backups.

Notification consumers ignore comment text in both old and new events. New comment notifications
and email contain only a reference to the issue. Migration 0008 removes existing previews from
the notification database; email retries read their content from these notification rows.
Inbox protobuf mapping and email rendering also redact legacy previews at read time, so a late
write by an old consumer or a restored row cannot expose text through the new service version.
Migration 0009 removes body/oldBody/newBody from existing comment history and outbox objects,
preserving routing metadata, timestamps, event IDs and outbox delivery status.

## Deployment

Pause comment writes and notification/email workers while deploying both services and their
migrations. Replace all old notification workers before resuming consumption, then replace the
issue-service writers before enabling comments again. This prevents an old worker from inserting
new previews after the one-time cleanup. Retained Kafka events are safe for the new consumer to
replay because it ignores body. Check history, inbox and a pending email using a deleted test
comment before restoring traffic. Migration SQL is intentionally irreversible: rollback to a
version that copies comment text would reintroduce the leak.

Already delivered emails, previously fetched responses and external Kafka/backups cannot be
recalled by this fix. Any historical copies there follow their own access and retention policies.
