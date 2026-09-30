#!/usr/bin/env python3
"""Connect the unmodified Minecraft Main client (zero Fabric/QA mods) to a fresh shim server."""
import argparse, hashlib, json, os, shutil, socket, subprocess, time, uuid, zipfile
from pathlib import Path
from run import prepare_classpaths
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--version',required=True,choices=['26.2','26.3'])
p.add_argument('--java',default='/usr/lib/jvm/java-25-openjdk/bin/java')
p.add_argument('--assets',type=Path,default=Path.home()/'.local/share/ModrinthApp/meta/assets')
p.add_argument('--label',default='vanilla-'+time.strftime('%Y%m%d-%H%M%S'))
a=p.parse_args();v=a.version;base=ROOT/'build/qa'/v/a.label;base.mkdir(parents=True,exist_ok=False)
servercp,clientcp,info=prepare_classpaths(v,base/'libraries')
# The vanilla entrypoint never initializes Fabric; remove its libraries anyway.
clientcp=[x for x in clientcp if not any(t in str(x) for t in ['/net.fabricmc/','/org.ow2.asm/'])]
mods=[x for x in (ROOT/'libs'/v).glob('*.jar') if 'colorful' not in x.name]
if v=='26.3':mods.append(ROOT/'libs/chalk-3.2.1+26.3.jar')
mods.append(ROOT/f'build/{v}/libs/chalk-polymer-compat-1.0.0+{v}.jar')
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
server=base/'server';client=base/'client';(server/'mods').mkdir(parents=True);client.mkdir()
for m in mods:shutil.copy2(m,server/'mods'/m.name)
(server/'eula.txt').write_text('eula=true\n')
(server/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\ngenerator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}}],"biome":"minecraft:plains"}}\ngenerate-structures=false\n')
children=[];result={'passed':False,'version':v,'client_entrypoint':'net.minecraft.client.main.Main','client_mods':[], 'mods_sha256':{m.name:hashlib.sha256(m.read_bytes()).hexdigest() for m in mods}}
def start(side,command):
 run=base/side;log=(run/'console.log').open('w');(run/'audit.json').write_text(json.dumps({'command':command},indent=2))
 env=os.environ.copy();env.update(SDL_VIDEODRIVER='x11',SDL_VIDEO_X11_XINPUT2='0',LP_NUM_THREADS='4')
 child=subprocess.Popen(command,cwd=run,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True);children.append((child,log,side));return child
def wait(test,seconds,reason):
 deadline=time.monotonic()+seconds
 while not test():
  if any(c.poll() is not None for c,_,_ in children):raise RuntimeError('Process exited: '+reason)
  if time.monotonic()>deadline:raise TimeoutError(reason)
  time.sleep(.25)
def console(command):sp.stdin.write(command+'\n');sp.stdin.flush()
common=[a.java,'-Xms256M','-Xmx2G','-XX:ActiveProcessorCount=4','--enable-native-access=ALL-UNNAMED']
try:
 sp=start('server',common+['-cp',os.pathsep.join(map(str,servercp)),'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui'])
 wait(lambda:'Done (' in (server/'console.log').read_text(),150,'server startup')
 console('polymer generate-pack');pack=server/'polymer/resource_pack.zip'
 wait(pack.exists,90,'pack generation');time.sleep(2)
 with zipfile.ZipFile(pack) as z:assert z.testzip() is None
 (client/'resourcepacks').mkdir();shutil.copy2(pack,client/'resourcepacks/chalk-qa.zip')
 (client/'options.txt').write_text('graphicsMode:0\nrenderDistance:2\nsimulationDistance:5\nmaxFps:20\nmaxFpsInactive:20\nsoundCategory_master:0.0\njoinedFirstServer:true\npauseOnLostFocus:false\nresourcePacks:["vanilla","file/chalk-qa.zip"]\n')
 cmd=common.copy()
 for prop,folder in [('java.library.path','java'),('jna.tmpdir','jna'),('org.lwjgl.system.SharedLibraryExtractPath','lwjgl'),('io.netty.native.workdir','netty')]:cmd+=['-D'+prop+'='+str(client/'natives'/folder)]
 if v=='26.3':cmd+=['-XX:StackShadowPages=32','--add-exports','java.base/jdk.internal.misc=ALL-UNNAMED']
 cmd+=['-cp',os.pathsep.join(map(str,clientcp)),'net.minecraft.client.main.Main','--username','VanillaChalkQa','--version',v,'--gameDir',str(client),'--assetsDir',str(a.assets),'--assetIndex',info['assetIndex']['id'],'--uuid',str(uuid.uuid3(uuid.NAMESPACE_DNS,'VanillaChalkQa')),'--accessToken','0','--versionType','release','--width','900','--height','600','--quickPlayMultiplayer',f'127.0.0.1:{port}']
 cp=start('client',cmd)
 wait(lambda:'VanillaChalkQa joined the game' in (server/'console.log').read_text(),150,'zero-mod vanilla login')
 console('give VanillaChalkQa chalk:white_chalk');console('give VanillaChalkQa chalk:white_glow_chalk')
 console('fill -3 100 -3 3 100 5 minecraft:stone');console('setblock 0 100 0 minecraft:stone');console('setblock 0 101 0 chalk:white_chalk_mark[facing=up,orientation=4]')
 console('setblock 2 100 0 minecraft:stone');console('setblock 2 101 0 chalk:white_glow_chalk_mark[facing=up,orientation=4]')
 console('tp VanillaChalkQa 1 101 3 180 40')
 time.sleep(12)
 assert cp.poll() is None,'Vanilla client exited'
 logs=(server/'console.log').read_text();assert 'VanillaChalkQa lost connection' not in logs,'Vanilla disconnected'
 clientlog=(client/'console.log').read_text();assert 'file/chalk-qa.zip' in clientlog,'Pack was not loaded'
 assert 'Loading Minecraft' not in clientlog,'Fabric loader unexpectedly initialized'
 result.update(passed=True,join=True,pack_loaded=True,received_items_and_mark_chunks=True)
finally:
 for child,log,side in reversed(children):
  if child.poll() is None:
   if side=='server':child.stdin.write('stop\n');child.stdin.flush()
   else:child.terminate()
   try:child.wait(timeout=35)
   except subprocess.TimeoutExpired:child.kill();child.wait()
  log.close()
  if side=='server':result['server_exit']=child.returncode
 result['passed']=result['passed'] and result.get('server_exit')==0
 (base/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
