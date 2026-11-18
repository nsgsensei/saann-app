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
    return "ready"

def respond(msg):
    import saann
    return saann.respond(msg, _state)


def stop():
    from engine import agent
    agent.STOP["flag"] = True
