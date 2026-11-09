"""Configure fictitious credentials in ONLY the isolated test server's private passdb."""
import json,pathlib,os,pwd,subprocess,signal
info=json.loads(pathlib.Path('/tmp/synczen-recette-smb.json').read_text())
root=pathlib.Path(info['root'])
assert root.name.startswith('synczen-recette-smb-') and root.parent==pathlib.Path('/tmp')
cmd=pathlib.Path(f"/proc/{info['pid']}/cmdline").read_bytes()
assert str(root).encode() in cmd and b'smbd' in cmd
owner=pwd.getpwuid(os.getuid()).pw_name
fake_password='fiction-worker'
hashline=subprocess.check_output(['/usr/bin/openssl','dgst','-provider','legacy','-md4'],input=fake_password.encode('utf-16-le')).decode().strip()
nt=hashline.split()[-1].upper()
assert len(nt)==32
passdb=root/'private/fictitious-smbpasswd'
passdb.write_text(f'{owner}:{os.getuid()}:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX:{nt}:[U          ]:LCT-00000001:\n')
passdb.chmod(0o600)
(root/'users.map').write_text(f'{owner} = fiction-worker\n')
conf=root/'smb.conf'; text=conf.read_text()
text=text.replace(f'passdb backend = tdbsam:{root}/private/passdb.tdb',f'passdb backend = smbpasswd:{passdb}\nusername map = {root}/users.map')
conf.write_text(text)
os.killpg(info['pid'],signal.SIGTERM)
print('Only isolated fixture passdb configured; no OS account/password/system Samba change.',flush=True)
with (root/'process-auth.log').open('w') as log:
 p=subprocess.Popen(['/usr/bin/setsid','/usr/bin/smbd','--foreground','--no-process-group',f'--log-basename={root}','--debug-stdout',f'--configfile={conf}'],stdout=log,stderr=subprocess.STDOUT)
 info['pid']=p.pid;pathlib.Path('/tmp/synczen-recette-smb.json').write_text(json.dumps(info))
 try:p.wait()
 finally:
  if p.poll() is None:p.terminate();p.wait(timeout=5)
