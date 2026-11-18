import argparse, datetime as dt, shutil, subprocess, time, urllib.request
from pathlib import Path
from core.eventlog import _conn, log_event
from core.gateway import servers

ROOT = Path(__file__).resolve().parent.parent
BRIEFS = ROOT / "data" / "briefs"
MIN_EVERY = 15
MAX_TASKS = 20

def _db():
    c = _conn()
    c.execute("CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, "
              "name TEXT, request TEXT, url TEXT, kind TEXT, at TEXT, "
              "every_min INTEGER, enabled INTEGER, next_run REAL, "
              "last_run REAL, last_status TEXT)")
    return c

def _next(kind, at, every, after):
    if kind == "every":
        return after + every * 60
    h, m = (int(x) for x in at.split(":"))
    a = dt.datetime.fromtimestamp(after)
    c = a.replace(hour=h, minute=m, second=0, microsecond=0)
    if c <= a:
        c += dt.timedelta(days=1)
    return c.timestamp()

def add(name, request, url, daily, every):
    if bool(daily) == bool(every):
        raise ValueError("give exactly one of --daily HH:MM or --every MINUTES")
    if url and not url.startswith(("http://", "https://")):
        raise ValueError("url must start with http:// or https://")
    if daily:
        h, m = (int(x) for x in daily.split(":"))
        if not (0 <= h < 24 and 0 <= m < 60):
            raise ValueError("bad time, use HH:MM")
    if every and every < MIN_EVERY:
        raise ValueError("--every must be at least %d minutes" % MIN_EVERY)
    kind = "daily" if daily else "every"
    with _db() as c:
        if c.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] >= MAX_TASKS:
            raise ValueError("too many tasks (max %d)" % MAX_TASKS)
        nxt = _next(kind, daily, every, time.time())
        cur = c.execute("INSERT INTO tasks(name,request,url,kind,at,every_min,"
                        "enabled,next_run) VALUES(?,?,?,?,?,?,1,?)",
                        (name, request, url or "", kind, daily, every, nxt))
        return cur.lastrowid, nxt

def _llm_up():
    sv = servers()
    cfg = sv.get("main") or list(sv.values())[0]
    try:
        urllib.request.urlopen("http://127.0.0.1:%d/health" % cfg["port"],
                               timeout=3).read()
        return True
    except Exception:
        return False

def _notify(name, report):
    if shutil.which("termux-notification"):
        try:
            subprocess.run(["termux-notification", "--title", "SAANN: " + name,
                            "--content", report[:300]], timeout=10)
        except Exception:
            pass

def run_task(row):
    tid, name, request, url = row[0], row[1], row[2], row[3]
    report = ""
    if not _llm_up():
        status = "skipped: model server not running"
    else:
        try:
            if url:
                from engine.web import read_url
                out = read_url("research-agent", request, url)
            else:
                from engine.planner import solve
                out = solve("research-agent", request, None, source="scheduled")
            if out["ok"]:
                status, report = "ok", out["report"]
            else:
                status = "failed: " + out["reason"][:150]
        except Exception as e:
            status = "failed: " + str(e)[:150]
    if report:
        BRIEFS.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y-%m-%d_%H%M")
        (BRIEFS / ("%s_task%d.md" % (stamp, tid))).write_text(
            "# %s\n\nRequest: %s\nSource: %s\nRan: %s\n\n%s\n" % (
                name, request, url or "memory",
                time.strftime("%Y-%m-%d %H:%M"), report))
        _notify(name, report)
    log_event("scheduler", "task.run", input=name, output=status,
              status="ok" if status == "ok" else "error")
    return status, report

def loop():
    print("scheduler running (CTRL+C to stop)")
    while True:
        now = time.time()
        with _db() as c:
            due = c.execute("SELECT id,name,request,url,kind,at,every_min FROM tasks "
                            "WHERE enabled=1 AND next_run<=?", (now,)).fetchall()
        for row in due:
            print(time.strftime("%H:%M"), "running:", row[1])
            status, _ = run_task(row)
            done = time.time()
            with _db() as c:
                c.execute("UPDATE tasks SET last_run=?, last_status=?, next_run=? "
                          "WHERE id=?", (done, status,
                                         _next(row[4], row[5], row[6], done), row[0]))
            print("  ->", status)
        try:
            from engine import mind
            mind.tick()
        except Exception as e:
            print('mind:', e)
        time.sleep(30)

def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd")
    a = sp.add_parser("add")
    a.add_argument("name"); a.add_argument("request")
    a.add_argument("--url", default="")
    a.add_argument("--daily"); a.add_argument("--every", type=int)
    sp.add_parser("list")
    r = sp.add_parser("remove"); r.add_argument("id", type=int)
    o = sp.add_parser("once"); o.add_argument("id", type=int)
    sp.add_parser("run"); sp.add_parser("briefs")
    args = ap.parse_args()
    if args.cmd == "add":
        try:
            tid, nxt = add(args.name, args.request, args.url, args.daily, args.every)
            print("task", tid, "added; next run",
                  time.strftime("%Y-%m-%d %H:%M", time.localtime(nxt)))
        except Exception as e:
            print("ERROR:", e)
    elif args.cmd == "list":
        with _db() as c:
            for t in c.execute("SELECT id,name,kind,at,every_min,next_run,"
                               "last_status FROM tasks").fetchall():
                when = t[3] if t[2] == "daily" else "every %d min" % t[4]
                print(t[0], "|", t[1], "|", when, "| next",
                      time.strftime("%m-%d %H:%M", time.localtime(t[5])),
                      "| last:", t[6])
    elif args.cmd == "remove":
        with _db() as c:
            c.execute("DELETE FROM tasks WHERE id=?", (args.id,))
        print("removed", args.id)
    elif args.cmd == "once":
        with _db() as c:
            row = c.execute("SELECT id,name,request,url FROM tasks WHERE id=?",
                            (args.id,)).fetchone()
        if not row:
            print("no such task"); return
        status, report = run_task(row)
        print(status); print(report)
    elif args.cmd == "run":
        loop()
    elif args.cmd == "briefs":
        files = sorted(BRIEFS.glob("*.md")) if BRIEFS.exists() else []
        print("\n".join(f.name for f in files[-10:]) or "no briefs yet")
        if files:
            print("\n" + files[-1].read_text())
    else:
        ap.print_help()

if __name__ == "__main__":
    main()
