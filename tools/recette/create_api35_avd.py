"""Install only the explicitly approved official API35 image and new dedicated AVD."""
from pathlib import Path
import hashlib, zipfile, shutil
sdk=Path('/var/home/Snoopy63/Android/Sdk')
archive=Path('/tmp/synczen-api35-r09.zip')
image=sdk/'system-images/android-35/google_apis/x86_64'
avdroot=Path('/var/home/Snoopy63/.android/avd')
name='SyncZen-Recette-API35'
avd=avdroot/(name+'.avd')
assert not image.exists() and not avd.exists() and not (avdroot/(name+'.ini')).exists(), 'Refuse replacing an existing image or AVD'
assert archive.stat().st_size==1738815903
h=hashlib.sha1()
with archive.open('rb') as stream:
 while chunk:=stream.read(1024*1024): h.update(chunk)
assert h.hexdigest()=='0103e6dab21290c4b9d16550a3ce99476f884eef', 'Official checksum mismatch'
print('Official archive size/checksum verified',flush=True)
with zipfile.ZipFile(archive) as z:
 print('Expanded image bytes:',sum(i.file_size for i in z.infolist()),flush=True)
 image.mkdir(parents=True)
 for info in z.infolist():
  relative=Path(info.filename)
  assert relative.parts[0]=='x86_64' and '..' not in relative.parts
  target=image.joinpath(*relative.parts[1:])
  if info.is_dir(): target.mkdir(parents=True,exist_ok=True)
  else:
   target.parent.mkdir(parents=True,exist_ok=True)
   with z.open(info) as src,target.open('wb') as dst: shutil.copyfileobj(src,dst,1024*1024)
   mode=info.external_attr>>16
   if mode: target.chmod(mode & 0o777)
avd.mkdir(parents=True)
(avdroot/(name+'.ini')).write_text(f'avd.ini.encoding=UTF-8\npath={avd}\npath.rel=avd/{name}.avd\ntarget=android-35\n')
(avd/'config.ini').write_text(f'''AvdId={name}
avd.ini.displayname={name}
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.gpu.enabled=yes
hw.gpu.mode=swangle
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.mainKeys=no
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
hw.gps=no
hw.sdCard=no
hw.initialOrientation=Portrait
disk.dataPartition.size=4G
image.sysdir.1=system-images/android-35/google_apis/x86_64/
tag.id=google_apis
tag.display=Google APIs
PlayStore.enabled=false
showDeviceFrame=no
fastboot.forceColdBoot=yes
''')
print('Created:',avd,flush=True)
