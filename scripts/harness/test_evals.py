"""Regression tests for the evaluator itself, including a broken production guard."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('taska_eval_runner_test', Path(__file__).with_name('eval_runner.py'))
evals = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evals)


class EvalTest(unittest.TestCase):
    def test_real_report_guard_and_mutant_are_distinguished(self):
        case = next(c for c in evals.load_cases() if c['id'] == 'it-selector-zero')
        evaluator = evals.Evaluator()
        self.assertEqual(evals.evaluate([case], evaluator)['cases'][0]['evaluationStatus'], 'PASS')
        with patch.object(evaluator.h, 'test_reports', return_value={
                'status': 'PASS', 'reason': '', 'testCounts': {'ExampleIT': {'tests': 0}}}):
            result = evals.evaluate([case], evaluator)
        self.assertEqual(result['cases'][0]['evaluationStatus'], 'FAIL')
        self.assertEqual(result['metrics']['falsePassCount'], 1)

    def test_missing_dependency_never_counts_as_expected_block(self):
        case = next(c for c in evals.load_cases() if c['id'] == 'missing-mailbox')
        with patch.object(evals.Evaluator, 'observe', side_effect=evals.MissingImplementation()):
            result = evals.evaluate([case], evals.Evaluator())['cases'][0]
        self.assertEqual(result['observedOutcome'], 'BLOCKED')
        self.assertEqual(result['evaluationStatus'], 'BLOCKED')

    def test_unexpected_exception_is_failed_without_secret_text(self):
        case = evals.load_cases()[0]
        with patch.object(evals.Evaluator, 'observe', side_effect=ValueError('secret-do-not-publish')):
            result = evals.evaluate([case], evals.Evaluator())
        self.assertEqual(result['cases'][0]['evaluationStatus'], 'FAIL')
        self.assertNotIn('secret-do-not-publish', str(result))

    def test_catalog_rejects_duplicate_and_unknown_expectation(self):
        cases = evals.load_cases()
        for mutant in [cases + [cases[0]], [dict(cases[0], expectedOutcome='SKIP')]]:
            with patch.object(Path, 'read_text', return_value=evals.json.dumps({'schemaVersion': 1, 'cases': mutant})):
                with self.assertRaises(ValueError):
                    evals.load_cases()

    def test_unknown_case_does_not_execute_input(self):
        evaluator = evals.Evaluator()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'marker'
            with self.assertRaises(ValueError):
                evaluator.observe('$(touch %s)' % marker, Path(directory))
            self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
