"""Prepare Taska connections from runtime secrets; retain the existing pgAdmin DB."""
import json
import os
from pathlib import Path
import sqlite3
import subprocess


def pgpass_value(value):
    if any(char in value for char in '\r\n'):
        raise ValueError('PostgreSQL connection values must not contain line breaks')
    return value.replace('\\', '\\\\').replace(':', '\\:')


def prepare(data_dir=Path("/var/lib/pgadmin")):
    os.umask(0o077)
    email = os.environ['PGADMIN_DEFAULT_EMAIL']
    password = os.environ['PGADMIN_DEFAULT_PASSWORD']
    username = os.environ['POSTGRES_USER']
    db_password = os.environ['POSTGRES_PASSWORD']
    directory = email.replace('@', '_')
    if '/' in directory or '\\' in directory or directory in {'.', '..'}:
        raise ValueError('Invalid pgAdmin account directory')
    storage = data_dir / 'storage' / directory
    storage.mkdir(parents=True, exist_ok=True)
    passfile = storage / '.pgpass'
    servers = {}
    passwords = []
    for number, service in enumerate(('auth', 'project', 'workflow', 'issue', 'notification', 'admin'), 1):
        host = service + '-db'
        database = os.environ.get(service.upper() + '_DB_NAME', service + '_db')
        servers[str(number)] = {
            'Name': 'Taska ' + service,
            'Group': 'Taska',
            'Host': host,
            'Port': 5432,
            'MaintenanceDB': database,
            'Username': username,
            'ConnectionParameters': {'sslmode': 'prefer', 'passfile': '.pgpass'},
        }
        passwords.append(':'.join(pgpass_value(part) for part in (host, '5432', '*', username, db_password)))
    passfile.write_text('\n'.join(passwords) + '\n')
    passfile.chmod(0o600)
    definitions = Path(os.environ['PGADMIN_SERVER_JSON_FILE'])
    definitions.write_text(json.dumps({'Servers': servers}))
    database = data_dir / 'pgadmin4.db'
    if not database.exists():
        return  # The official entrypoint provisions the initial account and imports definitions.
    backup = database.with_name('pgadmin4.db.pre-tas-302')
    if not backup.exists():
        with sqlite3.connect(database) as connection, sqlite3.connect(backup) as target:
            connection.backup(target)
    with sqlite3.connect(backup) as target:
        if target.execute('PRAGMA integrity_check').fetchone() != ('ok',):
            raise RuntimeError('pgAdmin pre-migration backup failed integrity check')
    # Use pgAdmin's supported migrations before assuming a particular SQLite schema.
    run_setup('setup-db')
    with sqlite3.connect(database) as connection:
        exists = connection.execute('SELECT 1 FROM "user" WHERE email=?', (email,)).fetchone()
        if exists:
            return
    run_setup('add-user', email, password, '--admin')
    run_setup('load-servers', str(definitions), '--user', email)


def run_setup(*arguments):
    # setup.py does not log credentials; hide its output and keep failure diagnostics generic.
    result = subprocess.run(['/venv/bin/python3', '/pgadmin4/setup.py', *arguments],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise RuntimeError('pgAdmin setup command failed: ' + arguments[0])


if __name__ == '__main__':
    prepare()
