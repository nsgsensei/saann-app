import json, random, re, shutil, sys, time, zlib
from pathlib import Path
import numpy as np
from core.eventlog import log_event, _conn

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "models" / "ann"
MODEL = DIR / "clause_op.npz"
META = DIR / "clause_op.json"
LABELS_FILE = ROOT / "data" / "ann_labels.jsonl"
OPS = ["answer_from_text", "summarize", "extract_facts", "calc",
       "classify", "other"]
DIM, HID, THRESH = 1024, 64, 0.85

def featurize(text):
    toks = re.findall(r"[a-z']+|\d+|[+\-*/()%]", text.lower())
    toks = ["<n>" if t.isdigit() else t for t in toks]
    grams = toks + [a + "_" + b for a, b in zip(toks, toks[1:])]
    if toks:
        grams.append("^" + toks[0])
    v = np.zeros(DIM, dtype=np.float32)
    for g in grams:
        v[zlib.crc32(g.encode()) % DIM] += 1.0
    n = np.linalg.norm(v)
    return v / n if n else v

class MLP:
    def __init__(self, n_in, n_hid, n_out, seed=0):
        r = np.random.RandomState(seed)
        self.W1 = (r.randn(n_in, n_hid) * np.sqrt(2.0 / n_in)).astype(np.float32)
        self.b1 = np.zeros(n_hid, np.float32)
        self.W2 = (r.randn(n_hid, n_out) * np.sqrt(2.0 / n_hid)).astype(np.float32)
        self.b2 = np.zeros(n_out, np.float32)

    def forward(self, X):
        H = np.maximum(0, X @ self.W1 + self.b1)
        Z = H @ self.W2 + self.b2
        Z = Z - Z.max(axis=1, keepdims=True)
        P = np.exp(Z)
        P /= P.sum(axis=1, keepdims=True)
        return H, P

    def fit(self, X, y, epochs=40, lr=0.01, batch=32, seed=1, l2=1e-4):
        r = np.random.RandomState(seed)
        n = len(X)
        Y = np.eye(self.W2.shape[1], dtype=np.float32)[y]
        params = [self.W1, self.b1, self.W2, self.b2]
        m = [np.zeros_like(p) for p in params]
        v = [np.zeros_like(p) for p in params]
        t = 0
        for _ in range(epochs):
            idx = r.permutation(n)
            for i in range(0, n, batch):
                b = idx[i:i + batch]
                xb, yb = X[b], Y[b]
                H, P = self.forward(xb)
                dZ = (P - yb) / len(b)
                dH = dZ @ self.W2.T
                dH[H <= 0] = 0
                grads = [xb.T @ dH + l2 * self.W1, dH.sum(0),
                         H.T @ dZ + l2 * self.W2, dZ.sum(0)]
                t += 1
                for k in range(4):
                    m[k] = 0.9 * m[k] + 0.1 * grads[k]
                    v[k] = 0.999 * v[k] + 0.001 * grads[k] * grads[k]
                    mh = m[k] / (1 - 0.9 ** t)
                    vh = v[k] / (1 - 0.999 ** t)
                    params[k] -= lr * mh / (np.sqrt(vh) + 1e-8)

_cache = {"mtime": None, "net": None}

def _load():
    if not MODEL.exists():
        return None
    mt = MODEL.stat().st_mtime
    if _cache["mtime"] != mt:
        z = np.load(MODEL)
        net = MLP(z["W1"].shape[0], z["W1"].shape[1], z["W2"].shape[1])
        net.W1, net.b1, net.W2, net.b2 = z["W1"], z["b1"], z["W2"], z["b2"]
        _cache.update(mtime=mt, net=net)
    return _cache["net"]

def _probs(net, text):
    _, P = net.forward(featurize(text)[None, :])
    return P[0]

def predict(text):
    net = _load()
    if net is None:
        return None, 0.0
    P = _probs(net, text)
    i = int(P.argmax())
    return OPS[i], float(P[i])

# ---------------- training data ----------------
NOUNS = ["bridge", "company", "treaty", "engine", "river", "museum", "festival",
         "report", "team", "project", "law", "city", "mission", "tower",
         "school", "station", "album", "bank", "garden", "airport"]
PAST = ["opened", "started", "finished", "built", "won", "cost", "carried",
        "used", "created", "released", "signed", "reached", "held", "covered",
        "included"]
OBJ = ["document", "text", "article", "page", "report", "paper", "story"]
SUM = ["summarize the {o}", "summarise the {o}", "give me a summary of the {o}",
       "sum up the {o}", "short summary of the {o} please", "tldr of the {o}",
       "tl;dr", "give me the gist of the {o}", "overview of the {o}",
       "briefly summarize this {o}", "can you summarize the {o}",
       "write a quick summary of the {o}"]
FACT = ["list the key facts from the {o}", "give me the main facts",
        "extract the important facts from the {o}", "key facts please",
        "what are the key facts in the {o}", "pull out the facts from the {o}",
        "give me three facts about the {o}", "list the facts"]
CALC = ["calculate {a}{op}{b}", "compute {a} {op} {b}", "what is {a}{op}{b}",
        "what's {a} {op} {b}", "evaluate {a}{op}{b}"]
LABSETS = [["news", "history", "fiction"], ["positive", "negative", "neutral"],
           ["spam", "not spam"], ["technical", "legal", "casual"],
           ["urgent", "normal"]]
CLS = ["classify the {o} as {l}", "categorize this {o} as {l}",
       "label the {o} as {l}", "please classify the {o} as {l}"]
OTHER = ["hello", "hi there", "thanks", "thank you", "good morning",
         "good night", "how are you", "tell me a joke", "who are you",
         "open the camera", "open the browser", "play some music",
         "play my playlist", "turn on the flashlight", "turn off the wifi",
         "set an alarm for 7 am", "set a timer for ten minutes",
         "send an email to my boss", "send a message to my friend",
         "call my mum", "delete my files", "delete the photos",
         "remind me to buy milk", "take a picture", "what do you think of me",
         "sing me a song", "restart the phone", "increase the volume",
         "post this on twitter", "order me a pizza", "book a taxi",
         "turn up the brightness", "lock the screen", "open settings",
         "text John that I am late"]
GOLD = [("When did the bridge open?", "answer_from_text"),
        ("How many litres of paint were used?", "answer_from_text"),
        ("Who designed the bridge?", "answer_from_text"),
        ("What do ferries do?", "answer_from_text"),
        ("Summarize the document", "summarize"),
        ("Give me the tl;dr", "summarize"),
        ("Can you give me an overview of the page", "summarize"),
        ("List the key facts", "extract_facts"),
        ("Pull out the main facts from the text", "extract_facts"),
        ("Calculate 12*(3+4)", "calc"),
        ("What is 45 + 17", "calc"),
        ("Classify the document as news, history or fiction", "classify"),
        ("Hello there", "other"),
        ("Set an alarm for 7am", "other"),
        ("Send an email to my boss", "other"),
        ("Play some music", "other")]

def _seed():
    r = random.Random(7)
    out = []
    for _ in range(320):
        n, p = r.choice(NOUNS), r.choice(PAST)
        t = r.randint(0, 9)
        q = ["When did the %s %s?" % (n, r.choice(["open", "start", "finish", "end"])),
             "Who %s the %s?" % (p, n),
             "How many %ss did the %s have?" % (r.choice(NOUNS), n),
             "What is the %s of the %s?" % (r.choice(["size", "name", "cost", "length", "purpose", "date", "height"]), n),
             "Where is the %s located?" % n,
             "Why was the %s %s?" % (n, r.choice(["closed", "built", "famous", "delayed"])),
             "How much did the %s cost?" % n,
             "Which %s was %s first?" % (n, p),
             "Is the %s %s?" % (n, r.choice(["open", "old", "finished", "public", "free", "large"])),
             "How long does the %s take?" % n][t]
        pre = r.choice(["", "", "", "Please tell me ", "Can you tell me ", "I want to know ", "Also, "])
        out.append((pre + (q[0].lower() + q[1:] if pre else q), "answer_from_text"))
    for tpl in SUM:
        for o in OBJ:
            out.append((tpl.format(o=o), "summarize"))
    for tpl in FACT:
        for o in OBJ:
            out.append((tpl.format(o=o), "extract_facts"))
    for _ in range(80):
        out.append((r.choice(CALC).format(a=r.randint(2, 99), b=r.randint(2, 99),
                                          op=r.choice(["+", "-", "*", "/"])), "calc"))
    for tpl in CLS:
        for ls in LABSETS:
            labs = ", ".join(ls[:-1]) + " or " + ls[-1] if len(ls) > 2 else " or ".join(ls)
            out.append((tpl.format(o=r.choice(OBJ), l=labs), "classify"))
    for t in OTHER:
        out.append((t, "other"))
        out.append((t.capitalize(), "other"))
    return out

def _harvest():
    # Only explicit questions from earlier verified plans are reused. Whole-request
    # labels are skipped on purpose: early planner versions logged wrong plans.
    out = []
    try:
        with _conn() as c:
            rows = c.execute("SELECT output FROM events WHERE action='plan' "
                             "AND outcome='verified'").fetchall()
    except Exception:
        return out
    for (o,) in rows:
        try:
            steps = json.loads(o)
        except Exception:
            continue
        if isinstance(steps, list):
            for s in steps:
                if isinstance(s, dict) and s.get("op") == "answer_from_text" and s.get("arg"):
                    out.append((s["arg"], "answer_from_text"))
    return out

def _manual():
    out = []
    if LABELS_FILE.exists():
        for line in LABELS_FILE.read_text().splitlines():
            try:
                d = json.loads(line)
                if d["op"] in OPS:
                    out.append((d["text"], d["op"]))
            except Exception:
                pass
    return out

def _acc(net, pairs):
    if not pairs:
        return 0.0
    X = np.stack([featurize(t) for t, _ in pairs])
    _, P = net.forward(X)
    return float(np.mean(P.argmax(1) == np.array([OPS.index(l) for _, l in pairs])))

def train(epochs=40):
    base = list({(t.lower(), l): (t, l) for t, l in _seed() + _harvest()}.values())
    r = random.Random(3)
    r.shuffle(base)
    test = base[::5]
    tr = [d for i, d in enumerate(base) if i % 5 != 0]
    manual = _manual()
    tr += manual * 5
    counts = {}
    for _, l in tr:
        counts[l] = counts.get(l, 0) + 1
    print("training examples:", len(tr), counts, "| your labels:", len(manual))
    X = np.stack([featurize(t) for t, _ in tr])
    y = np.array([OPS.index(l) for _, l in tr])
    net = MLP(DIM, HID, len(OPS))
    t0 = time.time()
    net.fit(X, y, epochs=epochs)
    te, ga = _acc(net, test), _acc(net, GOLD)
    print("trained in %ds | held-out accuracy %.1f%% | gold accuracy %.1f%%" %
          (time.time() - t0, te * 100, ga * 100))
    for t, l in GOLD:
        p = _probs(net, t)
        if OPS[int(p.argmax())] != l:
            print("  gold miss:", t, "->", OPS[int(p.argmax())], "(want %s)" % l)
    prev = json.loads(META.read_text()) if META.exists() else None
    need = prev["gold_acc"] if prev else 0.6
    if te >= 0.90 and ga >= need - 1e-9:
        DIR.mkdir(parents=True, exist_ok=True)
        if MODEL.exists():
            shutil.copy(MODEL, DIR / "clause_op.prev.npz")
        np.savez(MODEL, W1=net.W1, b1=net.b1, W2=net.W2, b2=net.b2)
        META.write_text(json.dumps({"held_out_acc": te, "gold_acc": ga,
                                    "examples": len(tr), "trained": time.time()}))
        print("ACCEPTED: new model saved (old one kept as .prev)")
    else:
        print("REJECTED: needs held-out >= 90%% and gold >= %.0f%%; keeping current model" % (need * 100))

# ---------------- used by the planner ----------------
def _labels(c):
    m = re.search(r"\b(?:as|into|between)\s+(.+)$", c, re.I)
    if not m:
        return None
    parts = re.split(r",\s*(?:or\s+|and\s+)?|\s+or\s+|\s+and\s+", m.group(1).strip(" ?.!"))
    parts = [p.strip() for p in parts if p.strip()]
    return parts if len(parts) >= 2 else None

def step_from_clause(c):
    label, p = predict(c)
    if label is None:
        return None
    log_event("ann", "clause.pred", input=c[:200], output=label, confidence=p)
    if p < THRESH or label == "other":
        return None
    if label in ("summarize", "extract_facts"):
        return {"op": label, "arg": ""}
    if label == "calc":
        m = re.search(r"\d[\d+\-*/(). %]*\d", c)
        return {"op": "calc", "arg": m.group(0).strip()} if m else None
    if label == "classify":
        labs = _labels(c)
        return {"op": "classify", "arg": ",".join(labs)} if labs else None
    from engine.planner import _strip_leads
    q = _strip_leads(c)
    if len(q) < 8:
        return None
    q = q[0].upper() + q[1:]
    return {"op": "answer_from_text", "arg": q if q.endswith("?") else q + "?"}

def main():
    a = sys.argv[1:]
    if not a:
        print('usage: train | test "clause" | label "clause" op | review'); return
    if a[0] == "train":
        train()
    elif a[0] == "test":
        net = _load()
        if net is None:
            print("no model yet; run train"); return
        P = _probs(net, a[1])
        for i in np.argsort(-P)[:3]:
            print("%-18s %.2f" % (OPS[i], P[i]))
    elif a[0] == "label":
        if a[2] not in OPS:
            print("op must be one of", OPS); return
        LABELS_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(LABELS_FILE, "a") as f:
            f.write(json.dumps({"text": a[1], "op": a[2]}) + "\n")
        print("saved. run: python -m engine.ann train")
    elif a[0] == "review":
        with _conn() as c:
            rows = c.execute("SELECT input, output, confidence FROM events WHERE "
                             "action='clause.pred' ORDER BY ts DESC LIMIT 30").fetchall()
        for t, o, p in rows:
            print("%-12s %.2f | %s" % (o, p or 0, t))
        if not rows:
            print("no predictions logged yet")

if __name__ == "__main__":
    main()
