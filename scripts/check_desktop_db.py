import sqlite3, sys

db = sqlite3.connect(r'C:\Users\16039\.foldledger\foldledger.db')
c = db.cursor()
c.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in c.fetchall()]
print('tables:', tables)
for t in ['transactions', 'ledgers', 'categories']:
    try:
        c.execute(f'SELECT COUNT(*) FROM {t}')
        print(t, c.fetchone()[0])
    except Exception as e:
        print(t, 'ERR', e)
print('--- ledgers ---')
try:
    c.execute("SELECT id, name, sceneKey, updatedAt FROM ledgers")
    for r in c.fetchall():
        print(r)
except Exception as e:
    print('ledgers ERR', e)
print('--- last 3 transactions ---')
try:
    c.execute("SELECT id, ledgerId, amountCents, note, updatedAt FROM transactions ORDER BY id DESC LIMIT 3")
    for r in c.fetchall():
        print(r)
except Exception as e:
    print('txn ERR', e)
