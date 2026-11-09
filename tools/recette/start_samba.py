"""Standalone fictitious SMB server: loopback only, all state isolated, existing smbd."""
import tempfile, pathlib, socket, os, pwd, subprocess, json
root=pathlib.Path(tempfile.mkdtemp(prefix='synczen-recette-smb-'))
root.chmod(0o755)
share=root/'share'; share.mkdir(); share.chmod(0o777)
for d in ['state','lock','cache','private','pid','rpc','winbind','ntp']: (root/d).mkdir()
with socket.socket() as s: s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
owner=pwd.getpwuid(os.getuid()).pw_name
config=f'''[global]
server role = standalone server
security = user
map to guest = Bad User
guest account = {owner}
interfaces = 127.0.0.1
bind interfaces only = yes
smb ports = {port}
disable netbios = yes
server min protocol = SMB2_02
server max protocol = SMB3
server signing = disabled
load printers = no
disable spoolss = yes
printing = bsd
printcap name = /dev/null
state directory = {root}/state
lock directory = {root}/lock
cache directory = {root}/cache
private dir = {root}/private
pid directory = {root}/pid
ncalrpc dir = {root}/rpc
winbindd socket directory = {root}/winbind
ntp signd socket directory = {root}/ntp
log file = {root}/log.smbd
passdb backend = tdbsam:{root}/private/passdb.tdb
[fiction]
path = {share}
read only = no
guest ok = yes
guest only = yes
create mask = 0666
force create mode = 0666
directory mask = 0777
force directory mode = 0777
force user = {owner}
'''
conf=root/'smb.conf';conf.write_text(config)
with (root/'process.log').open('w') as log:
 p=subprocess.Popen(['/usr/bin/setsid','/usr/bin/smbd','--foreground','--no-process-group',f'--log-basename={root}','--debug-stdout',f'--configfile={conf}'],stdout=log,stderr=subprocess.STDOUT)
 pathlib.Path('/tmp/synczen-recette-smb.json').write_text(json.dumps(dict(pid=p.pid,port=port,root=str(root))))
 print('Fictitious SMB loopback port:',port,flush=True)
 try: p.wait()
 finally:
  if p.poll() is None: p.terminate();p.wait(timeout=5)
