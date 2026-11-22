import ast
import json
import time
from pathlib import Path

from core.eventlog import log_event
from core.registry import load_manifests

POLICY = {"read": "auto", "write": "auto",
          "sensitive": "confirm", "forbidden": "deny"}
AUDIT = Path(__file__).resolve().parent.parent / "data" / "audit.jsonl"


def _ask(summary):
    try:
        ans = input(f"[APPROVAL] Allow {summary}? (y/N): ")
        return ans.strip().lower() == "y"
    except EOFError:
        return False


def redact(summary):
    """What gets written to logs: numbers masked, message text reduced to its length."""
    tool, _, rest = str(summary).partition(" ")
    try:
        d = ast.literal_eval(rest) if rest.startswith("{") else None
    except Exception:
        d = None
    if not isinstance(d, dict):
        return tool + (" (details hidden)" if rest else "")
    out = []
    for k, v in d.items():
        v = str(v)
        if k == "number":
            v = "***" + v[-3:]
        elif k in ("text", "body", "message"):
            v = "<%d chars>" % len(v)
        else:
            v = v[:40]
        out.append("%s=%s" % (k, v))
    return tool + " " + ", ".join(out)


def audit(agent, tool, risk, decision, why="", summary=""):
    try:
        AUDIT.parent.mkdir(parents=True, exist_ok=True)
        with open(AUDIT, "a") as f:
            f.write(json.dumps({"t": time.strftime("%Y-%m-%d %H:%M:%S"), "agent": agent, "tool": tool, "risk": risk,
                                "decision": decision, "why": why, "detail": redact(summary)}) + "\n")
    except Exception:
        pass


def recent_audit(n=10):
    try:
        rows = [json.loads(x) for x in AUDIT.read_text().splitlines()[-n:]]
    except Exception:
        return "No audit entries yet."
    if not rows:
        return "No audit entries yet."
    return "\n".join("%s  %-8s %s  (%s)" % (r["t"][5:16], r["decision"], r["detail"], r["risk"]) for r in rows)


def authorize(agent, tool, needs, risk, summary, confirm_fn=_ask):
    safe = redact(summary)
    granted = load_manifests().get(agent, {}).get("permissions", [])
    if needs not in granted:
        log_event(agent, "perm.deny", input=safe,
                  output="missing " + needs, status="denied")
        audit(agent, tool, risk, "blocked", "missing " + needs, summary)
        return False
    policy = POLICY.get(risk, "deny")
    if policy == "deny":
        log_event(agent, "perm.deny", input=safe,
                  output="forbidden", status="denied")
        audit(agent, tool, risk, "blocked", "forbidden", summary)
        return False
    if policy == "confirm":
        ok = confirm_fn(summary)
        audit(agent, tool, risk, "approved" if ok else "denied", "asked the user", summary)
        try:
            log_event(agent, "perm.confirm", input=safe,
                      output="approved" if ok else "rejected",
                      status="ok" if ok else "denied",
                      approved_by="user" if ok else "user-rejected")
        except Exception:
            pass
        return ok
    return True
