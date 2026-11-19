"""SAANN cortex: learned neural networks that sit under the brain's senses.

Per sense: a deep autoencoder learns that sense's own features online (replay + Adam).
Across senses: an association network learns to predict one sense from the others
(sound -> picture, word -> colour). Language also gets a sequence net.
Reconstruction error is the 'surprise' signal: high when input is unlike anything learned.
Size is set with  /brain size small|medium|large  (changing it starts the cortex fresh).
"""
import json, os, random, time
from collections import deque
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "data" / "brain"
FILE = DIR / "cortex.npz"
SIZES = {"small": (128, 24), "medium": (384, 48), "large": (1024, 96)}
LR = {"small": 0.003, "medium": 0.002, "large": 0.001}

class MLP:
    def __init__(self, sizes, acts, seed=0, lr=0.003):
        rs = np.random.RandomState(seed)
        self.sizes, self.acts, self.lr, self.t = list(sizes), list(acts), lr, 0
        self.W = [(rs.randn(a, b) * np.sqrt(1.0 / a)).astype(np.float32) for a, b in zip(sizes, sizes[1:])]
        self.b = [np.zeros(b, np.float32) for b in sizes[1:]]
        self.m = [np.zeros_like(p) for p in self.W + self.b]
        self.v = [np.zeros_like(p) for p in self.W + self.b]

    def params(self):
        return sum(w.size for w in self.W) + sum(b.size for b in self.b)

    def forward(self, x, start=0, stop=None, keep=False):
        stop = len(self.W) if stop is None else stop
        hs = [np.asarray(x, np.float32)]
        for i in range(start, stop):
            z = hs[-1] @ self.W[i] + self.b[i]
            hs.append(np.tanh(z) if self.acts[i] == "tanh" else z)
        return hs if keep else hs[-1]

    def train(self, x, y, mask=None):
        L = len(self.W)
        hs = self.forward(x, keep=True)
        err = hs[-1] - y
        if mask is not None:
            err = err * mask
        n = max(1, len(x))
        loss = float((err ** 2).sum() / n)
        g = 2 * err / n
        grads = [None] * (2 * L)
        for i in reversed(range(L)):
            if self.acts[i] == "tanh":
                g = g * (1 - hs[i + 1] ** 2)
            grads[i] = hs[i].T @ g
            grads[L + i] = g.sum(0)
            g = g @ self.W[i].T
        self.t += 1
        ps = self.W + self.b
        c1, c2 = 1 - 0.9 ** self.t, 1 - 0.999 ** self.t
        for k, p in enumerate(ps):
            gr = np.clip(grads[k], -5, 5)
            self.m[k] = 0.9 * self.m[k] + 0.1 * gr
            self.v[k] = 0.999 * self.v[k] + 0.001 * gr * gr
            p -= self.lr * (self.m[k] / c1) / (np.sqrt(self.v[k] / c2) + 1e-8)
        return loss

    def state(self, pre):
        d = {}
        for i, (w, b) in enumerate(zip(self.W, self.b)):
            d["%s_W%d" % (pre, i)], d["%s_b%d" % (pre, i)] = w, b
        d[pre + "_t"] = np.array([self.t])
        return d

    def restore(self, z, pre):
        try:
            for i in range(len(self.W)):
                w, b = z["%s_W%d" % (pre, i)], z["%s_b%d" % (pre, i)]
                if w.shape != self.W[i].shape:
                    return False
            for i in range(len(self.W)):
                self.W[i][...] = z["%s_W%d" % (pre, i)]
                self.b[i][...] = z["%s_b%d" % (pre, i)]
            self.t = int(z[pre + "_t"][0])
            return True
        except Exception:
            return False

class Cortex:
    def __init__(self, size="medium"):
        self.size = size if size in SIZES else "medium"
        self.hid, self.code = SIZES[self.size]
        self.ae, self.buf, self.ema, self.seen, self.cs = {}, {}, {}, {}, {}
        self.assoc, self.order, self.seq = None, [], None
        self.hist = deque(maxlen=3)
        self.loss = {}
        self.pending = {}
        self._load()

    # ---- structure
    def _ae(self, name, dim):
        if name not in self.ae:
            h = self.hid
            c = self.cs[name] = min(self.code, max(4, dim // 3))
            self.ae[name] = MLP([dim, h, c, h, dim], ["tanh", "lin", "tanh", "lin"], seed=sum(map(ord, name)) % 9973, lr=LR[self.size])
            self.buf[name] = deque(maxlen=256)
            self.ema[name] = None
            self.seen[name] = 0
            r = self.pending.pop(name, None)
            if r is not None:
                self.ae[name].restore(r, "ae_" + name)
                self.seen[name] = int(r["seen_" + name][0]) if "seen_" + name in r else 0
                if "ema_" + name in r:
                    self.ema[name] = float(r["ema_" + name][0])
        return self.ae[name]

    def _assoc(self):
        names = sorted(self.ae)
        if self.assoc is None or names != self.order:
            self.order = names
            R, tot = len(names), sum(self.cs[n] for n in names)
            self.assoc = MLP([tot + R, self.hid, tot], ["tanh", "lin"], seed=7, lr=LR[self.size])
            z = self.pending.get("__assoc__")
            if z is not None and list(z.get("order", [])) == names:
                self.assoc.restore(z, "assoc")
        return self.assoc

    # ---- learning
    def see(self, enc):
        """enc: {region: vector}. Trains the networks one step. Returns surprise (about 1 = normal)."""
        out = {}
        codes = {}
        for name, q in enc.items():
            q = np.asarray(q, np.float32)
            ae = self._ae(name, len(q))
            x = q[None, :]
            rec = ae.forward(x)
            err = float(((rec - x) ** 2).sum())
            e = self.ema[name]
            out[name] = 1.0 if (e is None or self.seen[name] < 20) else err / (e + 1e-6)
            self.ema[name] = err if e is None else 0.97 * e + 0.03 * err
            self.buf[name].append(q)
            k = min(len(self.buf[name]), 8)
            batch = np.stack(random.sample(list(self.buf[name]), k - 1) + [q]) if k > 1 else x
            self.loss[name] = ae.train(batch, batch)
            self.seen[name] += 1
            codes[name] = ae.forward(x, 0, 2)[0]
        self._train_assoc(codes)
        self._train_seq(enc, codes)
        return out

    def _offs(self):
        o, a = {}, 0
        for n in self.order:
            o[n] = a
            a += self.cs[n]
        return o, a

    def _pack(self, codes, hide=()):
        o, tot = self._offs()
        x = np.zeros(tot + len(self.order), np.float32)
        for i, n in enumerate(self.order):
            if n in codes and n not in hide:
                x[o[n]:o[n] + self.cs[n]] = codes[n]
                x[tot + i] = 1.0
        return x

    def _train_assoc(self, codes):
        if not codes:
            return
        a = self._assoc()
        o, tot = self._offs()
        present = [n for n in self.order if n in codes]
        hide = set()
        if len(present) > 1:
            hide = set(random.sample(present, random.randint(1, len(present) - 1)))
        y = np.zeros(tot, np.float32)
        m = np.zeros(tot, np.float32)
        for n in self.order:
            if n in codes:
                y[o[n]:o[n] + self.cs[n]] = codes[n]
                m[o[n]:o[n] + self.cs[n]] = 1.0
        self.loss["assoc"] = a.train(self._pack(codes, hide)[None, :], y[None, :], m[None, :])

    def _train_seq(self, enc, codes):
        if "language" not in codes:
            return
        c = self.cs["language"]
        if self.seq is None:
            self.seq = MLP([3 * c, self.hid, c], ["tanh", "lin"], seed=11, lr=LR[self.size])
            z = self.pending.get("__seq__")
            if z is not None:
                self.seq.restore(z, "seq")
        if len(self.hist) == 3:
            x = np.concatenate(list(self.hist))[None, :]
            self.loss["seq"] = self.seq.train(x, codes["language"][None, :])
        self.hist.append(codes["language"])

    # ---- using what it learned
    def imagine(self, enc):
        """Predict the senses that are NOT present, as region vectors."""
        if not self.ae or self.assoc is None or not enc:
            return {}
        codes = {}
        for name, q in enc.items():
            if name in self.ae:
                codes[name] = self.ae[name].forward(np.asarray(q, np.float32)[None, :], 0, 2)[0]
        if not codes:
            return {}
        y = self._assoc().forward(self._pack(codes)[None, :])[0]
        o, _ = self._offs()
        out = {}
        for n in self.order:
            if n not in enc and self.seen.get(n, 0) >= 30:
                out[n] = self.ae[n].forward(np.tanh(y[None, o[n]:o[n] + self.cs[n]]), 2)[0]
        return out

    def stats(self):
        p = sum(m.params() for m in self.ae.values())
        p += self.assoc.params() if self.assoc else 0
        p += self.seq.params() if self.seq else 0
        return {"size": self.size, "networks": len(self.ae) + (1 if self.assoc else 0) + (1 if self.seq else 0),
                "parameters": p, "loss": {k: round(v, 4) for k, v in self.loss.items()},
                "seen": dict(self.seen)}

    # ---- saving
    def save(self):
        DIR.mkdir(parents=True, exist_ok=True)
        d = {"size": np.array([self.size])}
        for n, m in self.ae.items():
            d.update(m.state("ae_" + n))
            d["seen_" + n] = np.array([self.seen[n]])
            d["ema_" + n] = np.array([self.ema[n] or 0.0])
        if self.assoc:
            d.update(self.assoc.state("assoc"))
            d["assoc_order"] = np.array(self.order)
        if self.seq:
            d.update(self.seq.state("seq"))
        tmp = DIR / "cortex.tmp.npz"
        np.savez_compressed(tmp, **d)
        os.replace(tmp, FILE)

    def _load(self):
        try:
            z = np.load(FILE, allow_pickle=False)
            if str(z["size"][0]) != self.size:
                return
            names = {k[3:-2] for k in z.files if k.startswith("ae_") and k.endswith("_t")}
            self.pending = {n: z for n in names}
            if "assoc_order" in z.files:
                self.pending["__assoc__"] = _View(z, order=list(z["assoc_order"]))
            if "seq_t" in z.files:
                self.pending["__seq__"] = z
        except Exception:
            pass

class _View(dict):
    def __init__(self, z, order):
        super().__init__(order=order)
        self.z = z
    def __getitem__(self, k):
        return super().__getitem__(k) if k == "order" else self.z[k]
    def get(self, k, d=None):
        return super().get(k, d) if k == "order" else d if k not in self.z.files else self.z[k]
