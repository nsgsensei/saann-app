"""Jarvis-style replies: instant, natural, a little formal. Online, sir. Working on it.
Wraps respond(); never changes what SAANN decides or learns. Live step card, animations
and icon are never touched. Settings: /jarvis on | off | call me NAME | call me none
"""
import datetime
import json
import random
import re
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILE = ROOT / "data" / "jarvis.json"
DEFAULT = {"on": True, "address": "sir"}
_last = {}
_lock = threading.Lock()


def cfg():
    c = dict(DEFAULT)
    try:
        c.update(json.loads(FILE.read_text()))
    except Exception:
        pass
    return c


def save(**kv):
    c = cfg()
    c.update(kv)
    FILE.parent.mkdir(parents=True, exist_ok=True)
    FILE.write_text(json.dumps(c))
    return c


def A():
    a = str(cfg().get("address") or "").strip()
    return (", " + a) if a else ""


def pick(key, options):
    with _lock:
        prev = _last.get(key)
        pool = [o for o in options if o != prev] or list(options)
        choice = random.choice(pool)
        _last[key] = choice
        return choice


def fmt(s):
    return s.replace("{a}", A()).replace("  ", " ")


def say(key, options):
    return fmt(pick(key, options))


def _norm(msg):
    t0 = re.sub(r"\s+", " ", re.sub(r"[^a-z0-9' ]", " ", str(msg).lower())).strip()
    t = re.sub(r"\b(hey|hi|ok|okay|yo|please|jarvis|saann|saan|sann)\b", " ", t0)
    t = re.sub(r"\s+", " ", t).strip()
    return t if t else ("hello" if t0 else "")


def _daypart(now=None):
    h = (now or datetime.datetime.now()).hour
    return "morning" if 5 <= h < 12 else "afternoon" if 12 <= h < 18 else "evening" if 18 <= h < 23 else "night"


GREET = re.compile(r"^(hello|hello there|hi there|hiya|howdy|good (morning|afternoon|evening)|what'?s up|sup|are you (up|awake)|wake up|you awake|"
                   r"you there|are you there|are you online|online|anyone there|ready|are you ready)$")
STATUS = re.compile(r"^(status|system status|status report|give me a status( report)?|report|run diagnostics|diagnostics|how are you|how are you doing|"
                    r"how'?s everything|how are things|systems check|system check|are all systems (ok|okay|up|running))$")
CAN = re.compile(r"^(what can you do|what do you do|help|help me|capabilities|what are your capabilities|what are you capable of)$")
TIME = re.compile(r"^(what'?s |what is )?(the )?(current )?time( is it)?( now| right now)?$|^what time is it( now)?$|^do you know what time it is$")
DATE = re.compile(r"^(what'?s |what is )?(today'?s |the )?(date|day)( is it)?( today)?$|^what day is (it|today)$|^which day is today$")
START = re.compile(r"^(start|bring up|wake up|turn on|boot) (the )?(servers?|models?|language cores?|cores?|brain servers?)( up)?$")
NIGHT = re.compile(r"^(good ?night|goodnight|going to sleep|talk to you later|see you later|bye|goodbye)$")


def local(msg):
    t = _norm(msg)
    if not t or len(t) > 60:
        return None
    m = GREET.match(t)
    if m:
        if t.startswith("good"):
            part = m.group(2) or _daypart()
            return say("greet", ["Good %s{a}. Online and ready." % part, "Good %s{a}. All systems are active. What shall we do?" % part,
                                 "Good %s{a}. At your service." % part])
        if "online" in t or "there" in t or "awake" in t or t == "wake up" or "up" == t.split()[-1] or "ready" in t:
            return say("presence", ["Online{a}. All systems active.", "For you{a}, always. Online and listening.",
                                    "Here{a}. Fully active and ready.", "Online{a}. How can I help?"])
        return say("hello", ["Hello{a}. Online and ready.", "Hello{a}. What can I do for you?", "At your service{a}.", "Welcome back{a}. All systems active."])
    if TIME.match(t):
        return fmt("It is %s{a}." % datetime.datetime.now().strftime("%I:%M %p").lstrip("0"))
    if DATE.match(t):
        return fmt("Today is %s{a}." % datetime.datetime.now().strftime("%A, %d %B %Y"))
    if STATUS.match(t):
        return status_report()
    if CAN.match(t):
        return fmt("I can open and operate your apps, read the screen, take screenshots, set timers and alarms, do maths, "
                   "remember things, read documents and answer questions{a}. Say \"status\" for a system report, "
                   "or just tell me what you need.")
    if START.match(t):
        return start_servers()
    if NIGHT.match(t):
        return say("bye", ["Of course{a}. I'll be here whenever you need me.", "Understood{a}. Standing by.", "Rest well{a}. I'm standing by."])
    return None


def _up(port):
    try:
        from engine import servers
        return bool(servers.up(port))
    except Exception:
        return None


def status_report():
    lines = [say("status", ["Online and fully operational{a}.", "All primary systems are active{a}.", "Systems nominal{a}. Standing by."])]
    main, fast = _up(8080), _up(8081)
    down = []
    if main is not None:
        lines.append("- Main language core: " + ("online" if main else "offline"))
        if not main:
            down.append("main")
    if fast is not None:
        lines.append("- Quick core: " + ("online" if fast else "offline"))
        if not fast:
            down.append("quick")
    try:
        from engine import brain
        b = brain.get()
        links = len(getattr(b, "links", {}) or {})
        lines.append("- Brain: %d concepts, %d links" % (int(b.n), links))
    except Exception:
        pass
    try:
        from engine import motor
        lines.append("- Motor cortex: %d intents ready" % motor.get().stats()["intents"])
    except Exception:
        pass
    now = datetime.datetime.now()
    lines.append("It is %s on %s." % (now.strftime("%H:%M"), now.strftime("%A")))
    if down:
        lines.append(fmt("The %s core%s offline{a}. Say \"start servers\" and I'll bring %s up." % (
            " and ".join(down), " is" if len(down) == 1 else "s are", "it" if len(down) == 1 else "them")))
    return "\n".join(lines)


def start_servers():
    try:
        import shutil
        from engine import servers
        if not shutil.which("llama-server"):
            return fmt("The language cores run in Termux on this phone{a}. Start them there with: bash ~/saann/start_llm.sh")
        if servers.up(8080):
            return fmt("The language cores are already online{a}.")
        threading.Thread(target=servers.ensure, daemon=True).start()
        return fmt("Bringing the language cores online{a}. This takes about thirty seconds. Say \"status\" to check on them.")
    except Exception:
        return fmt("I couldn't reach the server controls{a}. Try: bash ~/saann/start_llm.sh in Termux.")


def _clean_err(s):
    s = re.sub(r"^(error|err)\s*:\s*", "", s.strip(), flags=re.I)
    s = re.sub(r"^(TypeError|ValueError|RuntimeError|Exception)\s*:\s*", "", s)
    return s[:170].rstrip()


RULES = [
    (r"^torch on\.?$", ["The flashlight is on{a}.", "Flashlight on{a}.", "Lighting it up{a}."]),
    (r"^torch off\.?$", ["The flashlight is off{a}.", "Flashlight off{a}."]),
    (r"^opened (.+?)\.?$", ["{0} is open{a}.", "Opening {0} now{a}.", "{0} is on screen{a}."]),
    (r"^(?:clicked|tapped) (.+?)\.?$", ["Done{a}. I tapped {0}.", "Tapped {0}{a}."]),
    (r"^typed\.?$", ["Typed it in{a}.", "Done{a}. The text is in."]),
    (r"^pasted\.?$", ["Pasted{a}."]),
    (r"^pressed (home|back|recents|notifications|enter)\.?$", ["Done{a}.", "{0} pressed{a}."]),
    (r"^scrolled (up|down|left|right)\.?$", ["Scrolled {0}{a}.", "Done{a}. Scrolled {0}."]),
    (r"^swiped (.+?)\.?$", ["Swiped{a}.", "Done{a}."]),
    (r"^(?:dragged|pinched.*|long-pressed.*)\.?$", ["Done{a}."]),
    (r"^done:?\s+(.+)$", ["Done{a}. {0}"]),
    (r"^flashlight (on|off)\.?$", ["The flashlight is {0}{a}.", "Flashlight {0}{a}."]),
    (r"^glad that helped\.?$", ["Glad to be of service{a}.", "Happy to help{a}."]),
    (r"^understood, i won't repeat that answer\.?$", ["Understood{a}. I won't give that answer again.", "Noted{a}. I'll avoid that answer."]),
    (r"^okay, i'll remember that\.?$", ["Noted{a}. I'll remember that.", "Committed to memory{a}."]),
    (r"^got it\. when you say (.+)$", ["Understood{a}. When you say {0}"]),
    (r"^thanks for correcting me\. next time i will say: (.+)$", ["Thank you for the correction{a}. Next time I'll say: {0}"]),
]
_COMPILED = [(re.compile(p, re.I | re.S), o) for p, o in RULES]
_ERR = re.compile(r"^(error|err)\s*:|^could not |^couldn't |^not available in the app yet|^\(no answer", re.I)
_RAW = re.compile(r"^(ok|done|success)\.?$", re.I)


def polish(msg, out):
    if not isinstance(out, str):
        return out
    s = out.strip()
    if not s or len(s) > 220 or "\n" in s and len(s) > 120:
        return out
    if _ERR.search(s):
        return fmt(pick("err", ["I'm afraid I ran into a problem{a}. ", "My apologies{a}. Something went wrong: ", "I couldn't complete that{a}. "]) + _clean_err(s))
    for rx, opts in _COMPILED:
        m = rx.match(s)
        if m:
            t = pick("r:" + rx.pattern, opts)
            for i, g in enumerate(m.groups()):
                g = (g or "").strip()
                t = t.replace("{%d}" % i, g[:1].upper() + g[1:] if (t.startswith("{%d}" % i) or (". {%d}" % i) in t) else g)
            return fmt(t)
    if _RAW.match(s):
        return say("done", ["Done{a}.", "All done{a}.", "Complete{a}."])
    return out


def command(msg):
    a = msg.strip().split(None, 1)
    arg = a[1].strip() if len(a) > 1 else "status"
    low = arg.lower()
    if low in ("on", "off"):
        save(on=(low == "on"))
        return fmt("Jarvis mode is on{a}.") if low == "on" else "Jarvis mode is off. I'll answer plainly."
    m = re.match(r"^call me (.+)$", arg, re.I)
    if m:
        who = m.group(1).strip()[:20]
        none = who.lower() in ("none", "nothing", "nobody")
        save(address="" if none else who)
        return "Understood. No form of address." if none else fmt("Very well{a}.")
    c = cfg()
    return "jarvis mode: %s, address: %s\n/jarvis on | off | call me NAME | call me none" % ("on" if c["on"] else "off", c["address"] or "none")


def wrap(fn):
    if getattr(fn, "_jarvis", False):
        return fn

    def w(msg, *a, **k):
        try:
            text = str(msg)
            if text.strip().lower().startswith("/jarvis"):
                return command(text)
            if text.lstrip().startswith("/") or not cfg()["on"]:
                return fn(msg, *a, **k)
            quick = local(text)
            if quick is not None:
                return quick
        except Exception:
            return fn(msg, *a, **k)
        out = fn(msg, *a, **k)
        try:
            return polish(msg, out)
        except Exception:
            return out
    w._jarvis = True
    return w


def install(g):
    if "respond" in g:
        g["respond"] = wrap(g["respond"])
