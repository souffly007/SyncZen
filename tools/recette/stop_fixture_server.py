"""Stop only the isolated smbd process group created for this recipe."""
import json,pathlib,os,signal
info=json.loads(pathlib.Path('/tmp/synczen-recette-smb.json').read_text())
root=pathlib.Path(info['root'])
assert root.parent==pathlib.Path('/tmp') and root.name.startswith('synczen-recette-smb-')
cmd=pathlib.Path(f"/proc/{info['pid']}/cmdline").read_bytes()
assert str(root).encode() in cmd and b'smbd' in cmd
assert os.getpgid(info['pid'])==info['pid']
os.killpg(info['pid'],signal.SIGTERM)
print('Stopped only isolated fictitious SMB process group. Fixture files retained.')
