"""Контрольные grants fixture остаются однозначными и ограниченными SERVICE."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import e2e as e
from project_acl_e2e import ProjectAclLocal
from workflow_acl_e2e import WorkflowAclLocal
from issue_acl_e2e import IssueAclLocal


class NativeFixtureSourcesTest(unittest.TestCase):
    def test_source_graph_is_complete_unique_and_scoped_in_each_profile(self):
        for fixture in (ProjectAclLocal, WorkflowAclLocal, IssueAclLocal):
            with self.subTest(profile=fixture.__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                store = e.StateStore(root)
                state = store.create('a' * 16, 'local', 'owned-source-graph', '')
                local = fixture(root, store, state)
                local.password = 'unused-owned-fixture-password'
                # Only this test's empty DB/Compose boundaries are replaced.
                with patch.object(local, 'sql'), patch.object(local, 'compose'), patch.object(local, 'refresh'):
                    local.prepare_extra_databases()
                controls = [edge for edge in local.snapshot['edges']
                            if edge['purpose'] in ('admin', 'billing', 'membership')]
                keys = [(edge['issuer'], edge['audience'], edge['rpc'], edge['action'], edge['purpose'])
                        for edge in controls]
                self.assertEqual(len(keys), len(set(keys)), 'Ambiguous duplicate source grant')
                for edge in controls:
                    self.assertEqual(edge['kinds'], ['SERVICE'])
                    self.assertEqual(edge['serviceIdentities'], [edge['issuer']])
                    self.assertEqual(edge['tenants'], [])
                expected = {
                    'getPlatformConfigurationSnapshot': ('tenant-service', 'admin', 'NONE', ['read']),
                    'getEntitlementSnapshot': ('billing-service', 'billing', 'BOUND', ['read']),
                    'readReferencedPlanVersion': ('billing-service', 'billing', 'NONE', ['read', 'catalog:read'])}
                for issuer in ('tenant-service', 'project-service', 'workflow-service', 'issue-service'):
                    for action, (audience, purpose, mode, scopes) in expected.items():
                        found = [edge for edge in controls if edge['issuer'] == issuer and edge['action'] == action]
                        self.assertEqual(len(found), 1, (issuer, action))
                        edge = found[0]
                        self.assertEqual((edge['audience'], edge['purpose'], edge['tenantMode'], edge['scopes']),
                                         (audience, purpose, mode, scopes))
                for issuer in ('project-service', 'workflow-service', 'tenant-service', 'auth-service', 'issue-service'):
                    found = [edge for edge in controls if edge['issuer'] == issuer
                             and edge['action'] == 'batchValidateMemberships']
                    self.assertEqual(len(found), 1, issuer)
                    self.assertEqual((found[0]['audience'], found[0]['tenantMode'], found[0]['scopes']),
                                     ('tenant-service', 'BOUND', ['memberships:read']))


if __name__ == '__main__':
    unittest.main()
