#!/bin/sh
set -eu
/venv/bin/python3 /taska-pgadmin/prepare.py
exec /entrypoint.sh "$@"
