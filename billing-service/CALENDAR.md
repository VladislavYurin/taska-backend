# UTC subscription calendar and renewal scheduling — TAS-331

`SubscriptionCalendar` computes half-open UTC calendar months with the original day/time anchor. January31→February28/29→March31 preserves day31; year changes and DST do not change UTC time. PostgreSQL microsecond precision is enforced rather than silently rounding financial time.

`settled` consumes authoritative financial timestamps supplied by settlement policy (TAS-352): PSP activation uses actual provider succeededAt; WIRE first/resume uses confirmedAt, while receivedAt determines timeliness. Timely same-plan renewal starts at the previous exclusive end with its original anchor. A payment after a gap or an explicit plan change starts a fresh full month with a new anchor, without adding old remaining days or unpaid debt. This arithmetic does not itself prove payment, grant coverage, create money facts or implement plan-change policy.

## Invoice assignment and scheduler

The [0007 migration](src/main/resources/db/changelog/0007-renewal-calendar.sql) adds append-only FORCE RLS `renewal_calendar_assignments`. It binds tenant, source period, immutable plan, next UTC interval and original anchor. Unique tenant+targetStart+RENEWAL provides a stable operation/target identity across workers, retries and payment channels. The subscription row lock serializes admission; assignment, invoice, lines and invoice outbox commit in the same transaction. Invoice failure rolls all of them back. Applied changesets are unchanged.

`InvoiceStore.issueScheduledRenewal(tenant,sourcePeriod,payload)` is the tenant-scoped scheduler step. It requires a verified SERVICE credential from billing-service, exact issueRenewalInvoice action, matching tenant and write scope. Mandatory `RenewalAuthority` rechecks current lifecycle/channel/job eligibility on the same transaction, before replay or writes; missing authority fails UNAVAILABLE. Worker payloads are server-supplied invoice inputs, never payment evidence. Commercial policy and immutable invoice validation remain mandatory. This issues an obligation, not a charge or paid period.

An unissued assignment requires current started GRANTED coverage, an end strictly after now, renewal_enabled and no cancellation. No automatic invoice/debt/FREE fallback is created at or after an unpaid boundary. A successful earlier assignment replays its exact issued invoice after restart, even if coverage later expires/cancels; authorization is still checked and replay does not start a new charge. A changed plan/buyer/currency conflicts instead of generating another assignment. Historical invoice/coverage terms are never repriced.

`RenewalCalendar` also implements the obligation-authority interface for eligible manual renewal orchestration under the caller's existing subscription lock. The default public invoice obligation adapter remains fail-closed: initial/resume/plan-change lifecycle/financial policy is supplied by its owning tasks, rather than silently inferred from a date. Next-period timing is available to TAS-488; bank invoice delivery, reminder lead time and mandatory inbox/email are not implemented here.

`RenewalScheduler.tick` is a deferred reactive scheduled dispatcher. It is opt-in with billing.renewal.enabled=true; default is disabled. Its WorkSource provides an authorized tenant worklist and authoritative buyer/method inputs, with stable source-period IDs. ExecutionContext must install a freshly verified service context; it may not mint claims from a Work object. Both adapters are required to enable the bean. RenewalAuthority additionally remains mandatory. No cross-tenant SQL or privileged RLS bypass is introduced. Failed work does not prevent the remaining work from executing; the tick reports collected errors, and subsequent ticks retry the same durable targets. The interval is configurable as billing.renewal.interval (default PT5M); this is dispatch cadence, not a grace period or bank-notice policy.

Coverage/financial expiry is still evaluated from GRANTED windows and entitlement validUntil on every relevant read (TAS-319). A scheduler delay cannot prolong paid rights. Renewal cancellation never truncates an existing paid interval or changes it to FREE.

## Verification

```sh
python3 scripts/harness.py check --module billing-service
```

Required SubscriptionCalendarTest and RenewalCalendarIntegrationTest cover leap/short months, UTC/DST/year boundaries, exact expiry, source-specific clocks, gaps, concurrent workers, restart/replay, authority failures, rollback, immutable/RLS history and scheduler reauthorization. They use owned PostgreSQL Testcontainers and the existing real invoice store. External PSP charges, production worklist/identity adapters and bank delivery are not claimed by these checks.
