"""Phone tools through Termux:API. Every tool declares what it needs and its risk.

risk 'read'/'write' run automatically; 'sensitive' always asks you first.
Private results (clipboard, location, messages) are NOT written to the event log.
Needs:  pkg install termux-api   AND the Termux:API app (F-Droid).
"""
import json, re, shutil, subprocess, sys
from pathlib import Path
from core.tools import tool, TOOLS

ROOT = Path(__file__).resolve().parent.parent
SPEC = {}

def _run(cmd, timeout=15):
    if not shutil.which(cmd[0]):
        raise RuntimeError("%s not found: run 'pkg install termux-api' and install "
                           "the Termux:API app" % cmd[0])
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout or "command failed").strip()[:200])
    return r.stdout.strip()

def ptool(name, needs, risk, args, doc, private=False):
    def deco(fn):
        SPEC[name] = {"args": args, "doc": doc, "risk": risk}
        tool(name, needs, risk)(fn)
        if private:
            TOOLS[name]["private"] = True
        return fn
    return deco

def _text(t, n):
    t = str(t).strip()
    if not t or len(t) > n:
        raise ValueError("text must be 1 to %d characters" % n)
    return t

def _number(n):
    n = str(n).replace(" ", "")
    if not re.fullmatch(r"\+?[0-9]{5,15}", n):
        raise ValueError("bad phone number")
    return n

@ptool("phone_battery", "phone.read", "read", {}, "battery level and charging state")
def battery():
    d = json.loads(_run(["termux-battery-status"]))
    return "%s%%, %s" % (d.get("percentage"), str(d.get("status", "")).lower())

@ptool("phone_wifi", "phone.read", "read", {}, "current wifi connection")
def wifi():
    d = json.loads(_run(["termux-wifi-connectioninfo"]))
    return "ssid %s, %s Mbps, signal %s" % (d.get("ssid"), d.get("link_speed_mbps"), d.get("rssi"))

@ptool("phone_clipboard_get", "phone.private", "read", {}, "text on the clipboard", private=True)
def clipboard_get():
    return _run(["termux-clipboard-get"])[:2000]

@ptool("phone_clipboard_set", "phone.write", "write", {"text": "string"}, "copy text to the clipboard")
def clipboard_set(text):
    _run(["termux-clipboard-set", _text(text, 2000)])
    return "copied"

@ptool("phone_notify", "phone.write", "write", {"title": "string", "text": "string"},
       "show a notification")
def notify(title, text):
    _run(["termux-notification", "--title", _text(title, 80), "--content", _text(text, 300)])
    return "shown"

@ptool("phone_speak", "phone.write", "write", {"text": "string"}, "say text out loud")
def speak(text):
    _run(["termux-tts-speak", _text(text, 500)], timeout=60)
    return "spoken"

@ptool("phone_volume_get", "phone.read", "read", {}, "all volume levels")
def volume_get():
    return ", ".join("%s %s/%s" % (v["stream"], v["volume"], v["max_volume"])
                     for v in json.loads(_run(["termux-volume"])))

@ptool("phone_volume_set", "phone.write", "write", {"stream": "string", "level": "integer"},
       "set volume; stream is music, ring, alarm, notification, system or call")
def volume_set(stream, level):
    if stream not in ("music", "ring", "alarm", "notification", "system", "call"):
        raise ValueError("unknown stream")
    _run(["termux-volume", stream, str(max(0, min(15, int(level))))])
    return "volume set"

@ptool("phone_torch", "phone.write", "write", {"state": "string"}, "flashlight; state is on or off")
def torch(state):
    if state not in ("on", "off"):
        raise ValueError("state must be on or off")
    _run(["termux-torch", state])
    return "torch " + state

@ptool("phone_brightness", "phone.write", "write", {"level": "integer"},
       "screen brightness from 0 to 255")
def brightness(level):
    _run(["termux-brightness", str(max(0, min(255, int(level))))])
    return "brightness set"

@ptool("phone_open_url", "phone.write", "write", {"url": "string"}, "open a web link in the browser")
def open_url(url):
    if not re.match(r"https?://[^\s]+$", str(url)):
        raise ValueError("only http and https links")
    _run(["termux-open-url", url])
    return "opened"

@ptool("phone_location", "phone.private", "sensitive", {}, "current location", private=True)
def location():
    d = json.loads(_run(["termux-location", "-p", "network"], timeout=40))
    return "lat %.5f, lon %.5f" % (d["latitude"], d["longitude"])

@ptool("phone_sms_list", "phone.private", "sensitive", {"limit": "integer"},
       "latest text messages", private=True)
def sms_list(limit=5):
    rows = json.loads(_run(["termux-sms-list", "-l", str(max(1, min(10, int(limit))))]))
    return "\n".join("%s: %s" % (r.get("number"), str(r.get("body"))[:120]) for r in rows)

@ptool("phone_send_sms", "phone.send", "sensitive", {"number": "string", "text": "string"},
       "send a text message")
def send_sms(number, text):
    _run(["termux-sms-send", "-n", _number(number), _text(text, 300)])
    return "sent"

@ptool("phone_call", "phone.call", "sensitive", {"number": "string"}, "place a phone call")
def call(number):
    _run(["termux-telephony-call", _number(number)])
    return "calling"

def install():
    out = []
    f = ROOT / "core" / "tools.py"
    src = f.read_text()
    old = 'output=str(out)[:500], outcome="verified")'
    if "[private]" in src:
        out.append("tools.py: already private-aware")
    elif old in src:
        f.with_suffix(".py.bak").write_text(src)
        f.write_text(src.replace(
            old, 'output=("[private]" if t.get("private") else str(out)[:500]),\n'
                 '                  outcome="verified")', 1))
        out.append("tools.py: private results are now kept out of the log")
    else:
        out.append("tools.py: could not patch, tell Claude")
    g = ROOT / "saann.py"
    s = g.read_text()
    if "agent.quick" in s:
        out.append("saann.py: already routes phone requests")
    elif 'def route(msg, st):\n' in s and '    return "chat"\n' in s and '    if r == "chat":\n' in s:
        g.with_suffix(".py.bak").write_text(s)
        s = s.replace('def route(msg, st):\n',
                      'def route(msg, st):\n    from engine import agent\n'
                      '    if agent.quick(msg):\n        return "phone"\n', 1)
        s = s.replace('    return "chat"\n',
                      '    if agent.wants_phone(msg):\n        return "phone"\n'
                      '    return "chat"\n', 1)
        s = s.replace('    if r == "chat":\n',
                      '    if r == "phone":\n        return agent.run(msg)["answer"]\n'
                      '    if r == "chat":\n', 1)
        s = s.replace('def respond(msg, st):\n',
                      'def respond(msg, st):\n    from engine import agent\n', 1)
        g.write_text(s)
        out.append("saann.py: phone requests are now routed")
    else:
        out.append("saann.py: could not patch, tell Claude")
    return "\n".join(out)

if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["install"]:
        print(install())
    elif a[:1] == ["list"]:
        for n, s in SPEC.items():
            print("%-18s %-9s %s" % (n, s["risk"], s["doc"]))
    elif a[:1] == ["test"]:
        from core.tools import call_tool
        print(call_tool("phone-agent", "phone_battery"))
    else:
        print("usage: python -m engine.phone list | test | install")
