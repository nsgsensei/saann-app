"""SAANN front door: one chat that routes to the right part.

Run:  python saann.py
Routes (every decision is logged so a routerANN can learn from it later):
  document -> planner.solve on the loaded document
  calc     -> planner.solve (pure code, no model)
  memory   -> planner.solve answering from stored facts
  chat     -> mind.chat (personality + what SAANN believes)
"""
import sys
from core.eventlog import log_event
from engine import planner, skills, mind

AGENT = "research-agent"
HELP = """commands:
  /file NAME   load a file from data/sandbox as the document
  /url URL     load a web page as the document
  /doc         show what is loaded      /clear   unload it
  /status      mind status              /beliefs what I believe
  /curious     what I want to find out  /quit
anything else: just talk or ask"""

class State:
    def __init__(self):
        self.text, self.label = None, ""

def route(msg, st):
    from engine import agent
    if agent.quick(msg):
        return "phone"
    steps, residue = planner.rule_plan(msg)
    kinds = {s["op"] for s in steps}
    if st.text and steps:
        return "document"
    if steps and not residue:
        if kinds <= {"calc"}:
            return "calc"
        if kinds <= {"answer_from_text"} and all(
                skills.recall(s["arg"]) for s in steps):
            return "memory"
    if agent.wants_phone(msg):
        return "phone"
    return "chat"

def _respond_core(msg, st):
    from engine import agent
    from engine import brain
    cmd = brain.command(msg)
    if cmd is not None:
        return cmd
    note = brain.self_layer(msg)
    if note is None:
        note = brain.listen(msg)
    if note is not None:
        return note
    brain.think_text(msg)
    r = None
    try:
        pl = brain.plan(msg)
        if pl and not st.text:
            stp = pl["steps"]
            if pl["act"] and not any(s["sensitive"] for s in stp):
                r = "phone"
            elif len(stp) == 1 and stp[0]["intent"] == "chat" and stp[0]["conf"] >= 0.9:
                r = "chat"
    except Exception:
        r = None
    if r is None:
        r = route(msg, st)
    log_event("router", "route", input=msg[:200], output=r)
    if r == "phone":
        return agent.run(msg)["answer"]
    if r == "chat":
        hit = brain.first(msg)
        if hit:
            return hit
        ans = brain.refine(msg, mind.chat(msg))
        brain.learn(msg, ans)
        return ans
    out = planner.solve(AGENT, msg, st.text if r == "document" else None,
                        source=st.label or "chat")
    return out["report"] if out["ok"] else "I couldn't handle that: " + out["reason"]

def respond(msg, st):
    from engine import brain
    out = _respond_core(msg, st)
    brain.note_turn(msg, out)
    return out

def _mind(*a):
    sys.argv = ["mind"] + list(a)
    mind.main()

def load(kind, arg, st):
    import engine.web  # registers the fetch_url tool
    from core.tools import call_tool
    r = (call_tool(AGENT, "read_file", path=arg) if kind == "file"
         else call_tool(AGENT, "fetch_url", url=arg))
    if not r["ok"]:
        return "could not load: %s" % r["error"]
    res = r["result"]
    st.text = res if kind == "file" else res["text"]
    st.label = arg[:90]
    return "loaded %s (%d characters)" % (arg[:60], len(st.text))

def command(line, st):
    cmd, _, arg = line.partition(" ")
    arg = arg.strip()
    if cmd in ("/file", "/url") and arg:
        print(load(cmd[1:], arg, st))
    elif cmd == "/doc":
        print("loaded:", st.label or "nothing")
    elif cmd == "/clear":
        st.text, st.label = None, ""
        print("unloaded")
    elif cmd == "/status":
        _mind("status")
    elif cmd == "/beliefs":
        _mind("beliefs")
    elif cmd == "/curious":
        _mind("curious")
    else:
        print(HELP)

def main():
    from engine.scheduler import _llm_up
    st = State()
    print("SAANN ready." + ("" if _llm_up() else
          "  (model server is not running; start it first)"))
    print("type /help for commands")
    try:
        mind.reflect()
    except Exception as e:
        print("mind:", e)
    while True:
        try:
            line = input("\nyou> ").strip()
        except (EOFError, KeyboardInterrupt):
            print(); break
        if not line:
            continue
        if line in ("/quit", "/exit"):
            break
        if line.startswith("/"):
            command(line, st)
            continue
        try:
            print("\nsaann>", respond(line, st))
        except Exception as e:
            print("\nsaann> something went wrong:", e)
            log_event("router", "error", input=line[:200], output=str(e)[:200],
                      status="error")

if __name__ == "__main__":
    main()
