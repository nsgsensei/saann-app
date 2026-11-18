import json, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "config" / "models.json"
USAGE = ROOT / "data" / "usage.json"

def load():
    return json.loads(CFG.read_text())

def save(c):
    CFG.write_text(json.dumps(c, indent=2))

def get(mid):
    for m in load()["models"]:
        if m["id"] == mid:
            return m

def installed(m):
    if m["kind"] == "cloud":
        return True
    f = ROOT / "models" / m["file"]
    return f.exists() and f.stat().st_size >= m["size_mb"] * 1024 * 1024 * 0.9

def active_local_file():
    return get(load()["active_local"])["file"]

def usage():
    month = time.strftime("%Y-%m")
    try:
        u = json.loads(USAGE.read_text())
    except Exception:
        u = {}
    if u.get("month") != month:
        u = {"month": month, "cloud_tokens": 0}
    return u

def add_usage(n):
    u = usage()
    u["cloud_tokens"] += n
    USAGE.write_text(json.dumps(u))

def cap_reached():
    return usage()["cloud_tokens"] >= load()["cloud_monthly_token_cap"]

def download(mid):
    m = get(mid)
    if not m or m["kind"] != "local":
        print("not a downloadable model"); return
    ok = input(f"Download {m['name']} (~{m['size_mb']} MB)? (y/N): ")
    if ok.strip().lower() != "y":
        return
    path = ROOT / "models" / m["file"]
    subprocess.run(["curl", "-L", "-C", "-", "-o", str(path), m["url"]])

def use(mid):
    c = load()
    m = get(mid)
    if not m:
        print("unknown model"); return
    if not installed(m):
        print("not downloaded yet"); return
    c["active_local" if m["kind"] == "local" else "active_cloud"] = mid
    save(c)
    print("active", m["kind"], "model:", mid)

def set_mode(mode):
    if mode not in ("local", "cloud", "hybrid"):
        print("mode must be local, cloud or hybrid"); return
    c = load(); c["mode"] = mode; save(c); print("mode:", mode)

def show():
    c = load()
    print("mode:", c["mode"], "| cloud tokens this month:",
          usage()["cloud_tokens"], "/", c["cloud_monthly_token_cap"])
    for m in c["models"]:
        act = m["id"] in (c["active_local"], c["active_cloud"])
        print(("* " if act else "  ") + m["id"], "-", m["name"],
              "[ready]" if installed(m) else "[not downloaded]")

if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] == "list": show()
    elif a[0] == "download": download(a[1])
    elif a[0] == "use": use(a[1])
    elif a[0] == "mode": set_mode(a[1])
