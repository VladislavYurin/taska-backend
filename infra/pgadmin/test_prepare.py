"""Checks libpq escaping; full container smoke covers both fresh and desktop volumes."""
import unittest
from prepare import pgpass_value, prepare
import json
import os
from pathlib import Path
import sqlite3
import tempfile
from unittest.mock import patch


class PgpassTest(unittest.TestCase):
    def test_password_delimiters_are_literal(self):
        self.assertEqual(pgpass_value(r'a:b\c'), r'a\:b\\c')

    def test_line_break_cannot_inject_another_connection(self):
        for value in ('a\nb', 'a\rb'):
            with self.assertRaises(ValueError):
                pgpass_value(value)


class PrepareTest(unittest.TestCase):
    def test_fresh_and_existing_volumes_are_idempotent_and_backed_up(self):
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as folder:
                root=Path(folder)
                database=root/'pgadmin4.db'
                if existing:
                    with sqlite3.connect(database) as connection:
                        connection.execute('CREATE TABLE "user" (email TEXT)')
                        connection.execute("INSERT INTO user VALUES ('desktop@example.com')")
                env={'PGADMIN_DEFAULT_EMAIL':'admin@example.com','PGADMIN_DEFAULT_PASSWORD':'test-only',
                    'POSTGRES_USER':'taska','POSTGRES_PASSWORD':r'a:b\c',
                    'PGADMIN_SERVER_JSON_FILE':str(root/'servers.json')}
                def setup(command,*args):
                    if command=='add-user':
                        with sqlite3.connect(database) as connection:
                            connection.execute('INSERT INTO user VALUES (?)',(args[0],))
                with patch.dict(os.environ,env), patch('prepare.run_setup',side_effect=setup) as setup_mock:
                    prepare(root)
                    definitions=json.loads((root/'servers.json').read_text())['Servers']
                    self.assertEqual(len(definitions),6)
                    self.assertTrue(all(server['ConnectionParameters']['passfile']=='.pgpass' for server in definitions.values()))
                    pgpass=root/'storage/admin_example.com/.pgpass'
                    self.assertEqual(pgpass.stat().st_mode & 0o777,0o600)
                    self.assertIn(r'a\:b\\c',pgpass.read_text())
                    if existing:
                        self.assertEqual(setup_mock.call_args_list[0].args,('setup-db',))
                        self.assertEqual(setup_mock.call_args_list[1].args[0],'add-user')
                        with sqlite3.connect(root/'pgadmin4.db.pre-tas-302') as backup:
                            self.assertEqual(backup.execute('PRAGMA integrity_check').fetchone(),('ok',))
                            self.assertEqual(backup.execute('SELECT count(*) FROM user').fetchone()[0],1)
                        prepare(root)
                        self.assertEqual(sum(call.args[0]=='add-user' for call in setup_mock.call_args_list),1)
                        self.assertEqual(sum(call.args[0]=='load-servers' for call in setup_mock.call_args_list),1)
                        with patch.dict(os.environ,{'POSTGRES_PASSWORD':'rotated:test'}):
                            prepare(root)
                            self.assertIn(r'rotated\:test',pgpass.read_text())
                        with patch.dict(os.environ,{'PGADMIN_DEFAULT_EMAIL':'new@example.com'}):
                            prepare(root)
                        with sqlite3.connect(database) as connection:
                            self.assertEqual(connection.execute('SELECT count(*) FROM user').fetchone()[0],3)
                        self.assertEqual(sum(call.args[0]=='load-servers' for call in setup_mock.call_args_list),2)
                    else:
                        setup_mock.assert_not_called()


if __name__ == '__main__':
    unittest.main()
