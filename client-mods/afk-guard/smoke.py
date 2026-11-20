#!/usr/bin/env python3
"""Isolated Fabric transformation/entrypoint test. Exits before the game starts.

No account credentials, server connection, graphics initialization, or live save access.
Run build.py first. The smoke test mod is never installed into the real game.
"""
import json
import os
from pathlib import Path
import subprocess
import zipfile

BASE = Path(__file__).resolve().parent
MC = Path(os.environ.get('MINECRAFT_DIR', '/home/hunter/.var/app/com.mojang.Minecraft/.minecraft'))
SMOKE = BASE / 'build/smoke'
(SMOKE / 'mods').mkdir(parents=True, exist_ok=True)
libraries = {}
for version in ['26.3', 'fabric-loader-0.20.1-26.3']:
    data = json.loads((MC / 'versions' / version / (version + '.json')).read_text())
    for lib in data.get('libraries', []):
        parts = lib['name'].split(':')
        fallback = '/'.join([parts[0].replace('.', '/'), parts[1], parts[2], parts[1] + '-' + parts[2] + ('-' + parts[3] if len(parts) > 3 else '') + '.jar'])
        path = MC / 'libraries' / lib.get('downloads', {}).get('artifact', {}).get('path', fallback)
        if path.exists(): libraries[':'.join(parts[:2])] = path
classpath = os.pathsep.join(map(str, [MC / 'versions/26.3/26.3.jar', *libraries.values()]))
classes = BASE / 'build/smoke-classes'
classes.mkdir(exist_ok=True)
subprocess.run(['javac', '--release', '25', '-proc:none', '-cp', classpath, '-d', str(classes),
                *map(str, BASE.joinpath('src/smoke/java').rglob('*.java'))], check=True)
for p in MC.joinpath('mods').glob('*.jar'):
    target = SMOKE / 'mods' / p.name
    if not target.exists(): target.symlink_to(p)
guard_jar = BASE / 'build/fieldnotes-afk-guard-0.2.0+26.3.jar'
target = SMOKE / 'mods' / guard_jar.name
if target.is_symlink(): target.unlink()
target.symlink_to(guard_jar)
with zipfile.ZipFile(SMOKE / 'mods/guard-smoke.jar', 'w') as z:
    z.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'guard-smoke', 'version': '1', 'environment': 'client',
        'entrypoints': {'preLaunch': ['dev.fieldnotes.smoke.Smoke']}}))
    for p in classes.rglob('*.class'): z.write(p, p.relative_to(classes))
cmd = ['java', '-Xmx1G', '-Dfabric.gameJarPath=' + str(MC / 'versions/26.3/26.3.jar'), '-Dmixin.debug.export=true',
    '-cp', classpath, 'net.fabricmc.loader.impl.launch.knot.KnotClient', '--gameDir', str(SMOKE), '--version', '26.3',
    '--accessToken', '0', '--username', 'GuardSmokeTest']
with (SMOKE / 'smoke.log').open('w') as out:
    result = subprocess.run(cmd, cwd=SMOKE, stdout=out, stderr=subprocess.STDOUT, timeout=90)
for line in (SMOKE / 'smoke.log').read_text().splitlines():
    if 'SMOKE_' in line or 'ERROR' in line: print(line)
raise SystemExit(result.returncode)
