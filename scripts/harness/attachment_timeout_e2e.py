"""TAS-547: dependency timeout, owned native upload/confirm and signed cleanup."""
import e2e as e
import trusted_rpc_e2e as t
from attachment_upload_e2e import AttachmentUploadLocal


class AttachmentTimeoutLocal(AttachmentUploadLocal):
    def before_upload(self, client, path, payload, key, tenant):
        def snapshot():
            root = self.sql('tenant_db', "SELECT (SELECT count(*) FROM taska.quota_reservations WHERE tenant_id='%s' AND resource='STORAGE_BYTES')||'/'||(SELECT count(*) FROM taska.quota_effects WHERE tenant_id='%s' AND resource='STORAGE_BYTES')||'/'||coalesce((SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES'),'0/0');" % (tenant, tenant, tenant)).strip()
            owner = self.sql('issue_db', "SELECT count(*) FROM taska.issue_attachments WHERE tenant_id='%s';" % tenant).strip()
            return root, owner

        before = snapshot()
        # Only our Compose service pauses; no deadlines, trust or guards are changed.
        self.compose('pause', 'billing-service', timeout=60)
        try:
            response = client.request('POST', path + 'upload-url', body=payload,
                                      headers={'Idempotency-Key': key})
            e.require(response.status == 503, 'Billing timeout did not return HTTP 503')
            problem = response.json()
            e.require(problem.get('code') == 'DEPENDENCY_UNAVAILABLE'
                      and problem.get('details', {}).get('code') == 'DEPENDENCY_UNAVAILABLE'
                      and bool(problem.get('requestId')), 'Timeout lost its safe machine-readable code')
            e.require('problem+json' in response.headers.get('Content-Type', ''), 'Timeout did not return Problem')
            e.require('5000ms' not in str(problem) and 'TimeoutException' not in str(problem),
                      'Timeout exposed an internal diagnostic')
        finally:
            self.compose('unpause', 'billing-service', timeout=60)
        e.require(snapshot() == before, 'Failed admission changed accounting or confirmed attachment state')
        # The parent resumes exactly key/payload and verifies signed PUT, replay and concurrent confirm.

    def scenarios(self):
        result = super().scenarios()
        result.update(dependencyTimeout503='PASS', timeoutAccountingPreserved='PASS',
                      sameKeyRecovery='PASS')
        return result


if __name__ == '__main__':
    t.main(scenario='attachment-dependency-timeout', local_cls=AttachmentTimeoutLocal)
