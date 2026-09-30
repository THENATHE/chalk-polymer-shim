#!/usr/bin/env python3
"""Save/restart isolated production-JAR server worlds and verify every white Chalk state.

The QA-only fixture checks all 108 normal/glow/facing/orientation combinations,
their support blocks, and Polymer display-holder reattachment after restart.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import zipfile

from run import prepare_classpaths

QA = Path(__file__).resolve().parent
ROOT = QA.parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True, choices=['26.2', '26.3'])
    parser.add_argument('--label', default='persistence-' + time.strftime('%Y%m%d-%H%M%S'))
    parser.add_argument('--java', default='/usr/lib/jvm/java-25-openjdk/bin/java')
    args = parser.parse_args()
    version = args.version
    work = ROOT / 'build/qa' / version
    base = work / args.label
    base.mkdir(parents=True, exist_ok=False)
    fixture_build = base / 'fixture'
    fixture_build.mkdir()
    # The network harness may run concurrently. Never overwrite JARs on a live
    # server's classpath while unpacking the Minecraft bundle.
    servercp, _, _ = prepare_classpaths(version, base / 'libraries')
    mods = [p for p in sorted((ROOT / 'libs' / version).glob('*.jar')) if 'colorful' not in p.name]
    if version == '26.3':
        mods.append(ROOT / 'libs/chalk-3.2.1+26.3.jar')
    mods.append(ROOT / f'build/{version}/libs/chalk-polymer-compat-1.0.0+{version}.jar')
    before = {str(path): sha(path) for path in mods}
    compilecp = [*servercp, *mods]

    def nested(path):
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith('.jar'):
                    data = archive.read(name)
                    target = fixture_build / (hashlib.sha256(data).hexdigest()[:12] + '-' + Path(name).name)
                    target.write_bytes(data)
                    compilecp.append(target)
                    nested(target)

    for mod in mods:
        nested(mod)
    classes = fixture_build / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '--release', '25', '-proc:none', '-implicit:none', '-cp', os.pathsep.join(map(str, compilecp)),
                    '-d', str(classes), str(QA / 'persistence-fixture/ChalkPersistenceQa.java')], check=True)
    fixture = fixture_build / 'chalk-persistence-qa.jar'
    with zipfile.ZipFile(fixture, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'chalk_persistence_qa', 'version': '1',
                        'environment': 'server', 'entrypoints': {'main': ['chalk.persistence.ChalkPersistenceQa']}}))
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes))
    run = base / 'server'
    (run / 'mods').mkdir(parents=True)
    for mod in [*mods, fixture]:
        shutil.copy2(mod, run / 'mods' / mod.name)
    with socket.socket() as bound:
        bound.bind(('127.0.0.1', 0))
        port = bound.getsockname()[1]
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
            'enforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
            'level-name=persistence-world\nlevel-type=minecraft:flat\n'
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n'
            'generate-structures=false\ndifficulty=peaceful\npause-when-empty-seconds=0\n')
    report = {'passed': False, 'version': version, 'inputs_sha256': before, 'phases': {}}
    try:
        for phase in ('write', 'restart'):
            output = base / (phase + '.json')
            command = [args.java, '-Xms256M', '-Xmx2G', '-XX:ActiveProcessorCount=4', '--enable-native-access=ALL-UNNAMED',
                       '-Dchalk.persistence.phase=' + phase, '-Dchalk.persistence.output=' + str(output),
                       '-cp', os.pathsep.join(map(str, servercp)), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui']
            (base / (phase + '-audit.json')).write_text(json.dumps({'command': command, 'mods': before}, indent=2))
            with (base / (phase + '.log')).open('w') as log:
                child = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
                try:
                    deadline = time.monotonic() + 150
                    while not output.exists():
                        if child.poll() is not None:
                            raise RuntimeError(f'{phase} server exited {child.returncode}; see {phase}.log')
                        if time.monotonic() > deadline:
                            raise TimeoutError(phase + ' persistence fixture')
                        time.sleep(.25)
                    evidence = json.loads(output.read_text())
                    report['phases'][phase] = evidence
                    if not evidence['passed']:
                        raise AssertionError(evidence)
                finally:
                    if child.poll() is None:
                        child.stdin.write('stop\n')
                        child.stdin.flush()
                        try:
                            child.wait(timeout=40)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait()
                    report[phase + '_exit'] = child.returncode
                if child.returncode != 0:
                    raise RuntimeError('Unclean server shutdown after ' + phase)
            print(f'{version} {phase}: verified {evidence["mark_count"]} states and holders', flush=True)
        if report['phases']['write']['states'] != report['phases']['restart']['states']:
            raise AssertionError('State evidence changed between save and restart')
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
    finally:
        report['originals_unchanged'] = before == {str(path): sha(path) for path in mods}
        report['passed'] = report['passed'] and report['originals_unchanged']
        (base / 'result.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({key: value for key, value in report.items() if key not in ('phases', 'inputs_sha256')}, indent=2))
        print('Evidence: ' + str(base / 'result.json'))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
