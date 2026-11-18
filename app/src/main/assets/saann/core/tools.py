from pathlib import Path
from core.eventlog import log_event
from safety.permissions import authorize

ROOT = Path(__file__).resolve().parent.parent
SANDBOX = (ROOT / "data" / "sandbox").resolve()
SANDBOX.mkdir(parents=True, exist_ok=True)
TOOLS = {}

def tool(name, needs, risk):
    def deco(fn):
        TOOLS[name] = {"fn": fn, "needs": needs, "risk": risk}
        return fn
    return deco

def _safe(path):
    p = (SANDBOX / path).resolve()
    if not p.is_relative_to(SANDBOX):
        raise PermissionError("path outside sandbox")
    return p

@tool("read_file", "fs.read", "read")
def read_file(path):
    return _safe(path).read_text()

@tool("write_file", "fs.write", "write")
def write_file(path, text):
    p = _safe(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
    return "written"

@tool("delete_file", "fs.write", "sensitive")
def delete_file(path):
    _safe(path).unlink()
    return "deleted"

def call_tool(agent, name, **kw):
    t = TOOLS.get(name)
    if t is None:
        return {"ok": False, "error": "unknown tool"}
    if not authorize(agent, name, t["needs"], t["risk"], f"{name} {kw}"):
        return {"ok": False, "error": "denied"}
    try:
        out = t["fn"](**kw)
        log_event(agent, "tool." + name, input=str(kw),
                  output=("[private]" if t.get("private") else str(out)[:500]),
                  outcome="verified")
        return {"ok": True, "result": out}
    except Exception as e:
        log_event(agent, "tool." + name, input=str(kw),
                  output=str(e), status="error")
        return {"ok": False, "error": str(e)}
