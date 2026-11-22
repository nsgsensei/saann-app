"""Eval gate for the motor cortex. Every taught or corrected change is scored on a held-out set first;
if it makes things worse, it is undone in memory and never saved. Last good files are kept as *.prev.
  python engine/motor_eval.py            report
  python engine/motor_eval.py rollback   put the .prev files back
"""
import copy
import json
import random
import re
import shutil
import time

from engine import motor as M

HELD = M.DIR / "motor_heldout.json"
LOG = M.ROOT / "data" / "motor_eval.jsonl"
TOL = 0.02
CONF = 0.8

HAND = [
    ("battery", "how much charge do I have left"), ("battery", "is my phone almost dead"),
    ("time_now", "got the time"), ("time_now", "what's the hour"),
    ("date_today", "which date are we on"), ("date_today", "what is today's date"),
    ("flashlight", "light up the torch"), ("flashlight", "turn off the flashlight"),
    ("volume", "make it louder"), ("volume", "set volume to 40"),
    ("brightness", "dim the screen"), ("brightness", "brightness to maximum"),
    ("wifi", "am I connected to wifi"), ("wifi", "which network am I on"),
    ("bluetooth", "is bluetooth on"), ("bluetooth", "switch bluetooth off"),
    ("open_app", "launch whatsapp"), ("open_app", "start the camera app"),
    ("call", "ring mom"), ("call", "phone my brother"),
    ("send_sms", "text john I am late"), ("send_sms", "message anna that I'm on my way"),
    ("read_sms", "read my latest texts"), ("read_sms", "any new messages"),
    ("clipboard", "what did I copy"), ("clipboard", "show the clipboard"),
    ("location", "where am I"), ("location", "what's my location"),
    ("screenshot", "capture the screen"), ("screenshot", "take a screenshot"),
    ("read_screen", "what's on my screen"), ("read_screen", "read what is displayed"),
    ("weather", "is it going to rain"), ("weather", "how's the weather"),
    ("music", "play some music"), ("music", "next song"),
    ("search_web", "google best pizza near me"), ("search_web", "look up the capital of Peru"),
    ("timer", "set a timer for 10 minutes"), ("timer", "countdown 5 minutes"),
    ("alarm", "wake me at 6:30"), ("alarm", "set an alarm for 7 am"),
    ("math", "what is 12 times 8"), ("math", "calculate 45 plus 17"),
    ("chat", "tell me a joke"), ("chat", "who wrote Hamlet"), ("chat", "I had a long day"),
    ("chat", "what is bluetooth"), ("chat", "explain photosynthesis"), ("chat", "my mom called me yesterday"),
]

KW = [
    ("read_sms", r"\b(read|check|any new|latest|new|unread)\b.*\b(texts?|sms|messages?)\b"),
    ("send_sms", r"\b(text|sms|message)\b"),
    ("call", r"\b(call|ring|phone|dial)\b"),
    ("timer", r"\btimers?\b|\bcountdown\b"),
    ("alarm", r"\balarm\b|\bwake me\b"),
    ("screenshot", r"screenshot|capture the screen|screen ?cap"),
    ("read_screen", r"\b(on|read) (my|the) screen|what('?s| is) displayed"),
    ("clipboard", r"clipboard|what did i copy|\bpaste\b"),
    ("location", r"where am i|my location|\bgps\b"),
    ("flashlight", r"flashlight|torch"),
    ("bluetooth", r"bluetooth"),
    ("wifi", r"wi-?fi|network"),
    ("battery", r"battery|charge|power left"),
    ("volume", r"volume|louder|quieter|\bmute\b"),
    ("brightness", r"brightness|\bdim\b|brighter"),
    ("weather", r"weather|\brain\b|forecast|temperature"),
    ("music", r"\b(play|song|music|next track|pause)\b"),
    ("search_web", r"\b(google|search|look up)\b"),
    ("open_app", r"\b(open|launch|start)\b"),
    ("math", r"\d+\s*(\+|-|\*|x|times|plus|minus|divided)\s*\d+|\bcalculate\b"),
    ("time_now", r"\btime\b|what'?s the hour"),
    ("date_today", r"\bdate\b|which day|what day"),
]
KWC = [(l, re.compile(p, re.I)) for l, p in KW]


def kw_predict(text):
    for label, rx in KWC:
        if rx.search(text):
            return label
    return "chat"


def heldout():
    try:
        d = json.loads(HELD.read_text())
        if d.get("items"):
            return [tuple(x) for x in d["items"]]
    except Exception:
        pass
    seen = {t.lower() for t, _ in M.build_data(1)}
    try:
        seen |= {str(e[0]).lower() for e in json.loads(M.EXTRA.read_text())["ex"]}
    except Exception:
        pass
    fresh, used = [], set()
    for t, l in M.build_data(99):
        k = t.lower()
        if k not in seen and k not in used:
            used.add(k)
            fresh.append((t, l, "gen"))
    random.Random(7).shuffle(fresh)
    items = [(t, l, "gen") for t, l, _ in fresh[:150]] + [(t, l, "hand") for l, t in HAND]
    M.DIR.mkdir(parents=True, exist_ok=True)
    HELD.write_text(json.dumps({"made": time.strftime("%Y-%m-%d %H:%M"), "items": items}))
    return items


def evaluate(m, items):
    items = [it for it in items if it[1] in m.labels]
    ok = hand_ok = hand_n = gen_ok = gen_n = fp = 0
    for t, l, src in items:
        pl, pc = m.classify(t)
        hit = pl == l
        ok += hit
        if src == "hand":
            hand_n += 1
            hand_ok += hit
        else:
            gen_n += 1
            gen_ok += hit
        if l == "chat" and pl in M.SENSITIVE and pc >= CONF:
            fp += 1
    n = max(1, len(items))
    return {"n": len(items), "acc": ok / n, "hand": hand_ok / max(1, hand_n), "gen": gen_ok / max(1, gen_n), "fp": fp}


def baseline(items):
    hit = sum(1 for t, l, _ in items if kw_predict(t) == l)
    return hit / max(1, len(items))


def verdict(before, after, base):
    if after["acc"] < before["acc"] - TOL:
        return False, "accuracy fell from %.0f%% to %.0f%%" % (before["acc"] * 100, after["acc"] * 100)
    if after["acc"] < base <= before["acc"]:
        return False, "it would fall below the keyword baseline (%.0f%%)" % (base * 100)
    if after["fp"] > before["fp"] + 1:
        return False, "it started mistaking chat for a sensitive action"
    return True, "ok"


def _log(row):
    try:
        LOG.parent.mkdir(parents=True, exist_ok=True)
        with open(LOG, "a") as f:
            f.write(json.dumps(row) + "\n")
    except Exception:
        pass


def _keep_prev():
    for p in (M.FILE, M.EXTRA):
        try:
            if p.exists():
                shutil.copyfile(p, str(p) + ".prev")
        except Exception:
            pass


def _gated(orig, name):
    def w(self, *a, **k):
        if getattr(self, "_in_gate", False):
            return orig(self, *a, **k)
        try:
            items = heldout()
            snap = (copy.deepcopy(self.net), list(self.labels), list(self.extra), self.last)
            before = evaluate(self, items)
            base = baseline([i for i in items if i[1] in self.labels])
        except Exception:
            return orig(self, *a, **k)
        self._in_gate = True
        try:
            res = orig(self, *a, **k)
        finally:
            self._in_gate = False
        try:
            after = evaluate(self, items)
            ok, why = verdict(before, after, base)
        except Exception:
            return res
        _log({"t": time.strftime("%Y-%m-%d %H:%M:%S"), "op": name, "ok": ok, "why": why, "before": round(before["acc"], 3),
              "after": round(after["acc"], 3), "base": round(base, 3), "fp": [before["fp"], after["fp"]]})
        if ok:
            _keep_prev()
            self.gate_msg = None
            return res
        self.net, self.labels, self.extra, self.last = snap
        self._Xb = None
        self.gate_msg = "Change rejected by the eval gate: " + why
        return None if name == "learn" else False
    w._gated = True
    return w


def install():
    for name in ("learn", "reinforce"):
        f = getattr(M.Motor, name)
        if not getattr(f, "_gated", False):
            setattr(M.Motor, name, _gated(f, name))


def report(m=None):
    m = m or M.get()
    items = heldout()
    r = evaluate(m, items)
    base = baseline([i for i in items if i[1] in m.labels])
    sens = [i for i in items if i[1] in M.SENSITIVE and i[1] in m.labels]
    s_ok = sum(1 for t, l, _ in sens if m.classify(t)[0] == l)
    return ("held-out questions: %d (%d hand-written)\n"
            "motor cortex:   %.0f%% overall, %.0f%% on hand-written, %.0f%% on generated\n"
            "keyword baseline: %.0f%%\n"
            "sensitive intents found: %d/%d, chat mistaken for sensitive: %d\n"
            "Hand-written phrasings are the honest number; generated ones resemble the training data."
            % (r["n"], sum(1 for i in items if i[2] == "hand"), r["acc"] * 100, r["hand"] * 100, r["gen"] * 100,
               base * 100, s_ok, len(sens), r["fp"]))


def rollback():
    import os
    n = 0
    for p in (M.FILE, M.EXTRA):
        q = str(p) + ".prev"
        try:
            if os.path.exists(q):
                shutil.copyfile(q, p)
                n += 1
        except Exception:
            pass
    return "restored %d file(s) from .prev; restart SAANN to load them" % n


if __name__ == "__main__":
    import sys
    sys.path.insert(0, str(M.ROOT))
    print(rollback() if sys.argv[1:] == ["rollback"] else report())
