#!/usr/bin/env python3
"""Regression-test spawn-boundary redraw through Minecraft's actual packet handler.

Only the fixture player's outgoing connection is stubbed. Existing multiplayer
fixtures separately validate genuine client networking and visible rendering.
"""
import argparse, hashlib, json, os, shutil, socket, subprocess, time, zipfile
from pathlib import Path
from run import prepare_classpaths

QA=Path(__file__).resolve().parent
ROOT=QA.parent
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True,choices=['26.2','26.3'])
    parser.add_argument('--shim',type=Path)
    parser.add_argument('--label',default='protection-'+time.strftime('%Y%m%d-%H%M%S'))
    args=parser.parse_args(); version=args.version
    base=ROOT/'build/qa'/version/args.label; base.mkdir(parents=True,exist_ok=False)
    cp,_,_=prepare_classpaths(version,base/'libraries')
    mods=[p for p in sorted((ROOT/'libs'/version).glob('*.jar')) if 'colorful' not in p.name]
    if version=='26.3':mods.append(ROOT/'libs/chalk-3.2.1+26.3.jar')
    mods.append((args.shim or ROOT/f'build/{version}/libs/chalk-polymer-compat-1.0.0+{version}.jar').resolve())
    before={str(path):sha(path) for path in mods}; compilecp=[*cp,*mods]
    build=base/'fixture';build.mkdir()
    def nested(path):
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith('.jar'):
                    data=archive.read(name);target=build/(hashlib.sha256(data).hexdigest()[:12]+'-'+Path(name).name)
                    target.write_bytes(data);compilecp.append(target);nested(target)
    for path in mods:nested(path)
    classes=build/'classes';classes.mkdir()
    subprocess.run(['javac','--release','25','-proc:none','-implicit:none','-cp',os.pathsep.join(map(str,compilecp)),
                    '-d',str(classes),str(QA/'protection-fixture/ChalkProtectionQa.java')],check=True)
    fixture=build/'chalk-protection-qa.jar'
    with zipfile.ZipFile(fixture,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'chalk_protection_qa','version':'1',
                         'environment':'server','entrypoints':{'main':['chalk.protection.ChalkProtectionQa']}}))
        for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes))
    run=base/'server';(run/'mods').mkdir(parents=True)
    for mod in [*mods,fixture]:shutil.copy2(mod,run/'mods'/mod.name)
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    (run/'eula.txt').write_text('eula=true\n')
    (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
        'spawn-protection=16\nview-distance=2\nsimulation-distance=2\nlevel-name=protection-world\n'
        'level-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n'
        'generate-structures=false\npause-when-empty-seconds=0\n')
    output=base/'checks.json'
    command=['/usr/lib/jvm/java-25-openjdk/bin/java','-Xms256M','-Xmx2G','-XX:ActiveProcessorCount=4',
             '--enable-native-access=ALL-UNNAMED','-Dchalk.protection.output='+str(output),'-cp',os.pathsep.join(map(str,cp)),
             'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui']
    (base/'audit.json').write_text(json.dumps({'command':command,'mods_sha256':before},indent=2))
    report={'passed':False,'version':version,'inputs_sha256':before}
    with (base/'server.log').open('w') as log:
        child=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+150
            while not output.exists():
                if child.poll() is not None:raise RuntimeError('Server exited before fixture completed')
                if time.monotonic()>deadline:raise TimeoutError('Protection fixture')
                time.sleep(.25)
            report.update(json.loads(output.read_text()))
        except Exception as error:report['error']=str(error)
        finally:
            if child.poll() is None:
                child.stdin.write('stop\n');child.stdin.flush()
                try:child.wait(timeout=40)
                except subprocess.TimeoutExpired:child.kill();child.wait()
            report['server_exit']=child.returncode
    report['originals_unchanged']=before=={str(path):sha(path) for path in mods}
    report['passed']=report['passed'] and report['originals_unchanged'] and child.returncode==0
    (base/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
    return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
