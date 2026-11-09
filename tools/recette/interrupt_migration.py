"""Kill only the dedicated emulator's synthetic app at a migration marker."""
import subprocess, pathlib, time
adb=['/var/home/Snoopy63/Android/Sdk/platform-tools/adb','-s','emulator-5580']
identity=subprocess.check_output(adb+['emu','avd','name'],timeout=10).decode()
assert identity.splitlines()[0]=='SyncZen-Recette-API35'
path=pathlib.Path('/tmp/synczen-android-migration-interruption.txt')
with path.open('w') as log:
 p=subprocess.Popen(adb+['shell','am','instrument','-w','-e','class','fr.bonobo.synczen.data.AndroidMigrationCrashFixture','fr.bonobo.synczen.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  if 'SYNCZEN_READY_TO_KILL_SYNTHETIC_MIGRATION' in path.read_text():
   subprocess.run(adb+['shell','am','force-stop','fr.bonobo.synczen'],check=True,timeout=10)
   print('Killed only dedicated emulator app after first encrypted row; marker confirmed.',flush=True)
   p.wait(timeout=10)
   break
  if p.poll() is not None: raise RuntimeError('Fixture exited before marker: '+path.read_text())
  time.sleep(0.1)
 else:
  subprocess.run(adb+['shell','am','force-stop','fr.bonobo.synczen'],check=True,timeout=10)
  p.wait(timeout=10)
  raise RuntimeError('No migration marker: '+path.read_text())
