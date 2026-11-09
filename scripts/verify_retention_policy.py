#!/usr/bin/env python3
"""Validate ES_V1 design data and critical retention boundaries; no network or writes."""
import argparse
import calendar
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_DATASETS = {
    "ledger", "invoices", "invoice_lines", "credit_notes", "payment_facts", "refund_facts",
    "chargeback_facts", "financial_approval_evidence", "oss_transaction_records",
    "raw_provider_payload", "general_technical_logs", "operational_security_logs",
    "admin_security_audit", "operator_delivery_success_receipts", "privacy_rights_case",
    "deletion_evidence", "operator_delivery_declined_receipts", "identity_verification_decision",
    "required_blocked_profile_evidence", "necessary_contract_claim_evidence",
    "deleted_account_profile", "deleted_user_avatars", "deleted_identity_search_cache",
    "tenant_workfiles", "tenant_tasks_comments_history", "tenant_search_cache",
    "database_backups", "object_backups", "backup_copies", "free_live_company_binding",
    "free_used_hmac_after_deletion",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def instant(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(result.tzinfo is not None, "timestamp must include timezone")
    return result.astimezone(timezone.utc)


def add_calendar_years(value, years):
    year = value.year + years
    return value.replace(year=year, day=min(value.day, calendar.monthrange(year, value.month)[1]))


def deadline(policy, anchors, extensions=()):
    start = instant(anchors[policy["anchor"]])
    duration = policy["duration"]
    if duration["unit"] == "CALENDAR_YEARS":
        result = add_calendar_years(start, duration["value"])
    else:
        require(duration["unit"] == "DAYS", "unsupported duration")
        result = start + timedelta(days=duration["value"])
    return max([result] + [instant(item) for item in extensions])


def can_destroy(now, erase_after, hold_active):
    return not hold_active and now >= erase_after


def valid_hold_review(created_at, review_at, maximum_days):
    return created_at < review_at <= created_at + timedelta(days=maximum_days)


def free_matching(policy, now, review_due, purpose_active, upheld_erasure, legal_blocked):
    return (purpose_active and now < review_due and not upheld_erasure and not legal_blocked
            and policy["personal_data"])


def verify(path):
    data = json.loads(path.read_text())
    require(data["schema_version"] == "ES_V1", "unexpected policy version")
    require(data["cleanup_interval_seconds"] == 86400, "daily sweep required")
    require(data["default_unclassified_dataset"] == "REJECT_CONFIGURATION", "no implicit retention fallback")
    book = data["financial_book_anchor"]
    require(book["anchor_fixed_for_closed_book_revision"] and not book["new_unrelated_activity_extends_retention"], "financial book anchor may slide indefinitely")
    require({"bookId", "fiscalPeriodId", "closingRevision", "lastEntryAt", "sourceEvidenceRef"} <= set(book["required_fields"]), "financial book evidence incomplete")
    policies = {p["id"]: p for p in data["policies"]}
    require(len(policies) == len(data["policies"]) == 12, "policy IDs must be unique and complete")
    datasets = [d for p in policies.values() for d in p["datasets"]]
    require(len(datasets) == len(set(datasets)), "ambiguous dataset policy")
    require(set(datasets) == REQUIRED_DATASETS, "dataset coverage mismatch")
    for policy in policies.values():
        for name in ("id", "datasets", "owner", "mode", "anchor", "access", "basis_kind", "legal_basis", "sources", "final_action", "task"):
            require(bool(policy.get(name)), f"{policy['id']}: missing {name}")
        require(set(policy["sources"]) <= set(data["sources"]), "unknown source ID")
        if policy["mode"] != "PURPOSE_REVIEW":
            require(policy["duration"]["value"] > 0, "empty or nonpositive retention")
        else:
            require(policy["review_interval_months"] == 12, "annual purpose review required")
    hold = data["hold"]
    require(hold["maximum_review_interval_days"] == 90, "hold review maximum is 90 days")
    require(hold["blocks_destruction"] and not hold["grants_access"], "hold cannot authorize access")
    require(not hold["release_resets_retention_clock"], "release cannot restart retention")
    require(not hold["may_recover_destroyed_records"], "hold cannot restore destroyed data")
    hold_start = instant("2026-11-03T12:00:00Z")
    require(valid_hold_review(hold_start, hold_start + timedelta(days=90), hold["maximum_review_interval_days"]), "90-day hold review boundary rejected")
    require(not valid_hold_review(hold_start, hold_start + timedelta(days=90, microseconds=1), hold["maximum_review_interval_days"]), "hold review beyond 90 days accepted")
    block = data["legal_block"]
    require(not any(block[k] for k in ("ordinary_read_allowed", "global_admin_business_bypass", "free_matching_allowed", "invoice_delivery_allowed")), "legal block access expanded")
    free = data["free_marker"]
    require(set(free["after_tenant_deletion_fields"]) == set(free["live_fields"]) - set(free["clear_on_tenant_deletion"]), "FREE deleted-field projection incorrect")
    require({"tenantId", "userId"} <= set(free["live_fields"]), "live FREE binding lost")
    require(free["same_live_company_free_paid_free_preserves_binding"], "same-company FREE round trip broken")
    require(free["admission_identity"] == "CURRENT_VERIFIED_OWNER" and free["current_owner_binding_other_tenant"] == "FREE_ELIGIBILITY_USED", "FREE transferred-owner eligibility bypass")
    require(not free["transfer_reassigns_previous_owner_marker"] and free["multiple_owner_markers_per_live_tenant_allowed"], "ownership transfer restores previous FREE entitlement")
    require(free["account_deletion_clears_first_user_reference_but_preserves_live_tenant_binding"], "account deletion destroys company FREE binding")
    require(not any(free[k] for k in ("lookup_extends_review", "overdue_review_matching", "purpose_ended_matching", "upheld_erasure_matching", "reconstruction_from_archives", "fingerprinting", "legal_block_marker_matching")), "FREE privacy exception ignored")
    operator = data["operator_delivery"]
    require(operator["surface"] == "LOCAL_OPERATOR_CLI" and not operator["new_public_endpoint"], "operator scope expanded")
    require(not operator["account_revival"] and not operator["blocked_profile_read"], "delivery resurrects identity")
    require(operator["receipt_store"] == "admin_audit_log" and operator["receipt_mode"] == "APPEND_ONLY", "missing durable operator receipts")
    require(not set(operator["receipt_details_whitelist"]) & set(operator["forbidden_receipt_fields"]), "sensitive receipt field allowed")
    require(operator["declined_receipt_policy"] == "PRIVACY_CASE", "declined rights audit lost")
    require(operator["ticket"]["ttl_seconds"] == 600 and operator["ticket"]["single_use"] and not operator["ticket"]["public_download_url"], "ticket must be internal single-use 10min permit")
    require(operator["ticket"]["consume"] == "UNIQUE_PERMIT_CONSUMED_RECEIPT_COMMIT_BEFORE_STREAM", "permit consumption not durably fenced before stream")
    require(operator["ticket"]["consume_event"] in operator["receipt_event_types"] and "permitId" in operator["receipt_details_whitelist"], "permit receipt cannot be persisted")
    require(operator["ticket"]["duplicate_consume"] == "DENY_WITHOUT_STREAM", "second worker may stream consumed permit")
    require(operator["gdpr_response_months_from_receipt"] == 1, "GDPR response clock changed")

    financial = deadline(policies["FINANCIAL_RECORD"], {"BOOK_LAST_ENTRY_AT": "2020-02-29T12:00:00Z"})
    require(financial == instant("2026-02-28T12:00:00Z"), "six calendar years/leap boundary incorrect")
    require(not can_destroy(financial - timedelta(microseconds=1), financial, False), "early destruction")
    require(can_destroy(financial, financial, False), "due boundary must be inclusive")
    require(not can_destroy(financial + timedelta(days=100), financial, True), "hold ignored")
    require(deadline(policies["FINANCIAL_RECORD"], {"BOOK_LAST_ENTRY_AT":"2020-02-29T12:00:00Z"}, ["2030-01-01T00:00:00Z"]) == instant("2030-01-01T00:00:00Z"), "tax/claim extension ignored")
    require(deadline(policies["TAX_OSS_RECORD"], {"OPERATION_YEAR_END_AT":"2027-01-01T00:00:00Z"}) == instant("2037-01-01T00:00:00Z"), "OSS year-end exclusive boundary incorrect")
    backup = policies["BACKUP"]
    anchors = {"ORIGINAL_CAPTURED_AT":"2026-01-01T00:00:00Z", "COPIED_AT":"2026-01-30T00:00:00Z"}
    require(deadline(backup, anchors) == instant("2026-02-05T00:00:00Z"), "35-day capture boundary incorrect")
    anchors["COPIED_AT"] = "2026-02-04T00:00:00Z"
    require(deadline(backup, anchors) == instant("2026-02-05T00:00:00Z") and not backup["copy_resets_anchor"], "backup copy extended retention")
    require(backup["restore_applies_current_tombstones_before_traffic"] and not backup["whole_backup_hold_allowed"], "backup restrictions missing")
    due = instant("2027-11-03T00:00:00Z")
    require(free_matching(free, due-timedelta(seconds=1), due, True, False, False), "valid purpose unexpectedly denied")
    for now, active, erased, blocked in [(due, True, False, False), (due-timedelta(days=1), False, False, False), (due-timedelta(days=1), True, True, False), (due-timedelta(days=1), True, False, True)]:
        require(not free_matching(free, now, due, active, erased, blocked), "FREE matching survived purpose/rights/block boundary")
    print(f"ES_V1 PASS: {len(policies)} policies, {len(datasets)} datasets; calendar/backup/hold/FREE/operator boundaries verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, default=ROOT / "docs/b2b/schemas/retention-policy.json")
    args = parser.parse_args()
    try:
        verify(args.policy)
    except (ValueError, KeyError, TypeError, OSError) as exc:
        parser.exit(1, f"retention policy invalid: {exc}\n")
