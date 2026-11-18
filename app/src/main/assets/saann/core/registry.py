import json
from pathlib import Path
from core.eventlog import log_event

ROOT = Path(__file__).resolve().parent.parent
MODULE_DIR = ROOT / "config" / "modules"
LICENSE_FILE = ROOT / "config" / "license.json"

TIER_ACCESS = {
    "owner": {"core", "free", "pro", "addon"},
    "pro": {"core", "free", "pro"},
    "free": {"core", "free"},
}

def load_license():
    try:
        return json.loads(LICENSE_FILE.read_text())
    except FileNotFoundError:
        return {"tier": "free"}

def load_manifests():
    out = {}
    for f in sorted(MODULE_DIR.glob("*.json")):
        m = json.loads(f.read_text())
        out[m["id"]] = m
    return out

def is_licensed(m, lic):
    allowed = TIER_ACCESS.get(lic.get("tier", "free"), set())
    return m.get("tier", "free") in allowed

def load_all():
    manifests = load_manifests()
    lic = load_license()
    started, skipped = [], {}

    def start(mid, stack=()):
        if mid in started:
            return True
        if mid in skipped:
            return False
        m = manifests.get(mid)
        if m is None:
            skipped[mid] = "missing"
            return False
        if mid in stack:
            skipped[mid] = "dependency cycle"
            return False
        if not m.get("enabled", False):
            skipped[mid] = "disabled"
            return False
        if not is_licensed(m, lic):
            skipped[mid] = "not licensed"
            return False
        for dep in m.get("depends_on", []):
            if not start(dep, stack + (mid,)):
                skipped[mid] = "needs " + dep
                return False
        started.append(mid)
        return True

    for mid in manifests:
        start(mid)
    for mid in started:
        log_event("registry", "module.start", input=mid)
    for mid, why in skipped.items():
        log_event("registry", "module.skip", input=mid,
                  output=why, status="skipped")
    return started, skipped

if __name__ == "__main__":
    s, k = load_all()
    print("Started:", s)
    print("Skipped:", k)
