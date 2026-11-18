import json, time
from core.eventlog import _conn
from engine.primitives import _kw

def _db():
    c = _conn()
    c.execute("CREATE TABLE IF NOT EXISTS skills(sig TEXT PRIMARY KEY, "
              "request TEXT, steps TEXT, successes INTEGER, failures INTEGER, "
              "created REAL, used REAL)")
    c.execute("CREATE TABLE IF NOT EXISTS facts(qkey TEXT, question TEXT, "
              "answer TEXT, quote TEXT, source TEXT, ts REAL, times INTEGER, "
              "PRIMARY KEY(qkey, source))")
    return c

def _sig(text):
    return " ".join(sorted(_kw(text)))

def _jac(a, b):
    a, b = set(a), set(b)
    return len(a & b) / len(a | b) if (a | b) else 0.0

def trust(s, f):
    return (s + 1) / (s + f + 2)

def find_skill(request):
    k = _kw(request)
    best = None
    with _db() as c:
        rows = c.execute("SELECT sig, steps, successes, failures FROM skills").fetchall()
    for sig, steps, s, f in rows:
        sim = _jac(k, sig.split())
        if sim >= 0.85 and trust(s, f) >= 0.6 and (best is None or sim > best[0]):
            best = (sim, sig, json.loads(steps))
    return {"sig": best[1], "steps": best[2]} if best else None

def record(request, steps, ok, sig=None):
    sig = sig or _sig(request)
    now = time.time()
    with _db() as c:
        row = c.execute("SELECT successes, failures FROM skills WHERE sig=?",
                        (sig,)).fetchone()
        if row:
            c.execute("UPDATE skills SET successes=?, failures=?, used=? WHERE sig=?",
                      (row[0] + (1 if ok else 0), row[1] + (0 if ok else 1), now, sig))
        elif ok:
            c.execute("INSERT INTO skills VALUES (?,?,?,?,?,?,?)",
                      (sig, request, json.dumps(steps), 1, 0, now, now))

def remember(question, answer, quote, source):
    k = _sig(question)
    if len(k.split()) < 2:
        return
    with _db() as c:
        c.execute("INSERT INTO facts VALUES (?,?,?,?,?,?,1) "
                  "ON CONFLICT(qkey, source) DO UPDATE SET answer=excluded.answer, "
                  "quote=excluded.quote, ts=excluded.ts, times=times+1",
                  (k, question, answer, quote, source, time.time()))

def recall(question):
    k = _kw(question)
    if len(k) < 2:
        return None
    with _db() as c:
        rows = c.execute("SELECT qkey, answer, quote, source, ts FROM facts").fetchall()
    best = None
    for qk, a, q, src, ts in rows:
        sim = _jac(k, qk.split())
        if sim >= 0.8 and (best is None or (sim, ts) > (best[0], best[1])):
            best = (sim, ts, a, q, src)
    if not best:
        return None
    return {"answer": best[2], "quote": best[3], "source": best[4],
            "age_days": int((time.time() - best[1]) / 86400)}
