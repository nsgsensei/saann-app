from core.eventlog import log_event
from core.registry import load_manifests

POLICY = {"read": "auto", "write": "auto",
          "sensitive": "confirm", "forbidden": "deny"}

def _ask(summary):
    try:
        ans = input(f"[APPROVAL] Allow {summary}? (y/N): ")
        return ans.strip().lower() == "y"
    except EOFError:
        return False

def authorize(agent, tool, needs, risk, summary, confirm_fn=_ask):
    granted = load_manifests().get(agent, {}).get("permissions", [])
    if needs not in granted:
        log_event(agent, "perm.deny", input=summary,
                  output="missing " + needs, status="denied")
        return False
    policy = POLICY.get(risk, "deny")
    if policy == "deny":
        log_event(agent, "perm.deny", input=summary,
                  output="forbidden", status="denied")
        return False
    if policy == "confirm":
        ok = confirm_fn(summary)
        log_event(agent, "perm.confirm", input=summary,
                  output="approved" if ok else "rejected",
                  status="ok" if ok else "denied",
                  approved_by="user" if ok else "user-rejected")
        return ok
    return True
