"""SAANN brain: a brain-inspired learning core that runs BEFORE the language models.

Sense regions turn raw input into compact codes. Concepts are learned online (no training
runs), bound across senses, linked by co-occurrence (Hebbian), and ordered by a sequence
memory. A reflex layer answers from learned experience before any LLM is asked.

  left hemisphere : language, sequence memory (what usually comes next)
  right hemisphere: visual, colour, pattern memory (what does this remind me of)
  both            : sound, touch, smell (any external environment sensor)
  hubs            : thalamus (attention), hippocampus (episodes), amygdala (valence),
                    cerebellum (reflexes), prefrontal (deliberate / LLM)
Add your own regions: drop a .py file in config/brain_regions/ that defines REGIONS = [..].
"""
import importlib.util, json, math, os, re, sys, threading, time, zlib
from collections import Counter, deque
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "data" / "brain"
PLUGINS = ROOT / "config" / "brain_regions"
HUBS = ("thalamus", "hippocampus", "amygdala", "cerebellum", "prefrontal")

def _unit(v):
    v = np.asarray(v, np.float32)
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-9 else np.zeros_like(v)

def _pool(a, n):
    a = np.asarray(a, np.float32)
    rs = np.array_split(np.arange(a.shape[0]), n)
    cs = np.array_split(np.arange(a.shape[1]), n)
    return np.array([[a[np.ix_(r, c)].mean() for c in cs] for r in rs], np.float32)

# ---------------------------------------------------------------- regions
class Region:
    """Base sense. encode(raw) returns a vector of length dim, or None if there is nothing."""
    name, hemi, dim, weight, kind = "region", "M", 16, 1.0, "cosine"
    def encode(self, raw):
        raise NotImplementedError
    def sim(self, mat, q):
        if self.kind == "rbf":
            return np.exp(-((mat - q) ** 2).sum(1) / (2 * 0.35 ** 2 * mat.shape[1]))
        return mat @ q

_STOP = set("a an the is are am was of to in on at it its i you me my your do does did and or".split())

class Language(Region):
    name, hemi, dim = "language", "L", 256
    def encode(self, raw):
        t = str(raw).lower()
        t = re.sub(r"n't\b", " not", t)
        t = re.sub(r"'s\b", " is", t)
        t = re.sub(r"'m\b", " am", t)
        t = re.sub(r"'re\b", " are", t)
        toks = re.findall(r"[a-z0-9]+", t)
        if not toks:
            return None
        v = np.zeros(self.dim, np.float32)
        for tok in toks:
            w = 0.4 if tok in _STOP else 1.0
            h = zlib.crc32(tok.encode())
            v[h % self.dim] += w * (1 if zlib.crc32((tok + "#").encode()) & 1 else -1)
            p = "^" + tok + "$"
            for i in range(len(p) - 2):
                g = zlib.crc32(p[i:i + 3].encode())
                v[g % self.dim] += 0.3 * w * (1 if zlib.crc32((p[i:i + 3] + "#").encode()) & 1 else -1)
        return _unit(v)

class Visual(Region):
    name, hemi, dim = "visual", "R", 129
    def encode(self, raw):
        a = np.asarray(raw, np.float32)
        if a.ndim == 3:
            a = a.mean(2)
        if a.ndim != 2 or min(a.shape) < 4:
            return None
        if a.max() > 1.5:
            a = a / 255.0
        gy, gx = np.gradient(a)
        g = _pool(a, 8).ravel()
        m = _pool(np.hypot(gx, gy), 8).ravel()
        v = np.concatenate([_unit(g - g.mean()), _unit(m - m.mean()), [0.5 * a.mean()]])
        return _unit(v)

class Color(Region):
    name, hemi, dim = "color", "R", 17
    def encode(self, raw):
        a = np.asarray(raw, np.float32)
        if a.ndim != 3 or a.shape[2] < 3:
            return None
        px = a[..., :3].reshape(-1, 3)
        if px.max() > 1.5:
            px = px / 255.0
        if len(px) > 4096:
            px = px[:: len(px) // 4096]
        r, g, b = px[:, 0], px[:, 1], px[:, 2]
        mx, mn = px.max(1), px.min(1)
        d = mx - mn
        s = d / (mx + 1e-9)
        h = np.zeros(len(px), np.float32)
        nz = d > 1e-6
        rc = np.where(nz & (mx == r), ((g - b) / np.maximum(d, 1e-9)) % 6, 0)
        gc = np.where(nz & (mx == g) & (mx != r), (b - r) / np.maximum(d, 1e-9) + 2, 0)
        bc = np.where(nz & (mx == b) & (mx != r) & (mx != g), (r - g) / np.maximum(d, 1e-9) + 4, 0)
        h = ((rc + gc + bc) / 6.0) % 1.0
        wgt = s * mx + 1e-3
        hist = np.bincount(np.minimum((h * 12).astype(int), 11), weights=wgt, minlength=12)
        hist = hist / hist.sum()
        v = np.concatenate([np.sqrt(hist), 0.5 * np.array([s.mean(), mx.mean(), r.mean(), g.mean(), b.mean()])])
        return _unit(v)

class Audio(Region):
    name, hemi, dim = "audio", "M", 32
    def encode(self, raw):
        sr = 16000
        if isinstance(raw, dict):
            sr, raw = int(raw.get("sr", 16000)), raw["samples"]
        x = np.asarray(raw, np.float32)
        if x.size < 1024:
            return None
        x = x - x.mean()
        if float(np.sqrt((x ** 2).mean())) < 1e-4:
            return None
        n, hop = 512, 256
        idx = np.arange(n)[None, :] + hop * np.arange((len(x) - n) // hop + 1)[:, None]
        spec = np.abs(np.fft.rfft(x[idx] * np.hanning(n), axis=1)) ** 2
        f = np.fft.rfftfreq(n, 1.0 / sr)
        mel = lambda hz: 2595 * np.log10(1 + hz / 700.0)
        inv = lambda m: 700 * (10 ** (m / 2595.0) - 1)
        edges = inv(np.linspace(mel(60), mel(min(7600, sr / 2 * 0.95)), 25))
        bands = np.stack([spec[:, (f >= edges[i]) & (f < edges[i + 1])].sum(1) for i in range(24)], 1)
        pb = bands.sum(0) + 1e-9
        pb /= pb.sum()
        e = bands.sum(1)
        pt = np.array([c.mean() for c in np.array_split(e, 8)]) + 1e-9
        pt /= pt.sum()
        return _unit(np.concatenate([np.sqrt(pb), 0.6 * np.sqrt(pt)]))

class Touch(Region):
    name, hemi, dim, kind = "touch", "M", 10, "rbf"
    def encode(self, raw):
        if not isinstance(raw, dict):
            return None
        W, H = float(raw.get("w", 1080)), float(raw.get("h", 2400))
        taps, sw = raw.get("taps") or [], raw.get("swipes") or []
        acc = raw.get("accel") or (0, 0, 0)
        v = np.zeros(10, np.float32)
        if taps:
            t = np.asarray(taps, np.float32)
            v[0], v[1], v[2] = min(len(t), 10) / 10, t[:, 0].mean() / W, t[:, 1].mean() / H
        if sw:
            s = np.asarray(sw, np.float32)
            dx, dy = (s[:, 2] - s[:, 0]) / W, (s[:, 3] - s[:, 1]) / H
            v[3], v[4], v[5] = min(len(s), 10) / 10, dx.mean() * 0.5 + 0.5, dy.mean() * 0.5 + 0.5
            v[6] = np.hypot(dx, dy).mean()
        v[7:10] = np.clip(np.asarray(acc, np.float32) / 20 + 0.5, 0, 1)
        return v if v.any() else None

class Smell(Region):
    """Phones have no smell sensor. This accepts any external chemical or environment readings
    (0 to 1 each) from a connected sensor, and stays idle until some arrive."""
    name, hemi, dim, kind = "smell", "M", 8, "rbf"
    def encode(self, raw):
        v = np.zeros(8, np.float32)
        a = np.asarray(raw, np.float32).ravel()[:8]
        v[:len(a)] = np.clip(a, 0, 1)
        return v if len(a) else None

# ---------------------------------------------------------------- the brain
class Brain:
    def __init__(self, load=True):
        self.lock = threading.RLock()
        self.cfg = {"match": 0.80, "reflex": 0.88, "cap": 3000, "autosave": 25}
        self.regions = {}
        self.cap, self.n, self.next_id = 256, 0, 1
        self.mat, self.has, self.meta, self.index = {}, {}, [], {}
        self.links, self.seq = {}, {}
        self.episodes = deque(maxlen=500)
        self.recent = deque(maxlen=4)
        self.reflex = {}
        self.act = {h: 0.0 for h in HUBS}
        self.events = deque(maxlen=30)
        self.last = None
        self.novelty = 0.0
        self.updates, self.t_decay, self.t_consol = 0, time.time(), time.time()
        for R in (Language, Visual, Color, Audio, Touch, Smell):
            self.add_region(R())
        self.load_plugins()
        if load:
            self.load()

    # ---- structure
    def add_region(self, r):
        with self.lock:
            self.regions[r.name] = r
            self.mat[r.name] = np.zeros((self.cap, r.dim), np.float32)
            self.has[r.name] = np.zeros(self.cap, bool)
            self.act.setdefault(r.name, 0.0)

    def load_plugins(self):
        if not PLUGINS.exists():
            return
        for f in sorted(PLUGINS.glob("*.py")):
            try:
                spec = importlib.util.spec_from_file_location("brain_region_" + f.stem, f)
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                for r in (getattr(mod, "REGIONS", None) or [getattr(mod, "REGION")]):
                    self.add_region(r)
            except Exception as e:
                self.events.append({"t": time.time(), "note": "plugin %s failed: %s" % (f.name, e)})

    def _grow(self):
        self.cap *= 2
        for k, r in self.regions.items():
            m = np.zeros((self.cap, r.dim), np.float32)
            m[: len(self.mat[k])] = self.mat[k]
            self.mat[k] = m
            h = np.zeros(self.cap, bool)
            h[: len(self.has[k])] = self.has[k]
            self.has[k] = h

    # ---- matching
    def _match(self, enc):
        n = self.n
        if n == 0:
            return -1, 0.0
        num, den = np.zeros(n, np.float32), np.zeros(n, np.float32)
        for name, q in enc.items():
            has = self.has[name][:n]
            if not has.any():
                continue
            w = self.regions[name].weight * has
            num += w * self.regions[name].sim(self.mat[name][:n], q)
            den += w
        if not (den > 0).any():
            return -1, 0.0
        sims = np.where(den > 0, num / np.maximum(den, 1e-9), -1.0)
        i = int(np.argmax(sims))
        return i, float(sims[i])

    def _create(self, enc, label):
        if self.n >= self.cfg["cap"]:
            self._prune(force=True)
        if self.n >= self.cap:
            self._grow()
        i = self.n
        for name, q in enc.items():
            self.mat[name][i] = q
            self.has[name][i] = True
        for name in self.regions:
            if name not in enc:
                self.has[name][i] = False
        cid = self.next_id
        self.next_id += 1
        self.meta.append({"id": cid, "n": 1, "label": label, "val": 0.0, "seen": time.time(), "born": time.time()})
        self.index[cid] = i
        self.n += 1
        return cid

    def _reinforce(self, i, enc, label):
        m = self.meta[i]
        lr = max(0.08, 1.0 / (m["n"] + 1)) * (1 + abs(m["val"]))
        for name, q in enc.items():
            if self.has[name][i]:
                p = (1 - lr) * self.mat[name][i] + lr * q
                self.mat[name][i] = _unit(p) if self.regions[name].kind == "cosine" else p
            else:
                self.mat[name][i] = q
                self.has[name][i] = True
        m["n"] += 1
        m["seen"] = time.time()
        if label and not m["label"]:
            m["label"] = label
        return m["id"]

    def _decay(self):
        now = time.time()
        f = 0.5 ** ((now - self.t_decay) / 1.5)
        self.t_decay = now
        for k in self.act:
            self.act[k] *= f

    def _name(self, cid):
        i = self.index.get(cid)
        if i is None:
            return "?"
        return self.meta[i]["label"] or "#%d" % cid

    # ---- perceiving
    def think(self, raw, learn=True, label=None):
        """raw = {region: data}. Returns what the brain made of it, in a few milliseconds."""
        with self.lock:
            t0 = time.perf_counter()
            self._decay()
            enc = {}
            for name, data in raw.items():
                r = self.regions.get(name)
                if r is None:
                    continue
                try:
                    v = r.encode(data)
                except Exception:
                    v = None
                if v is not None:
                    enc[name] = np.asarray(v, np.float32)
            if not enc:
                return {"ok": False, "reason": "nothing to perceive"}
            i, sim = self._match(enc)
            known = i >= 0 and sim >= self.cfg["match"]
            self.novelty = 1.0 if i < 0 else float(1 - sim)
            recalled = []
            if known:
                recalled = [k for k in self.regions if self.has[k][i] and k not in enc]
            if learn:
                cid = self._reinforce(i, enc, label) if known else self._create(enc, label)
            else:
                cid = self.meta[i]["id"] if known else None
            for name in enc:
                self.act[name] = min(1.0, self.act[name] + 0.35 + 0.5 * self.novelty)
            self.act["thalamus"] = min(1.0, self.act["thalamus"] + 0.5)
            out = {"ok": True, "concept": cid, "label": self._name(cid) if cid else None,
                   "new": not known, "novelty": round(self.novelty, 3), "recalled": recalled,
                   "senses": list(enc)}
            if cid is not None and learn:
                self._bind(cid)
                self.episodes.append((time.time(), cid))
                self.act["hippocampus"] = min(1.0, self.act["hippocampus"] + 0.3)
                self.events.append({"t": time.time(), "senses": list(enc), "c": cid,
                                    "label": self._name(cid), "new": not known})
                self.updates += 1
                if self.updates % self.cfg["autosave"] == 0:
                    self.save()
            if cid is not None:
                out["remind"] = self._remind(cid)
                out["next"] = self._predict(cid)
                if recalled:
                    out["note"] = "this %s reminds me of something with %s" % ("+".join(enc), "+".join(recalled))
            out["ms"] = round((time.perf_counter() - t0) * 1000, 2)
            return out

    def _bind(self, cid):
        """Hebbian links with recent concepts, plus sequence memory."""
        rec = list(self.recent)
        if rec and rec[-1] == cid:
            return
        for o in rec:
            if o != cid:
                k = (min(o, cid), max(o, cid))
                w = self.links.get(k, 0.0)
                self.links[k] = w + 0.15 * (1 - w)
        if rec:
            p1 = rec[-1]
            p2 = rec[-2] if len(rec) > 1 else None
            for key in ((p2, p1), (None, p1)):
                self.seq.setdefault(key, Counter())[cid] += 1
        self.recent.append(cid)

    def _remind(self, cid, k=3):
        rel = [(w, a if b == cid else b) for (a, b), w in self.links.items() if cid in (a, b)]
        return [{"label": self._name(o), "w": round(w, 2)} for w, o in sorted(rel, reverse=True)[:k]]

    def _predict(self, cid, k=3):
        rec = list(self.recent)
        c = None
        if len(rec) >= 2 and rec[-1] == cid:
            c = self.seq.get((rec[-2], cid))
        c = c or self.seq.get((None, cid))
        if not c:
            return []
        tot = sum(c.values())
        return [{"label": self._name(o), "p": round(v / tot, 2)} for o, v in c.most_common(k)]

    # ---- senses shortcuts
    def think_text(self, text):
        return self.think({"language": text})

    # ---- learning from experience (reflex layer)
    def _lang_cid(self, text):
        enc = {"language": self.regions["language"].encode(text)}
        if enc["language"] is None:
            return None
        i, sim = self._match(enc)
        if i >= 0 and sim >= self.cfg["match"]:
            return self.meta[i]["id"], sim
        return self._create(enc, None), 1.0

    def first(self, text):
        """Answer from learned experience before any model is asked. None if not sure."""
        with self.lock:
            enc = self.regions["language"].encode(text)
            if enc is None or self.n == 0:
                return None
            i, sim = self._match({"language": enc})
            if i < 0 or sim < self.cfg["reflex"]:
                return None
            cid = self.meta[i]["id"]
            ok = [r for r in self.reflex.get(cid, []) if r["good"] >= 1 and r["good"] >= 2 * r["bad"]]
            if not ok:
                return None
            r = max(ok, key=lambda r: (r["good"] - 2 * r["bad"], r["good"]))
            self.last = {"cid": cid, "resp": r["t"], "reflex": True}
            self.act["cerebellum"] = 1.0
            self.events.append({"t": time.time(), "senses": ["language"], "c": cid,
                                "label": "reflex", "new": False})
            return r["t"]

    def learn(self, text, response, verdict="unknown"):
        """Remember an answer. Only 'good' answers can ever become reflexes."""
        with self.lock:
            got = self._lang_cid(text)
            if got is None or not response:
                return
            cid = got[0] if isinstance(got, tuple) else got
            lst = self.reflex.setdefault(cid, [])
            r = next((x for x in lst if x["t"] == response[:400]), None)
            if r is None:
                r = {"t": response[:400], "good": 0, "bad": 0}
                lst.append(r)
                del lst[:-5]
            if verdict == "good":
                r["good"] += 1
            elif verdict == "bad":
                r["bad"] += 1
            self.last = {"cid": cid, "resp": r["t"], "reflex": False}
            self.act["prefrontal"] = min(1.0, self.act["prefrontal"] + 0.7)

    def feedback(self, good):
        with self.lock:
            if not self.last:
                return "nothing to rate yet"
            lst = self.reflex.setdefault(self.last["cid"], [])
            r = next((x for x in lst if x["t"] == self.last["resp"]), None)
            if r is None:
                r = {"t": self.last["resp"], "good": 0, "bad": 0}
                lst.append(r)
            r["good" if good else "bad"] += 1
            self.reward(0.5 if good else -0.5)
            return "learned: good answer" if good else "learned: bad answer"

    def reward(self, v):
        with self.lock:
            for cid in list(self.recent)[-3:]:
                i = self.index.get(cid)
                if i is not None:
                    m = self.meta[i]
                    m["val"] = max(-1.0, min(1.0, m["val"] * 0.9 + v))
            self.act["amygdala"] = min(1.0, self.act["amygdala"] + abs(v))

    def label_last(self, label):
        with self.lock:
            if not self.recent:
                return "nothing perceived yet"
            i = self.index.get(self.recent[-1])
            self.meta[i]["label"] = label[:40]
            return "the last thing I perceived is now called '%s'" % label[:40]

    # ---- sleep: replay, forget, save
    def consolidate(self):
        with self.lock:
            eps = [c for _, c in list(self.episodes)[-300:]]
            for a, b, c in zip(eps, eps[1:], eps[2:]):
                if a != b and b != c:
                    self.seq.setdefault((a, b), Counter())[c] += 1
            for k in list(self.links):
                self.links[k] *= 0.97
                if self.links[k] < 0.02:
                    del self.links[k]
            gone = self._prune()
            self.t_consol = time.time()
            self.save()
            return {"replayed": len(eps), "forgot": gone, "concepts": self.n}

    def _prune(self, force=False):
        keep = []
        now = time.time()
        for i, m in enumerate(self.meta):
            weak = m["n"] <= 1 and now - m["born"] > 7 * 86400 and abs(m["val"]) < 0.2 \
                and not m["label"] and m["id"] not in self.reflex
            if not weak:
                keep.append(i)
        if force and len(keep) >= self.cfg["cap"]:
            keep = sorted(keep, key=lambda i: self.meta[i]["n"] + 5 * abs(self.meta[i]["val"]))[-int(self.cfg["cap"] * 0.9):]
            keep.sort()
        gone = self.n - len(keep)
        if gone <= 0:
            return 0
        for name in self.regions:
            self.mat[name][: len(keep)] = self.mat[name][keep]
            self.has[name][: len(keep)] = self.has[name][keep]
            self.has[name][len(keep): self.n] = False
        self.meta = [self.meta[i] for i in keep]
        self.n = len(keep)
        self.index = {m["id"]: i for i, m in enumerate(self.meta)}
        alive = set(self.index)
        self.links = {k: w for k, w in self.links.items() if k[0] in alive and k[1] in alive}
        return gone

    def tick(self):
        if time.time() - self.t_consol > 600:
            return self.consolidate()

    def save(self):
        with self.lock:
            DIR.mkdir(parents=True, exist_ok=True)
            arrays = {}
            for k in self.regions:
                arrays["m_" + k] = self.mat[k][: self.n]
                arrays["h_" + k] = self.has[k][: self.n]
            tmp = DIR / "state.tmp.npz"
            np.savez_compressed(tmp, **arrays)
            os.replace(tmp, DIR / "state.npz")
            doc = {"meta": self.meta, "next_id": self.next_id, "cfg": self.cfg,
                   "links": {"%d,%d" % k: w for k, w in self.links.items()},
                   "seq": {"%s,%d" % ("_" if k[0] is None else k[0], k[1]): dict(v) for k, v in self.seq.items()},
                   "reflex": {str(k): v for k, v in self.reflex.items()},
                   "dims": {k: r.dim for k, r in self.regions.items()}}
            (DIR / "state.json.tmp").write_text(json.dumps(doc))
            os.replace(DIR / "state.json.tmp", DIR / "state.json")

    def load(self):
        try:
            doc = json.loads((DIR / "state.json").read_text())
            z = np.load(DIR / "state.npz")
            n = len(doc["meta"])
            while self.cap < n + 16:
                self._grow()
            for k, r in self.regions.items():
                if "m_" + k in z and doc["dims"].get(k) == r.dim:
                    self.mat[k][:n] = z["m_" + k]
                    self.has[k][:n] = z["h_" + k]
            self.meta, self.n, self.next_id = doc["meta"], n, doc["next_id"]
            self.cfg.update(doc.get("cfg", {}))
            self.index = {m["id"]: i for i, m in enumerate(self.meta)}
            self.links = {tuple(int(x) for x in k.split(",")): w for k, w in doc["links"].items()}
            self.seq = {(None if k.split(",")[0] == "_" else int(k.split(",")[0]), int(k.split(",")[1])): Counter({int(a): b for a, b in v.items()})
                        for k, v in doc["seq"].items()}
            self.reflex = {int(k): v for k, v in doc["reflex"].items()}
        except Exception:
            pass

    # ---- what the monitor shows
    def snapshot(self):
        with self.lock:
            self._decay()
            hemi = {"L": [], "R": []}
            nodes = []
            for k, r in self.regions.items():
                nodes.append({"name": k, "hemi": r.hemi, "act": round(self.act.get(k, 0.0), 3)})
                if r.hemi in hemi:
                    hemi[r.hemi].append(self.act.get(k, 0.0))
            for h in HUBS:
                nodes.append({"name": h, "hemi": "H", "act": round(self.act[h], 3)})
            top = sorted(self.links.items(), key=lambda kv: -kv[1])[:10]
            return {"nodes": nodes, "novelty": round(self.novelty, 3), "concepts": self.n,
                    "left": round(max(hemi["L"] or [0]), 3), "right": round(max(hemi["R"] or [0]), 3),
                    "links": [{"a": self._name(a), "b": self._name(b), "w": round(w, 2)} for (a, b), w in top],
                    "events": [{"senses": e["senses"], "label": e["label"], "new": e["new"]} for e in list(self.events)[-6:]],
                    "reflexes": sum(1 for v in self.reflex.values() if any(x["good"] >= 1 for x in v))}

    def status(self):
        s = self.snapshot()
        return ("brain: %d concepts, %d links, %d reflexes, novelty %.2f\nactive: %s" % (
            s["concepts"], len(self.links), s["reflexes"], s["novelty"],
            ", ".join("%s %.1f" % (n["name"], n["act"]) for n in s["nodes"] if n["act"] > 0.05) or "quiet"))

# ---------------------------------------------------------------- shared instance and safe hooks
_B = None
_L = threading.Lock()

def get():
    global _B
    with _L:
        if _B is None:
            _B = Brain()
    return _B

def _safe(fn):
    def w(*a, **k):
        try:
            return fn(*a, **k)
        except Exception as e:
            sys.stderr.write("brain: %s\n" % e)
            return None
    return w

think_text = _safe(lambda text: get().think_text(text))
first = _safe(lambda text: get().first(text))
learn = _safe(lambda text, resp, verdict="unknown": get().learn(text, resp, verdict))
tick = _safe(lambda: get().tick())
snapshot_json = _safe(lambda: json.dumps(get().snapshot()))

def command(msg):
    """Chat commands. Returns text, or None if msg is not a brain command."""
    m = msg.strip()
    low = m.lower()
    b = get()
    if low in ("/good", "/bad"):
        return b.feedback(low == "/good")
    if low.startswith("/name "):
        return b.label_last(m[6:].strip())
    if low == "/brain":
        return b.status()
    if low == "/sleep":
        return str(b.consolidate())
    if low == "/brain demo":
        return demo()
    return None

# ---------------------------------------------------------------- demo and CLI
def _tone(f, secs=0.5, sr=16000):
    t = np.arange(int(sr * secs)) / sr
    return np.sin(2 * np.pi * f * t)

def _img(kind):
    a = np.zeros((32, 32, 3), np.float32)
    if kind == "red":
        a[...] = (200, 20, 20)
    elif kind == "blue":
        a[...] = (20, 20, 200)
    elif kind == "door":
        a[...] = (60, 60, 60)
        a[4:30, 10:22] = (160, 100, 40)
    return a

def demo():
    b = Brain(load=False)
    out = []
    bell = np.concatenate([_tone(660, 0.25), _tone(880, 0.25)])
    for _ in range(3):
        b.think({"audio": bell, "visual": _img("door")[..., 0], "color": _img("door")}, label="doorbell")
    r = b.think({"audio": bell}, learn=False)
    out.append("heard the bell alone -> %s; %s" % (r["label"], r.get("note", "")))
    r = b.think({"audio": np.random.RandomState(1).randn(8000) * 0.3}, learn=False)
    out.append("a hiss I never heard -> new=%s novelty=%.2f" % (r["new"], r["novelty"]))
    b.learn("what is your name", "I am SAANN", "good")
    b.learn("what is your name", "I am SAANN", "good")
    out.append("asked 'what's your name?' -> %s" % b.first("what's your name?"))
    return "\n".join(out)

def monitor(seconds=12):
    b = get()
    t_end = time.time() + seconds
    while time.time() < t_end:
        s = b.snapshot()
        sys.stdout.write("\033[2J\033[H")
        print("SAANN brain   concepts %d   novelty %.2f   (Ctrl+C to quit)\n" % (s["concepts"], s["novelty"]))
        for n in s["nodes"]:
            bar = "#" * int(n["act"] * 30)
            print("%-12s %s %-30s %.2f" % (n["name"], n["hemi"], bar, n["act"]))
        time.sleep(0.2)

if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["demo"]:
        print(demo())
    elif a[:1] == ["monitor"]:
        monitor(int(a[1]) if len(a) > 1 else 12)
    elif a[:1] == ["think"] and len(a) > 1:
        print(get().think_text(" ".join(a[1:])))
    elif a[:1] == ["sleep"]:
        print(get().consolidate())
    else:
        print(get().status())
