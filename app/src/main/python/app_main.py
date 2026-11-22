import builtins
import os
import sys

_state = None

def init(files_dir, bridge):
    global _state
    root = os.path.join(files_dir, "saann")
    os.makedirs(os.path.join(root, "data"), exist_ok=True)
    sys.path.insert(0, root)
    os.chdir(root)
    import android_bridge
    android_bridge.B = bridge
    from engine import phone
    phone._run = android_bridge.run
    # approvals: the app shows a dialog instead of waiting on a keyboard
    builtins.input = lambda prompt="": bridge.confirm(str(prompt))
    import saann
    _state = saann.State()
    from engine import agent

    def _status(t):
        try:
            bridge.call("status", str(t), "")
        except Exception:
            pass

    agent.STATUS = _status

    def _step(phase, tool, args, ok, result):
        import json
        try:
            a = json.dumps(args)[:160] if args else ""
            bridge.call("live_step", json.dumps({"phase": phase, "tool": tool, "args": a,
                                                   "ok": bool(ok), "result": str(result)[:200]}), "")
        except Exception:
            pass

    agent.STEP = _step
    return "ready"

def respond(msg):
    if msg.startswith("/do "):
        return _do(msg[4:])
    import saann
    return saann.respond(msg, _state)


def stop():
    from engine import agent
    agent.STOP["flag"] = True


def _do(text):
    """Test one tool by hand:  /do 8 screen_look   or   /do screen_tap x=540 y=1200"""
    import shlex, time
    from core.tools import call_tool
    from engine import phone
    parts = shlex.split(text)
    if parts and parts[0].isdigit():
        time.sleep(min(30, int(parts[0])))
        parts = parts[1:]
    if not parts or parts[0] not in phone.SPEC:
        return "tools: " + ", ".join(phone.SPEC)
    args = {}
    for p in parts[1:]:
        k, _, v = p.partition("=")
        args[k] = int(v) if v.lstrip("-").isdigit() else v
    r = call_tool("phone-agent", parts[0], **args)
    return str(r.get("result") if r["ok"] else "ERROR: " + str(r["error"]))


def brain_snapshot():
    import json
    try:
        from engine import brain
        return json.dumps(brain.get().snapshot())
    except Exception:
        return "{}"


def brain_view():
    try:
        from engine import brain_view as _v
        return _v.json_text()
    except Exception as e:
        return '{"error": "%s"}' % str(e).replace('"', "'")
