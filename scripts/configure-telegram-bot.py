#!/usr/bin/env python3
"""Configure a test bot without exposing its credential in argv, history or error output."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, help="Git-ignored .env containing NOTIFICATION_TELEGRAM_* values")
    parser.add_argument("--webhook-url", required=True, help="Public HTTPS gateway webhook URL")
    args = parser.parse_args()
    config = dict(os.environ)
    if args.env_file:
        for line in args.env_file.read_text().splitlines():
            line = line.strip()
            if line.startswith("NOTIFICATION_TELEGRAM_") and "=" in line:
                name, value = line.split("=", 1)
                config[name] = value.strip().strip("\"'")
    token = config.get("NOTIFICATION_TELEGRAM_BOT_TOKEN") or getpass.getpass("Bot token (hidden): ")
    secret = config.get("NOTIFICATION_TELEGRAM_WEBHOOK_SECRET") or getpass.getpass("Webhook secret (hidden, same as project config): ")
    if not re.fullmatch(r"[0-9]+:[A-Za-z0-9_-]+", token) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", secret):
        sys.exit("Invalid bot token or webhook secret format.")
    url = urllib.parse.urlparse(args.webhook_url)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        sys.exit("Webhook URL must be public HTTPS without credentials, query or fragment.")

    def call(method, payload):
        request = urllib.request.Request("https://api.telegram.org/bot" + token + "/" + method,
                                         data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        try:
            # Never include exceptions: their URLs contain the bot token.
            with urllib.request.urlopen(request, timeout=20) as response:
                result = json.load(response)
        except (OSError, ValueError):
            sys.exit("Telegram request failed. Check credentials/network; secrets were not printed.")
        if not result.get("ok"):
            sys.exit("Telegram rejected configuration. Check credentials and HTTPS reachability.")
        return result.get("result")

    bot = call("getMe", {})
    expected = config.get("NOTIFICATION_TELEGRAM_BOT_USERNAME", "").lower()
    if expected and str(bot.get("username", "")).lower() != expected:
        sys.exit("Bot username does not match the project config. No webhook changes made.")
    call("setMyCommands", {"commands": [
        {"command": "start", "description": "Connect from your Taska profile"},
        {"command": "help", "description": "How to connect Taska notifications"},
        {"command": "stop", "description": "Disconnect Telegram notifications"},
    ]})
    call("setWebhook", {"url": args.webhook_url, "secret_token": secret,
                         "allowed_updates": ["message"], "max_connections": 10, "drop_pending_updates": False})
    info = call("getWebhookInfo", {})
    print("Configured bot @" + bot.get("username", ""))
    print("Webhook installed. Pending updates:", info.get("pending_update_count", 0))
    print("Connect and confirm it in Taska Settings → My profile → Notifications.")


if __name__ == "__main__":
    main()
