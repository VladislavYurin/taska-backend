"""Checks libpq escaping; full container smoke covers both fresh and desktop volumes."""
import unittest
from prepare import pgpass_value


class PgpassTest(unittest.TestCase):
    def test_password_delimiters_are_literal(self):
        self.assertEqual(pgpass_value(r'a:b\c'), r'a\:b\\c')

    def test_line_break_cannot_inject_another_connection(self):
        for value in ('a\nb', 'a\rb'):
            with self.assertRaises(ValueError):
                pgpass_value(value)


if __name__ == '__main__':
    unittest.main()
