"""Translates the Termux-style commands in engine/phone.py into Android calls."""
B = None

def _call(name, a="", b=""):
    r = str(B.call(name, str(a), str(b)))
    if r.startswith("ERR:"):
        raise RuntimeError(r[4:])
    return r

def run(cmd, timeout=15):
    n = cmd[0]
    if n == "termux-battery-status":
        return _call("battery")
    if n == "termux-torch":
        return _call("torch", cmd[1])
    if n == "termux-volume":
        return _call("volume_get") if len(cmd) == 1 else _call("volume_set", cmd[1], cmd[2])
    if n == "termux-notification":
        d = dict(zip(cmd[1::2], cmd[2::2]))
        return _call("notify", d.get("--title", ""), d.get("--content", ""))
    if n == "termux-clipboard-get":
        return _call("clipboard_get")
    if n == "termux-clipboard-set":
        return _call("clipboard_set", cmd[1])
    if n == "termux-open-url":
        return _call("open_url", cmd[1])
    if n == "termux-tts-speak":
        return _call("speak", cmd[1])
    if n == "termux-wifi-connectioninfo":
        return _call("wifi")
    if n.startswith("saann-"):
        return _call(n[6:].replace("-", "_"), *cmd[1:3])
    raise RuntimeError("%s is not available in the app yet" % n)
