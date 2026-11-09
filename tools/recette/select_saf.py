"""Drive the existing Android picker on the dedicated emulator only; no extra UI tool."""
import subprocess,time,re,xml.etree.ElementTree as E
adb=['/var/home/Snoopy63/Android/Sdk/platform-tools/adb','-s','emulator-5580']
assert subprocess.check_output(adb+['emu','avd','name'],timeout=10).decode().splitlines()[0]=='SyncZen-Recette-API35'
def call(*args): return subprocess.check_output(adb+list(args),timeout=20).decode()
def nodes():
 call('shell','uiautomator','dump','/data/local/tmp/synczen-recette-ui.xml')
 return list(E.fromstring(call('exec-out','cat','/data/local/tmp/synczen-recette-ui.xml')).iter('node'))
def click(text):
 n=next(n for n in nodes() if text in n.attrib.get('text',''))
 a,b,c,d=map(int,re.findall(r'\d+',n.attrib['bounds']))
 call('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(0.7)
call('shell','am','start','-n','fr.bonobo.synczen/.MainActivity');time.sleep(1)
for _ in range(5):
 if any('Ajouter un dossier' in n.attrib.get('text','') for n in nodes()): break
 call('shell','input','swipe','500','1650','500','600','700');time.sleep(0.7)
click('Ajouter un dossier')
current=[n.attrib.get('text','') for n in nodes()]
if 'source.txt' not in current:
 if 'SyncZenFiction' not in current: click('Download')
 click('SyncZenFiction')
click('USE THIS FOLDER');click('ALLOW');time.sleep(2)
permissions=call('shell','dumpsys','activity','permissions')
assert 'SyncZenFiction' in permissions, 'No grant recorded after selection'
print(permissions,flush=True)
call('shell','am','force-stop','fr.bonobo.synczen')
call('shell','am','start','-n','fr.bonobo.synczen/.MainActivity');time.sleep(1)
assert 'SyncZenFiction' in call('shell','dumpsys','activity','permissions'), 'Grant did not survive restart'
print('Synthetic SAF selection complete, grant still present after process restart.',flush=True)
