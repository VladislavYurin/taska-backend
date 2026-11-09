"""Readiness retries cannot conceal authorization denial or retry mutations."""
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import trusted_rpc_e2e as t


class TrustedReadinessTest(unittest.TestCase):
    def test_only_safe_get_retries_transient_unavailability(self):
        client = Mock()
        client.request.side_effect = [SimpleNamespace(status=503), SimpleNamespace(status=404)]
        with patch.object(t.time, 'sleep'):
            self.assertEqual(t.wait_protected_read(client, '/api/v1/issues/owned', 404), 2)
        self.assertEqual(client.request.call_args_list,
                         [unittest.mock.call('GET', '/api/v1/issues/owned')] * 2)

    def test_authorization_denial_is_an_immediate_failure(self):
        for status in (401, 403):
            client = Mock()
            client.request.return_value.status = status
            with self.assertRaises(t.e.Failure):
                t.wait_protected_read(client, '/api/v1/issues/owned', 404)
            self.assertEqual(client.request.call_count, 1)

    def test_persistent_unavailability_never_counts_as_ready(self):
        client = Mock()
        client.request.return_value.status = 503
        with patch.object(t.time, 'monotonic', side_effect=[0, 1, 2, 46]), patch.object(t.time, 'sleep'):
            with self.assertRaises(t.e.Blocked):
                t.wait_protected_read(client, '/api/v1/issues/owned', 404)


if __name__ == '__main__':
    unittest.main()
