import sqlite3, time, uuid
from pathlib import Path

DB = Path(__file__).resolve().parent.parent / "data" / "saann.db"

def _conn():
    c = sqlite3.connect(DB)
    c.execute("""CREATE TABLE IF NOT EXISTS events(
        id TEXT PRIMARY KEY, ts REAL, agent TEXT, action TEXT,
        input TEXT, output TEXT, model TEXT, confidence REAL,
        status TEXT, approved_by TEXT, outcome TEXT)""")
    return c

def log_event(agent, action, input="", output="", model="",
              confidence=None, status="ok", approved_by="auto",
              outcome="unverified"):
    eid = "evt_" + uuid.uuid4().hex[:10]
    with _conn() as c:
        c.execute("INSERT INTO events VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                  (eid, time.time(), agent, action, input, output,
                   model, confidence, status, approved_by, outcome))
    return eid

def recent(n=10):
    with _conn() as c:
        return c.execute(
            "SELECT * FROM events ORDER BY ts DESC LIMIT ?", (n,)).fetchall()
