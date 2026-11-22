"""Read-only snapshot of the brain for the app's detail screen (never changes anything)."""
import json, time

def _try(f, d=None):
    try:
        return f()
    except Exception:
        return d

def snapshot():
    from engine import brain
    b = brain.get()
    n = int(_try(lambda: b.n, 0))
    out = {"ts": int(time.time()), "regions": [], "totals": {}, "cortex": None, "motor": None, "state": {}}
    for name in list(_try(lambda: list(b.regions), [])):
        has = _try(lambda: b.has[name][:n])
        cnt = int(has.sum()) if has is not None else 0
        recent = []
        if has is not None:
            for i in [i for i in range(n - 1, -1, -1) if has[i]][:5]:
                recent.append(str(_try(lambda: b._name(b.meta[i]["id"]), "?"))[:40])
        out["regions"].append({"name": name, "concepts": cnt, "recent": recent})
    out["totals"] = {"concepts": n, "reflexes": int(_try(lambda: sum(len(v) for v in b.reflex.values()), 0))}
    out["state"] = _try(b.snapshot, {}) or {}
    cx = _try(lambda: brain._cortex().stats())
    if cx:
        out["cortex"] = cx
        seen = cx.get("seen", {})
        for r in out["regions"]:
            r["trained"] = int(seen.get(r["name"], 0))
            r["loss"] = cx.get("loss", {}).get(r["name"])
    out["motor"] = _try(lambda: brain._motor_get().stats())
    return out

def json_text():
    return json.dumps(snapshot(), default=str)
