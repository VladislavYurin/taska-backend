#!/usr/bin/env python3
"""Build/run an isolated MCP E2E project. Credentials stay in ignored target with mode 0600."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--keep', action='store_true', help='Keep the successful project for external client checks')
parser.add_argument('--skip-package', action='store_true', help='Use already packaged jars from this checkout')
parser.add_argument('--jre-image', help='Optional existing Java 21 JRE image')
parser.add_argument('--cleanup', type=Path, help='Remove only the isolated project recorded in this credentials.json')
args = parser.parse_args()
if args.cleanup:
    fixture = args.cleanup.resolve()
    expected_root = root / 'mcp-server/target/mcp-e2e'
    if not fixture.is_relative_to(expected_root) or fixture.name != 'credentials.json':
        parser.error('Cleanup accepts only this checkout ignored E2E credentials.json')
    data = json.loads(fixture.read_text())
    project = data['composeProject']
    compose = root / 'mcp-server/src/test/compose/compose.yml'
    if not re.fullmatch(r'taska-mcp-it-[0-9a-f]{12}', project) or data['composeFile'] != str(compose):
        parser.error('Invalid isolated project identity')
    # Compose down needs interpolation values, but removes by unique project labels only.
    env = dict(os.environ, TASKA_E2E_ROOT=str(root), TASKA_E2E_JAR_DIR=str(fixture.parent / 'jars'), TASKA_E2E_JWT_SECRET='cleanup-only', TASKA_E2E_SIGNING_SECRET='cleanup-only')
    subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-p', project, '-f', str(compose),
                    'down', '--volumes', '--remove-orphans', '--timeout', '15'], cwd=root, env=env, check=True)
    jars = fixture.parent / 'jars'
    if jars.exists():
        shutil.rmtree(jars)
else:
    env = dict(os.environ, JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m')
    if not args.skip_package:
        subprocess.run(['./mvnw', '-B', '-ntp', '-pl',
                        'auth-service,project-service,workflow-service,issue-service,notification-service,api-gateway,mcp-server',
                        '-am', 'package', '-DskipTests'], cwd=root, env=env, check=True)
    command = ['./mvnw', '-B', '-ntp', '-pl', 'mcp-server', '-am', 'test',
               '-Dtest=McpComposeE2EIT', '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtaska.mcp.compose=true',
               '-Dtaska.mcp.compose.keep=' + str(args.keep).lower()]
    if args.jre_image:
        command += ['-Dtaska.mcp.compose.jreImage=' + args.jre_image]
    subprocess.run(command, cwd=root, env=env, check=True)
