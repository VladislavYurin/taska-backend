# Explicitly require pgAdmin authentication, including upgrades of desktop-mode volumes.
SERVER_MODE = True

# prepare.py invokes setup-db before the official entrypoint populates config_distro.py.
LOG_FILE = "/dev/null"
