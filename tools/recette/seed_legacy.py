"""Create v1 DB containing only synthetic data, never copied from a real installation."""
import sqlite3
from pathlib import Path
p=Path('/tmp/synczen-legacy-fiction.db')
assert not p.exists()
c=sqlite3.connect(p)
c.executescript('''CREATE TABLE nas_config (id INTEGER NOT NULL PRIMARY KEY, serverType TEXT NOT NULL, address TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, sourceFolderUris TEXT NOT NULL, wifiSsid TEXT, requireCharging INTEGER NOT NULL, lastSyncTimestamp INTEGER NOT NULL);
CREATE TABLE sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, fileCount INTEGER NOT NULL);
CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);
INSERT INTO room_master_table VALUES(42,'b0d8db853bc8b5f14a76f9b71c1e48ec');
PRAGMA user_version=1;''')
c.execute('INSERT INTO nas_config VALUES(1,?,?,?,?,?,?,?,?)',('WEBDAV','http://nas.invalid/fictitious','fictional-user','fictional-password','content://fake/tree/one|content://fake/tree/two','Fictitious-Wifi',1,123456789))
c.execute("INSERT INTO sync_log VALUES(7,987654321,'SUCCESS','Fictitious upgrade log',42)")
c.commit();c.close()
print(p)
