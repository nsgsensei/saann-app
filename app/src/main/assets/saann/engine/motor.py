"""SAANN motor cortex: a small neural net that turns language into actions.

It reads a message with its own typo-tolerant featurizer, classifies the intent
(battery, timer, call, chat ...) with a trained network, splits compound commands
into steps, pulls out slots (durations, times, names, maths) and flags sensitive
actions for confirmation. It pretrains itself on built-in examples the first time
it runs, then keeps learning from teaching and feedback. It never executes anything:
it returns a plan, and the caller decides what to run.
"""
import ast, json, math, os, random, re, time, zlib
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "data" / "brain"
FILE = DIR / "motor.npz"
EXTRA = DIR / "motor_extra.json"
DIM, HID, ACT_MIN = 768, 160, 0.6
SENSITIVE = {"call", "send_sms", "read_sms", "clipboard", "location", "read_screen"}

SEEDS = {
 "battery": ["what is my battery level", "how much battery do i have left", "battery percentage", "is my phone charged", "check the battery", "how charged is the phone", "battery status", "how much charge is left"],
 "time_now": ["what time is it", "tell me the time", "current time", "what is the time now", "do you know what time it is", "time please"],
 "date_today": ["what is today's date", "what day is it", "which day is today", "what is the date", "today's date", "what day of the week is it"],
 "flashlight": ["turn on the flashlight", "flashlight on", "switch off the torch", "turn the flashlight off", "torch on", "toggle the flashlight", "light on", "put the torch off"],
 "volume": ["turn the volume up", "volume down", "set the volume to 50 percent", "mute the phone", "make it louder", "lower the sound", "max volume", "turn it down a bit"],
 "brightness": ["increase the brightness", "dim the screen", "set brightness to 30 percent", "make the screen brighter", "lower the screen brightness", "brightness up"],
 "wifi": ["turn on wifi", "disable wifi", "switch wifi off", "is wifi connected", "wifi on", "enable the wifi"],
 "bluetooth": ["turn on bluetooth", "disable bluetooth", "switch bluetooth off", "is bluetooth on", "bluetooth on", "enable the bluetooth"],
 "open_app": ["open chrome", "launch whatsapp", "start the camera", "open the settings app", "go to youtube", "run spotify", "open maps", "launch the calculator"],
 "call": ["call mom", "phone john", "ring my brother", "make a call to dad", "give sarah a call", "dial 0712345678", "call the office"],
 "send_sms": ["text mom i am late", "send a message to john saying hi", "sms sarah hello there", "message dad that i am home", "send a text to anna saying see you soon"],
 "read_sms": ["read my messages", "do i have any new texts", "check my sms", "read my last text", "any new messages", "what did john text me"],
 "clipboard": ["what is in my clipboard", "read my clipboard", "what did i copy", "show me what i copied", "check the clipboard"],
 "location": ["where am i", "what is my location", "show my current position", "which city am i in", "my gps location", "what area am i in"],
 "screenshot": ["take a screenshot", "capture the screen", "screenshot please", "grab a screenshot", "save a picture of the screen"],
 "read_screen": ["what is on my screen", "read the screen", "describe what you see on my screen", "what is displayed right now", "look at my screen"],
 "weather": ["what is the weather", "will it rain today", "how is the weather outside", "do i need an umbrella", "weather forecast", "is it cold outside"],
 "music": ["pause", "next", "skip", "play some music", "play jazz", "pause the music", "next song", "stop the music", "skip this track", "resume the music", "previous song"],
 "search_web": ["search for pizza places", "google how to tie a tie", "look up the tallest mountain online", "search the web for cheap flights", "search cats"],
 "chat": ["hello", "hi there", "good morning", "how are you", "tell me a joke", "what is the capital of france", "why is the sky blue", "explain how a battery works",
          "who won the world cup", "what is the meaning of life", "write a poem about the sea", "i like jazz", "my favourite colour is green", "what is my dog called",
          "i am tired today", "thanks a lot", "that is wrong", "no it is paris", "what do you think about music", "can you help me with my homework",
          "how does a timer work in a microwave", "what is a clipboard", "tell me about the weather on mars", "why do phones get hot", "i love the new song by that band",
          "what is wifi", "do you like football", "give me advice on studying", "i had a long day", "what is the speed of light", "how do i learn python",
          "tell me a story", "what is your name", "who made you", "remember that the sky is blue", "my friend is called max", "is it better to call or to text a friend",
          "what is a good name for a dog", "summarise the plot of hamlet", "what is two thousand years in the past", "how far is the moon", "what is an alarm clock",
          "how many minutes are in a day", "i need to think", "translate hello to spanish", "what should i cook tonight", "help me write an email to my boss",
          "how do magnets work", "i want to be a doctor", "do you ever get bored", "can you think", "what is the best way to wake up early"],
}
APPS = ["chrome", "whatsapp", "camera", "settings", "youtube", "spotify", "maps", "calculator", "gmail", "telegram", "photos", "clock", "instagram", "files"]
NAMES = ["mom", "dad", "john", "sarah", "anna", "my brother", "the office", "alex", "grandma", "david"]
MSGS = ["i am late", "hello", "see you soon", "on my way", "call me back", "happy birthday", "i will be there at five"]
DURS = ["5 minutes", "10 min", "30 seconds", "an hour", "2 hours", "90 seconds", "half an hour", "1 minute", "45 minutes", "20 mins"]
TIMES = ["7 am", "6:30", "8 pm", "5:45 am", "noon", "9", "10:15 pm", "7:00 am"]
PRE = ["", "", "", "please ", "can you ", "could you ", "hey ", "i want you to ", "i need you to ", "would you "]
SUF = ["", "", "", " please", " now", " for me", " quickly"]

def _templates(rng):
    out = []
    for _ in range(60):
        d, t, a, c, m = (rng.choice(x) for x in (DURS, TIMES, APPS, NAMES, MSGS))
        x, y = rng.randint(2, 999), rng.randint(2, 99)
        op = rng.choice([("plus", "+"), ("minus", "-"), ("times", "x"), ("divided by", "/"), ("multiplied by", "*")])
        out += [("set a timer for " + d, "timer"), (d + " timer", "timer"), ("start a %s timer" % d, "timer"), ("countdown " + d, "timer"), ("remind me in " + d, "timer"),
                ("set an alarm for " + t, "alarm"), ("wake me up at " + t, "alarm"), ("alarm at " + t, "alarm"), ("set alarm " + t, "alarm"),
                (rng.choice(["open ", "launch ", "start ", "run ", "go to "]) + a, "open_app"),
                (rng.choice(["call ", "phone ", "ring ", "dial "]) + c, "call"), ("give %s a call" % c, "call"),
                ("text %s %s" % (c, m), "send_sms"), ("send a message to %s saying %s" % (c, m), "send_sms"), ("message %s that %s" % (c, m), "send_sms"),
                ("what is %d %s %d" % (x, op[0], y), "math"), ("calculate %d %s %d" % (x, op[0], y), "math"), ("%d %s %d" % (x, op[1], y), "math"),
                ("%d percent of %d" % (y, x), "math"), ("square root of %d" % x, "math"), ("how much is %d %s %d" % (x, op[0], y), "math"),
                ("search for " + rng.choice(["pizza", "cheap flights", "cats", "weather in paris", "news"]), "search_web"),
                (rng.choice(["turn on ", "turn off ", "switch on ", "switch off "]) + rng.choice(["the flashlight", "the torch", "wifi", "bluetooth"]), None)]
    fix = {"the flashlight": "flashlight", "the torch": "flashlight", "wifi": "wifi", "bluetooth": "bluetooth"}
    res = []
    for t, l in out:
        if l is None:
            l = fix[[k for k in fix if t.endswith(k)][0]]
        res.append((t, l))
    return res

TOPICS = ["bluetooth", "wifi", "a timer", "an alarm", "volume", "the battery", "gps", "the clipboard", "a screenshot", "a flashlight", "a calculator", "music", "weather", "a phone call", "a text message", "brightness"]
CHAT_T = ["what is %s", "tell me about %s", "explain %s", "how does %s work", "why do people use %s", "i was reading about %s", "i like %s", "what is the history of %s"]
CHAT_X = ["hello there", "hey there", "hi", "hey", "hello saann", "good evening", "i love my new phone", "i love my phone", "my phone is slow today", "thanks that helped", "thank you so much", "great thanks", "that was helpful", "you are smart", "i am happy", "i feel sad",
          "my mom is a doctor", "my brother called me yesterday", "i called my friend last night", "she sent me a message earlier", "the weather was nice yesterday", "i watched a movie", "good night",
          "i hate waiting", "what do you want to do today", "let us talk about space", "i was thinking about my day", "yes", "no", "maybe later", "ok", "cool", "lol", "see you later", "what is your favourite food",
          "tell me something interesting", "do you know any riddles", "what is the biggest animal", "how old is the earth", "i want a new phone", "my battery of tests starts tomorrow", "that song is great"]
def _chat_extra(rng):
    out = [(rng.choice(CHAT_T) % rng.choice(TOPICS), "chat") for _ in range(80)]
    return out + [(x, "chat") for x in CHAT_X]

def _typo(s, rng):
    w = s.split()
    idx = [i for i, x in enumerate(w) if len(x) >= 4 and x.isalpha()]
    if not idx:
        return s
    i = rng.choice(idx); x = w[i]; k = rng.randint(1, len(x) - 2)
    r = rng.random()
    if r < 0.3:
        x = x[:k] + x[k + 1:]
    elif r < 0.6:
        x = x[:k] + x[k + 1] + x[k] + x[k + 2:]
    elif r < 0.8:
        x = x[:k] + x[k] + x[k:]
    else:
        x = re.sub(r"[aeiou]", "", x, count=1)
    w[i] = x
    return " ".join(w)

def _aug(t, rng):
    r = rng.random()
    if r < 0.4:
        return rng.choice(PRE) + t + rng.choice(SUF)
    if r < 0.8:
        return _typo(rng.choice(PRE) + t, rng)
    return t + rng.choice([" saann", " saan", ""])

def build_data(seed=1):
    rng = random.Random(seed)
    base = [(t, l) for l, ts in SEEDS.items() for t in ts] + _templates(rng) + _chat_extra(rng)
    data = list(base)
    for t, l in base:
        for _ in range(2):
            data.append((_aug(t, rng), l))
    return data

LABELS = list(SEEDS) + ["timer", "alarm", "math"]

def feat(text):
    t = re.sub(r"\d+", "#", text.lower())
    t = " " + re.sub(r"\s+", " ", re.sub(r"[^a-z0-9#' ]", " ", t)).strip() + " "
    v = np.zeros(DIM, np.float32)
    for n in (3, 4):
        for i in range(len(t) - n + 1):
            v[zlib.crc32(t[i:i + n].encode()) % DIM] += 1
    ws = t.split()
    for w in ws:
        v[zlib.crc32(b"w" + w.encode()) % DIM] += 2
    for a, b in zip(ws, ws[1:]):
        v[zlib.crc32(("b" + a + " " + b).encode()) % DIM] += 1.5
    v = np.sqrt(v)
    return v / (np.linalg.norm(v) + 1e-9)

class Net:
    def __init__(self, k, seed=3):
        rs = np.random.RandomState(seed)
        self.W1 = (rs.randn(DIM, HID) * np.sqrt(1.0 / DIM)).astype(np.float32); self.b1 = np.zeros(HID, np.float32)
        self.W2 = (rs.randn(HID, k) * np.sqrt(1.0 / HID)).astype(np.float32); self.b2 = np.zeros(k, np.float32)
        self.t = 0
        self._adam()
    def _adam(self):
        self.m = [np.zeros_like(p) for p in self.P()]; self.v = [np.zeros_like(p) for p in self.P()]
    def P(self):
        return [self.W1, self.b1, self.W2, self.b2]
    def grow(self):
        k = self.W2.shape[1]
        self.W2 = np.hstack([self.W2, (np.random.randn(HID, 1) * 0.05).astype(np.float32)])
        self.b2 = np.append(self.b2, np.float32(-1.0))
        self.m[2] = np.hstack([self.m[2], np.zeros((HID, 1), np.float32)]); self.v[2] = np.hstack([self.v[2], np.zeros((HID, 1), np.float32)])
        self.m[3] = np.append(self.m[3], np.float32(0)); self.v[3] = np.append(self.v[3], np.float32(0))
    def probs(self, X):
        h = np.tanh(X @ self.W1 + self.b1)
        z = h @ self.W2 + self.b2
        z = z - z.max(1, keepdims=True); e = np.exp(z)
        return e / e.sum(1, keepdims=True), h
    def step(self, X, Y, lr=0.004):
        p, h = self.probs(X); n = len(X)
        gz = (p - Y) / n
        gh = (gz @ self.W2.T) * (1 - h * h)
        grads = [X.T @ gh, gh.sum(0), h.T @ gz, gz.sum(0)]
        self.t += 1
        c1, c2 = 1 - 0.9 ** self.t, 1 - 0.999 ** self.t
        for i, (q, g) in enumerate(zip(self.P(), grads)):
            self.m[i] = 0.9 * self.m[i] + 0.1 * g
            self.v[i] = 0.999 * self.v[i] + 0.001 * g * g
            q -= lr * (self.m[i] / c1) / (np.sqrt(self.v[i] / c2) + 1e-8)
        return float(-(Y * np.log(p + 1e-9)).sum() / n)

# ---------------- slots
NUMW = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
        "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40, "forty five": 45, "fifty": 50, "sixty": 60}
def _words2num(s):
    for w in sorted(NUMW, key=len, reverse=True):
        s = re.sub(r"\b%s\b" % w, str(NUMW[w]), s)
    return s

def parse_duration(t):
    t = _words2num(t.lower())
    t = re.sub(r"\bhalf an? hour\b", "30 minutes", t)
    t = re.sub(r"\ban? (hour|minute|second)\b", r"1 \1", t)
    tot = 0.0
    for n, u in re.findall(r"(\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)", t):
        tot += float(n) * (3600 if u[0] == "h" else 60 if u.startswith("m") else 1)
    return int(tot) if tot else None

def parse_time(t):
    t = t.lower()
    if re.search(r"\bnoon\b", t):
        return (12, 0)
    if re.search(r"\bmidnight\b", t):
        return (0, 0)
    m = re.search(r"\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?", re.sub(r"\bfor\b|\bat\b", " ", t))
    if not m:
        return None
    h, mi, ap = int(m.group(1)), int(m.group(2) or 0), (m.group(3) or "")[:1]
    if ap == "p" and h < 12:
        h += 12
    if ap == "a" and h == 12:
        h = 0
    return (h, mi) if h < 24 and mi < 60 else None

_OPS = {ast.Add: lambda a, b: a + b, ast.Sub: lambda a, b: a - b, ast.Mult: lambda a, b: a * b, ast.Div: lambda a, b: a / b, ast.Pow: lambda a, b: a ** b, ast.Mod: lambda a, b: a % b}
def _ev(n):
    if isinstance(n, ast.Expression):
        return _ev(n.body)
    if isinstance(n, ast.Constant) and isinstance(n.value, (int, float)):
        return n.value
    if isinstance(n, ast.BinOp) and type(n.op) in _OPS:
        a, b = _ev(n.left), _ev(n.right)
        if isinstance(n.op, ast.Pow) and abs(b) > 100:
            raise ValueError("too big")
        return _OPS[type(n.op)](a, b)
    if isinstance(n, ast.UnaryOp) and isinstance(n.op, (ast.USub, ast.UAdd)):
        return -_ev(n.operand) if isinstance(n.op, ast.USub) else _ev(n.operand)
    raise ValueError("unsafe")

def calc(text):
    t = _words2num(text.lower().replace(",", ""))
    m = re.search(r"square root of (\d+(?:\.\d+)?)", t)
    if m:
        return math.sqrt(float(m.group(1)))
    m = re.search(r"(\d+(?:\.\d+)?)\s*(?:percent|%) of (\d+(?:\.\d+)?)", t)
    if m:
        return float(m.group(1)) / 100 * float(m.group(2))
    for a, b in ((r"multiplied by|times|\bx\b", "*"), (r"divided by|over", "/"), (r"to the power of|squared", "**"), (r"plus", "+"), (r"minus|take away", "-")):
        t = re.sub(a, " %s " % b if b != "**" else " ** ", t)
    t = t.replace("** ", "** 2" if "squared" in text.lower() else "** ")
    m = re.findall(r"[\d.]+(?:\s*[-+*/%]+\s*[\d.]+)+", t)
    if not m:
        return None
    try:
        v = _ev(ast.parse(m[0].strip(), mode="eval"))
        return round(v, 6) if isinstance(v, float) else v
    except Exception:
        return None

def _state(t):
    t = t.lower()
    if re.search(r"\b(off|disable|stop|kill)\b", t):
        return "off"
    if re.search(r"\b(on|enable|start)\b", t):
        return "on"
    return "toggle"

def slots(intent, text):
    t = text.strip()
    low = t.lower()
    if intent == "timer":
        return {"seconds": parse_duration(low)}
    if intent == "alarm":
        hm = parse_time(low)
        return {"hour": hm[0], "minute": hm[1]} if hm else {}
    if intent == "math":
        return {"expression": t, "result": calc(low)}
    if intent in ("flashlight", "wifi", "bluetooth"):
        return {"state": _state(low)}
    if intent in ("volume", "brightness"):
        s = {}
        m = re.search(r"(\d+)", low)
        if m:
            s["level"] = min(100, int(m.group(1)))
        elif re.search(r"\b(mute|silent)\b", low):
            s["level"] = 0
        elif re.search(r"\b(max|maximum|full)\b", low):
            s["level"] = 100
        elif re.search(r"\b(up|louder|higher|increase|brighter|raise)\b", low):
            s["direction"] = "up"
        elif re.search(r"\b(down|lower|quieter|dim|decrease|reduce|softer)\b", low):
            s["direction"] = "down"
        return s
    if intent == "open_app":
        m = re.search(r"\b(?:open|launch|start|run|go to)\s+(?:the\s+)?(.+?)(?:\s+app)?(?:\s+(?:please|now))?$", low)
        return {"app": m.group(1).strip()} if m else {}
    if intent == "call":
        m = re.search(r"\b(?:call|phone|ring|dial|call to)\s+(.+?)(?:\s+(?:please|now))?$", low) or re.search(r"give\s+(.+?)\s+a call", low)
        return {"contact": m.group(1).strip()} if m else {}
    if intent == "send_sms":
        m = re.search(r"(?:text|message|sms)\s+(?:to\s+)?(.+?)\s+(?:saying|that|says)\s+(.+)$", low) or re.search(r"(?:to)\s+(.+?)\s+saying\s+(.+)$", low) or re.search(r"(?:text|message|sms)\s+(\w+)\s+(.+)$", low)
        return {"contact": m.group(1).strip(), "body": m.group(2).strip()} if m else {}
    if intent == "music":
        for k, v in (("pause", "pause"), ("stop", "stop"), ("next", "next"), ("skip", "next"), ("previous", "previous"), ("resume", "resume"), ("play", "play")):
            if re.search(r"\b%s\b" % k, low):
                q = re.sub(r"^(please |can you )?play( some)?\s*", "", low).strip() if v == "play" else ""
                return {"action": v, "query": q if q not in ("music", "") else ""}
        return {}
    if intent == "search_web":
        m = re.search(r"(?:search(?: the web)? for|google|look up|search)\s+(.+?)(?:\s+online)?$", low)
        return {"query": m.group(1)} if m else {}
    return {}

_CLEAN = re.compile(r"^(hey |hi |ok |okay )?(saann|saan|sann)[, ]+|[, ]+(saann|saan|sann)$", re.I)
_SPLIT = re.compile(r"\s*(?:,|;|\band then\b|\bthen\b|\bafter that\b|\band\b)\s+", re.I)

class Motor:
    def __init__(self):
        self.labels = list(LABELS)
        self.net = Net(len(self.labels))
        self.extra = []
        self.last = None
        self._Xb = None; self._Yb = None
        self.cache = {}
        self.loss = None
        try:
            self.extra = json.loads(EXTRA.read_text())["ex"]
        except Exception:
            pass
        if not self._load():
            self._pretrain()

    def _enc(self, pairs):
        X = np.stack([feat(t) for t, _ in pairs])
        Y = np.zeros((len(pairs), len(self.labels)), np.float32)
        for i, (_, l) in enumerate(pairs):
            Y[i, self.labels.index(l)] = 1
        return X, Y

    def _base(self):
        if self._Xb is None:
            self._Xb, _ = self._enc([(t, l) for t, l in build_data() if l in self.labels])
            self._lb = [self.labels.index(l) for _, l in build_data() if l in self.labels]
        return self._Xb, np.eye(len(self.labels), dtype=np.float32)[self._lb]

    def _pretrain(self, epochs=30):
        data = build_data() + [tuple(e) for e in self.extra if e[1] in self.labels]
        X, Y = self._enc(data)
        rs = np.random.RandomState(5)
        for ep in range(epochs):
            idx = rs.permutation(len(X))
            for i in range(0, len(X), 64):
                b = idx[i:i + 64]
                self.loss = self.net.step(X[b], Y[b], lr=0.004 if ep < 20 else 0.0015)
        self.save()

    def classify(self, text):
        p, _ = self.net.probs(feat(text)[None, :])
        p = p[0]; i = int(p.argmax())
        return self.labels[i], float(p[i])

    def _try_split(self, text):
        parts = [p.strip() for p in _SPLIT.split(text) if p and p.strip()]
        if len(parts) < 2:
            return [text]
        res = [self.classify(p) for p in parts]
        if all(l != "chat" and c >= ACT_MIN for l, c in res):
            return parts
        return [text]

    def analyze(self, text):
        t0 = time.perf_counter()
        text = _CLEAN.sub("", text.strip()).strip(" ?!.") or text
        steps = []
        for part in self._try_split(text):
            intent, conf = self.classify(part)
            steps.append({"text": part, "intent": intent, "conf": round(conf, 2), "slots": slots(intent, part) if intent != "chat" else {},
                          "sensitive": intent in SENSITIVE, "act": intent != "chat" and conf >= (0.8 if intent in SENSITIVE else ACT_MIN)})
        out = {"steps": steps, "act": all(s["act"] for s in steps), "ms": round((time.perf_counter() - t0) * 1000, 2)}
        self.last = out
        return out

    def learn(self, text, label, reps=40):
        label = re.sub(r"[^a-z_]", "", label.lower())
        if not label:
            return None
        if label not in self.labels:
            self.labels.append(label)
            self.net.grow()
            self._Xb = None
        rng = random.Random(len(self.extra))
        ex = [(text, label)] + [(_aug(text, rng), label) for _ in range(3)]
        self.extra.append([text, label]); self.extra = self.extra[-400:]
        Xn, Yn = self._enc(ex)
        self.net.t = 0; self.net._adam()
        Xb, Yb = self._base()
        if Yb.shape[1] != len(self.labels):
            Yb = np.pad(Yb, ((0, 0), (0, len(self.labels) - Yb.shape[1])))
        for _ in range(reps):
            ix = np.random.randint(0, len(Xb), 96)
            self.net.step(np.vstack([Xn, Xb[ix]]), np.vstack([Yn, Yb[ix]]), lr=0.008)
        return self.classify(text)

    def reinforce(self, ok):
        """Feedback on the last analysis: ok=True sharpens it, ok=False pushes the intent down."""
        if not self.last or len(self.last["steps"]) != 1:
            return False
        s = self.last["steps"][0]
        x = feat(s["text"])[None, :]
        i = self.labels.index(s["intent"])
        y = np.zeros((1, len(self.labels)), np.float32)
        if ok:
            y[0, i] = 1
        else:
            p, _ = self.net.probs(x); y[0] = p[0]; y[0, i] = 0; y[0, self.labels.index("chat")] += 0.5; y /= y.sum()
        self.net.t = 0; self.net._adam()
        Xb, Yb = self._base()
        if Yb.shape[1] != len(self.labels):
            Yb = np.pad(Yb, ((0, 0), (0, len(self.labels) - Yb.shape[1])))
        for _ in range(6):
            ix = np.random.randint(0, len(Xb), 32)
            self.net.step(np.vstack([x, Xb[ix]]), np.vstack([y, Yb[ix]]), lr=0.001)
        return True

    def stats(self):
        n = sum(p.size for p in self.net.P())
        return {"intents": len(self.labels) - 1, "neurons": HID + len(self.labels), "parameters": n, "taught": len(self.extra), "loss": None if self.loss is None else round(self.loss, 4)}

    def explain(self, r=None):
        r = r or self.last
        if not r:
            return "nothing analysed yet"
        lines = ["%d step(s), %s ms%s" % (len(r["steps"]), r["ms"], "" if r["act"] else " (not confident or plain chat)")]
        for i, s in enumerate(r["steps"], 1):
            lines.append("%d. %s %.2f%s %s" % (i, s["intent"], s["conf"], " [needs confirm]" if s["sensitive"] else "", json.dumps(s["slots"]) if s["slots"] else ""))
        return "\n".join(lines)

    def save(self):
        DIR.mkdir(parents=True, exist_ok=True)
        tmp = DIR / "motor.tmp.npz"
        np.savez_compressed(tmp, W1=self.net.W1, b1=self.net.b1, W2=self.net.W2, b2=self.net.b2, labels=np.array(self.labels), t=np.array([self.net.t]))
        os.replace(tmp, FILE)
        EXTRA.write_text(json.dumps({"ex": self.extra}))

    def _load(self):
        try:
            z = np.load(FILE, allow_pickle=False)
            labs = [str(x) for x in z["labels"]]
            if labs[:len(LABELS)] != LABELS or z["W1"].shape != (DIM, HID):
                return False
            self.labels = labs
            self.net = Net(len(labs))
            self.net.W1[...] = z["W1"]; self.net.b1[...] = z["b1"]; self.net.W2 = z["W2"].copy(); self.net.b2 = z["b2"].copy(); self.net.t = int(z["t"][0])
            self.net._adam()
            return True
        except Exception:
            return False

_M = None
def get():
    global _M
    if _M is None:
        _M = Motor()
    return _M

# eval gate: every taught or corrected change is scored on a held-out set first
try:
    from engine import motor_eval as _gate
    _gate.install()
except Exception:
    pass
