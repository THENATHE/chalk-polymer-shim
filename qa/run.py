#!/usr/bin/env python3
"""Compile and run production-JAR QA using cached, official Minecraft/Fabric libraries.
No downloads, original JAR changes, existing worlds, or launcher profiles are used.
"""
import argparse, hashlib, json, os, shutil, socket, subprocess, time, uuid, zipfile
from pathlib import Path
QA=Path(__file__).resolve().parent
ROOT=QA.parent
CACHE=Path.home()/'.gradle/caches'
MODULES=CACHE/'modules-2/files-2.1'

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def artifact(coordinates):
    group,name,version,*classifier=coordinates.split(':')
    filename=f'{name}-{version}'+('-'+classifier[0] if classifier else '')+'.jar'
    matches=list((MODULES/group/name/version).glob('*/'+filename))
    if not matches:raise FileNotFoundError('Missing cached dependency '+coordinates)
    return matches[0]

def prepare_classpaths(version,out):
    out.mkdir(parents=True,exist_ok=True)
    loader=artifact('net.fabricmc:fabric-loader:0.19.5')
    with zipfile.ZipFile(loader) as z:metadata=json.loads(z.read('fabric-installer.json'))
    common=[loader,*[artifact(x['name']) for x in metadata['libraries']['common']]]
    server=[*common,*[artifact(x['name']) for x in metadata['libraries']['server']]]
    with zipfile.ZipFile(CACHE/f'fabric-loom/{version}/minecraft-server.jar') as z:
        for kind in ('libraries','versions'):
            for line in z.read('META-INF/'+kind+'.list').decode().splitlines():
                digest,name,path=line.split('\t');data=z.read('META-INF/'+kind+'/'+path)
                if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Corrupt Minecraft bundle '+name)
                target=out/Path(path).name;target.write_bytes(data);server.append(target)
    info=json.loads((CACHE/f'fabric-loom/{version}/mojang_minecraft_info.json').read_text())
    client=[*common]
    for lib in info['libraries']:
        if lib.get('rules'):
            allowed=False
            for rule in lib['rules']:
                osrule=rule.get('os',{})
                if osrule.get('name','linux')=='linux' and osrule.get('arch','x86_64') in ('x86_64','amd64'):
                    allowed=rule['action']=='allow'
            if not allowed:continue
        client.append(artifact(lib['name']))
    client.append(CACHE/f'fabric-loom/{version}/minecraft-client.jar')
    return server,client,info

def fixtures(version,mods,servercp,clientcp,out):
    classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
    paths=[*servercp,*clientcp,*mods]
    def nested(p):
        with zipfile.ZipFile(p) as z:
            for name in z.namelist():
                if name.endswith('.jar'):
                    data=z.read(name);dest=out/(hashlib.sha256(data).hexdigest()[:12]+'-'+Path(name).name)
                    dest.write_bytes(data);paths.append(dest);nested(dest)
    for mod in mods:nested(mod)
    subprocess.run(['javac','--release','25','-proc:none','-implicit:none','-cp',os.pathsep.join(map(str,dict.fromkeys(paths))),'-d',str(classes),*map(str,(QA/'fixture').glob('*.java'))],check=True)
    result=[]
    for side in ('server','client'):
        jar=out/f'chalk-qa-{side}.jar'
        metadata={'schemaVersion':1,'id':'chalk_qa_'+side,'version':'1','environment':side,'entrypoints':{'main' if side=='server' else 'client':['chalk.qa.Chalk'+side.title()+'Qa']},'depends':{'fabric-api':'*','minecraft':version}}
        with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
            z.writestr('fabric.mod.json',json.dumps(metadata))
            for p in classes.rglob('*.class'):
                if ('Client' in p.name)==(side=='client'):z.write(p,p.relative_to(classes))
        result.append(jar)
    return result

def wait_for(predicate,children,timeout,description):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if predicate():return
        for child,_,_,path in children:
            if child.poll() is not None:raise RuntimeError(f'Process exit {child.returncode}: {path}')
        time.sleep(.25)
    raise TimeoutError(description)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--version',required=True,choices=['26.2','26.3']);p.add_argument('--mods',type=Path)
    p.add_argument('--shim',type=Path);p.add_argument('--client',choices=['none','native','unsupported'],default='unsupported')
    p.add_argument('--java',default='/usr/lib/jvm/java-25-openjdk/bin/java');p.add_argument('--label')
    p.add_argument('--assets',type=Path,default=Path.home()/'.local/share/ModrinthApp/meta/assets')
    p.add_argument('--creative-only',action='store_true');p.add_argument('--autohost',action='store_true');p.add_argument('--build-only',action='store_true');p.add_argument('--colorful',action='store_true')
    a=p.parse_args();v=a.version
    if a.creative_only and a.client!='native':p.error('--creative-only requires --client native')
    moddir=(a.mods or ROOT/'libs'/v).resolve()
    mods=[m for m in sorted(moddir.glob('*.jar')) if a.colorful or 'colorful' not in m.name]
    if not any(m.name.startswith('chalk-') and 'colorful' not in m.name for m in mods):
        mods.append(ROOT/'libs/chalk-3.2.1+26.3.jar')
    if a.colorful and not any('colorful' in m.name for m in mods):
        mods.append(ROOT/'libs/26.2/chalk-colorful-addon-2.1.1.jar')
    shim=(a.shim or ROOT/f'build/{v}/libs/chalk-polymer-compat-1.0.0+{v}.jar').resolve()
    work=ROOT/f'build/qa/{v}';support=work/'support'/str(os.getpid())
    servercp,clientcp,info=prepare_classpaths(v,support/'libraries')
    fixture=fixtures(v,mods,servercp,clientcp,support/'fixture')
    if a.build_only:return
    label=a.label or time.strftime('%Y%m%d-%H%M%S')+'-'+a.client
    base=work/label;base.mkdir(parents=True,exist_ok=False)
    control=base/'control';control.mkdir();children=[]
    inputs=[*mods,shim,*fixture];before={str(m):sha(m) for m in inputs}
    result={'passed':False,'version':v,'client':a.client,'autohost':a.autohost,'creative_only':a.creative_only,'original_sha256':before}
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    env=os.environ.copy();env.update(SDL_VIDEODRIVER='x11',SDL_VIDEO_X11_XINPUT2='0',LP_NUM_THREADS='4')
    try:
        for side in ['server']+([] if a.client=='none' else ['client']):
            run=base/side;(run/'mods').mkdir(parents=True)
            selected=[*mods,shim,fixture[0]] if side=='server' else [m for m in mods if m.name.startswith('fabric-api')]+[fixture[1]]
            if side=='client' and a.client=='native':selected=[m for m in mods if 'polymer' not in m.name]+[fixture[1]]
            for m in selected:shutil.copy2(m,run/'mods'/m.name)
            cp=servercp if side=='server' else clientcp
            command=[a.java,'-Xms256M','-Xmx2G','-XX:ActiveProcessorCount=4','--enable-native-access=ALL-UNNAMED','-Dchalk.qa.control='+str(control),'-Dchalk.qa.native='+str(a.client=='native').lower(),'-Dchalk.qa.creative-only='+str(a.creative_only).lower()]
            if side=='server':
                if a.autohost:
                    (run/'config/polymer').mkdir(parents=True)
                    (run/'config/polymer/auto-host.json').write_text(json.dumps({'enabled':True,'required':True,'type':'polymer:automatic','settings':{}}))
                (run/'eula.txt').write_text('eula=true\n')
                (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-name=qa-world\nlevel-type=minecraft:flat\ngenerator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}}],"biome":"minecraft:plains"}}\ngenerate-structures=false\ndifficulty=peaceful\npause-when-empty-seconds=0\n')
                command+=['-cp',os.pathsep.join(map(str,dict.fromkeys(cp))),'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui']
            else:
                (run/'options.txt').write_text('graphicsMode:0\nrenderDistance:2\nsimulationDistance:5\nmaxFps:30\nmaxFpsInactive:30\nsoundCategory_master:0.0\njoinedFirstServer:true\npauseOnLostFocus:false\n')
                if not a.autohost:
                    with (run/'options.txt').open('a') as options:options.write('resourcePacks:["vanilla","file/chalk-qa.zip"]\n')
                for property,folder in [('java.library.path','java'),('jna.tmpdir','jna'),('org.lwjgl.system.SharedLibraryExtractPath','lwjgl'),('io.netty.native.workdir','netty')]:command.append('-D'+property+'='+str(run/'natives'/folder))
                if v=='26.3':command+=['-XX:StackShadowPages=32','--add-exports','java.base/jdk.internal.misc=ALL-UNNAMED']
                command+=['-cp',os.pathsep.join(map(str,dict.fromkeys(cp))),'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','ChalkQa','--version',v,'--gameDir',str(run),'--assetsDir',str(a.assets),'--assetIndex',info['assetIndex']['id'],'--uuid',str(uuid.uuid3(uuid.NAMESPACE_DNS,'ChalkQa')),'--accessToken','0','--versionType','release','--width','900','--height','600','--quickPlayMultiplayer',f'127.0.0.1:{port}']
            (run/'audit.json').write_text(json.dumps({'command':command,'mods':[{'name':m.name,'sha256':sha(m)} for m in selected]},indent=2))
            logfile=run/'console.log';log=logfile.open('w');child=subprocess.Popen(command,cwd=run,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True);children.append((child,log,side,logfile))
            print(f'{side}: {logfile}',flush=True)
            if side=='server':
                wait_for(lambda:'CHALK_QA_PASS startup' in logfile.read_text(),children,180,'server startup checks')
                child.stdin.write('reload\n');child.stdin.flush()
                wait_for(lambda:'CHALK_QA_PASS reload' in logfile.read_text(),children,90,'reload checks')
                child.stdin.write('polymer generate-pack\n');child.stdin.flush()
                pack=run/'polymer/resource_pack.zip'
                wait_for(pack.exists,children,90,'resource pack generation')
                time.sleep(1)
                with zipfile.ZipFile(pack) as z:
                    if z.testzip() is not None:raise ValueError('Corrupt generated pack')
                    for entry in z.namelist():
                        if entry.endswith('.json'):json.loads(z.read(entry))
                    files=z.namelist();assert any(n.startswith('assets/chalk/') for n in files),'Chalk artwork missing'
                    assert 'licenses/chalk/LICENSE' in files,'Artwork license missing'
                    mark_models=[n for n in files if n.startswith('assets/chalk_polymer_compat/items/mark/') and n.endswith('.json')]
                    assert len(mark_models)>=18,'Missing vanilla-readable mark definitions'
                    result['mark_model_count']=len(mark_models)
                    (control/'pack-files.json').write_text(json.dumps(files,indent=2))
                if a.client!='none' and not a.autohost:
                    # Install the generated server pack as a local selected pack to avoid HTTP/UI dependencies.
                    clientdir=base/'client';(clientdir/'resourcepacks').mkdir(parents=True,exist_ok=True)
                    shutil.copy2(pack,clientdir/'resourcepacks/chalk-qa.zip')
        if a.client!='none':
            # The fixture drives use/break actions and validates server acknowledgements.
            wait_for(lambda:(control/'result.txt').exists() or (control/'failure').exists(),children,240,'multiplayer fixture')
            if (control/'failure').exists():raise AssertionError((control/'failure').read_text())
            result['multiplayer']=(control/'result.txt').read_text();assert result['multiplayer'].startswith('PASS')
            if a.autohost:
                entries=[json.loads(line) for line in (base/'client/downloads/log.json').read_text().splitlines()]
                expected=hashlib.sha1((base/'server/polymer/resource_pack.zip').read_bytes()).hexdigest()
                assert any(e.get('hash')==expected and e.get('url','').startswith('http://localhost:') for e in entries),'Actual hosted pack download missing'
                result['hosted_pack_sha1']=expected
        result['passed']=True
    except Exception as e:
        result['failure']=str(e);raise
    finally:
        for child,log,side,logfile in reversed(children):
            if child.poll() is None:
                if side=='server':child.stdin.write('stop\n');child.stdin.flush()
                else:child.terminate()
                try:child.wait(timeout=30)
                except subprocess.TimeoutExpired:child.kill();child.wait()
            log.close()
            if side=='server':result['server_exit']=child.returncode
        after={str(m):sha(m) for m in mods};result['originals_unchanged']=all(before[str(m)]==after[str(m)] for m in mods)
        result['tested_shim_sha256']=before[str(shim)]
        result['passed']=result['passed'] and result['originals_unchanged'] and result.get('server_exit')==0
        (base/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)
    if not result['passed']:raise SystemExit(1)
if __name__=='__main__':main()
