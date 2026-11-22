"""Streaming, images and PDFs for SAANN. Builds on engine/cloud.py (keys, quotas, fallback).

  Cloudflare provider      text, image understanding (backup) and image generation
  Image understanding      Gemini first, Cloudflare as backup; only when you attach a photo or ask me to look
  PDFs                     read: offline text first, cloud (Gemini) for scans; create: offline, pure Python
  Streaming                words appear as they are produced (local models, Groq, Gemini)

Commands:  /image PROMPT   /see [question]   /pdf read [question]   /pdf make TITLE | text
           /key cloudflare paste   (copy the Account ID, paste; copy the API token, paste)   /setup cloudflare
Natural:   "draw a picture of ...", "generate an image of ...", attach a photo or PDF and ask about it.
Nothing here is used unless online help is on (/cloud on) and a provider is connected, except offline PDF reading and creation.
"""
import base64
import json
import re
import threading
import time
import urllib.error
import urllib.request
import zlib
from pathlib import Path

from engine import cloud

ROOT = cloud.ROOT
UPLOADS = ROOT / "data" / "uploads"     # shown in the app's Library tab
CF_ACCT = "cloudflare_account"
CF_TEXT = "@cf/meta/llama-3.1-8b-instruct"
CF_IMAGE = "@cf/black-forest-labs/flux-1-schnell"
CF_SEE = "@cf/llava-hf/llava-1.5-7b-hf"
CF_SEE_MAX = 400_000                    # bytes: the backup vision model takes the image as a number list

# ---------------------------------------------------------------- cloudflare provider
cloud.PROVIDERS["cloudflare"] = {
    "name": "Cloudflare", "gives": "image generation and backup vision (free daily quota)",
    "url": "https://api.cloudflare.com/client/v4/accounts/{acct}/ai/run/{model}", "model": CF_TEXT,
    "site": "https://dash.cloudflare.com/?to=/:account/ai/workers-ai", "prefix": "", "daily": 300,
    "steps": ["Open dash.cloudflare.com and sign up free (say \"/setup cloudflare open\").",
              "In the left menu choose AI, then Workers AI. Tap Use REST API.",
              "Copy the Account ID shown there, then say: /key cloudflare paste",
              "Tap Create a Workers AI API Token, then Create API Token, then copy the token.",
              "Say /key cloudflare paste again. I can tell the two apart. (Menus change; if you are lost, say what you see.)"],
}
cloud.PROVIDERS["gemini"]["gives"] = "stronger answers, reads photos and PDFs"
cloud.PROVIDERS["groq"]["gives"] = "fast replies (Llama), streamed as it writes"
if "cloudflare" not in cloud.ORDER:
    cloud.ORDER.append("cloudflare")


def _cf_acct():
    return cloud.keys().get(CF_ACCT, "")


def _cf_run(model, payload, timeout=90):
    k = cloud.keys()
    token, acct = k.get("cloudflare"), k.get(CF_ACCT)
    if not token or not acct:
        raise RuntimeError("cloudflare needs both an account id and a token")
    url = cloud.PROVIDERS["cloudflare"]["url"].format(acct=acct, model=model)
    d = cloud._post(url, payload, {"Content-Type": "application/json", "Authorization": "Bearer " + token,
                                   "User-Agent": "saann/1.0"}, timeout)
    if d.get("success") is False:
        raise RuntimeError(str(d.get("errors"))[:200])
    return d.get("result", d)


_orig_call = getattr(cloud._call, "_orig", cloud._call)


def _call(name, key, prompt, max_tokens, schema, temperature, system):
    cb = _stream_cb()
    if name == "cloudflare":
        if schema:
            prompt += "\n\nReply with ONLY valid JSON matching this schema:\n" + json.dumps(schema)
        r = _cf_run(cloud.model_of("cloudflare"), {"messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
                                                     "max_tokens": max_tokens, "temperature": temperature})
        return str(r.get("response", "")).strip(), "cloudflare:" + cloud.model_of("cloudflare")
    if cb and not schema and name in ("groq", "gemini"):
        return _stream_cloud(name, key, prompt, max_tokens, temperature, system, cb)
    return _orig_call(name, key, prompt, max_tokens, schema, temperature, system)


_call._orig = _orig_call
cloud._call = _call


# ---------------------------------------------------------------- streaming
_tl = threading.local()


def _stream_cb():
    return getattr(_tl, "cb", None)


def _send(text):
    try:
        import android_bridge
        android_bridge.B.call("stream", text, "")
    except Exception:
        pass


def stream_on(cb=None):
    """Called around each chat reply. cb(text_so_far); the default pushes it to the app's bubble."""
    _tl.cb = cb or _send
    _tl.last = 0.0


def stream_off():
    _tl.cb = None


def emit(acc, final=False):
    cb = _stream_cb()
    if not cb or not acc:
        return
    now = time.time()
    if final or now - getattr(_tl, "last", 0) >= 0.08:
        _tl.last = now
        try:
            cb(acc)
        except Exception:
            pass


def sse(req, timeout=300):
    """Yields the parsed JSON of each 'data:' line."""
    with urllib.request.urlopen(req, timeout=timeout) as r:
        for raw in r:
            line = raw.decode("utf-8", "ignore").strip()
            if not line.startswith("data:"):
                continue
            body = line[5:].strip()
            if body == "[DONE]":
                return
            try:
                yield json.loads(body)
            except Exception:
                continue


def local_stream(url, body):
    """Used by the gateway for local llama-server replies."""
    body = dict(body, stream=True)
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    acc = ""
    for ev in sse(req):
        try:
            acc += ev["choices"][0]["delta"].get("content") or ""
        except Exception:
            continue
        emit(acc)
    emit(acc, True)
    return acc.strip()


def _stream_cloud(name, key, prompt, max_tokens, temperature, system, cb):
    p = cloud.PROVIDERS[name]
    model = cloud.model_of(name)
    acc = ""
    if name == "groq":
        body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
                "max_tokens": max_tokens, "temperature": temperature, "stream": True}
        req = urllib.request.Request(p["url"], json.dumps(body).encode(),
                                     {"Content-Type": "application/json", "Authorization": "Bearer " + key, "User-Agent": "saann/1.0"})
        for ev in sse(req, 60):
            try:
                acc += ev["choices"][0]["delta"].get("content") or ""
            except Exception:
                continue
            emit(acc)
    else:
        body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": [{"role": "user", "parts": [{"text": prompt}]}],
                "generationConfig": {"maxOutputTokens": max_tokens, "temperature": temperature}}
        url = p["url"].format(model=model).replace(":generateContent", ":streamGenerateContent") + "?alt=sse"
        req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json", "x-goog-api-key": key})
        for ev in sse(req, 60):
            try:
                acc += ev["candidates"][0]["content"]["parts"][0].get("text", "")
            except Exception:
                continue
            emit(acc)
    emit(acc, True)
    if not acc.strip():
        raise RuntimeError("empty stream")
    return acc.strip(), name + ":" + model


# ---------------------------------------------------------------- consent
def online_ok():
    """Cloud is used only when the owner switched online help on."""
    return cloud._mode() != "local" and bool(cloud.connected())


def _b64(path):
    return base64.b64encode(Path(path).read_bytes()).decode()


def _mime(path):
    e = Path(path).suffix.lower().lstrip(".")
    return {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp", "gif": "image/gif",
            "bmp": "image/bmp", "pdf": "application/pdf"}.get(e, "application/octet-stream")


# ---------------------------------------------------------------- understanding images and PDFs
def _gemini_file(path, question):
    k = cloud.keys().get("gemini")
    if not k:
        raise RuntimeError("no gemini key")
    model = cloud.model_of("gemini")
    body = {"contents": [{"role": "user", "parts": [{"inline_data": {"mime_type": _mime(path), "data": _b64(path)}}, {"text": question}]}],
            "generationConfig": {"maxOutputTokens": 700, "temperature": 0.3}}
    url = cloud.PROVIDERS["gemini"]["url"].format(model=model)
    d = cloud._post(url, body, {"Content-Type": "application/json", "x-goog-api-key": k}, 120)
    s = cloud.state(); s["used"]["gemini"] = s["used"].get("gemini", 0) + 1; cloud._save_state(s)
    return d["candidates"][0]["content"]["parts"][0]["text"].strip()


def _cf_see(path, question):
    data = Path(path).read_bytes()
    if len(data) > CF_SEE_MAX:
        raise RuntimeError("image too large for the backup model")
    r = _cf_run(CF_SEE, {"image": list(data), "prompt": question, "max_tokens": 400})
    return str(r.get("description") or r.get("response") or "").strip()


def see(path, question=None):
    """Describe or answer about an image. Returns (text, None) or (None, reason)."""
    question = question or "Describe this image clearly and briefly. Read out any text you can see."
    if not Path(path).exists():
        return None, "I can't find that image."
    errs = []
    keys = cloud.keys()
    if keys.get("gemini"):
        try:
            return _gemini_file(path, question), None
        except Exception as e:
            errs.append("Gemini: %s" % _why(e))
    if keys.get("cloudflare") and keys.get(CF_ACCT):
        try:
            t = _cf_see(path, question)
            if t:
                return t, None
        except Exception as e:
            errs.append("Cloudflare: %s" % _why(e))
    return None, ("; ".join(errs) if errs else "no provider that can see images is connected (/setup)")


def _why(e):
    if isinstance(e, urllib.error.HTTPError):
        return {401: "key rejected", 403: "key rejected", 404: "model not found", 413: "image too large", 429: "limit reached"}.get(e.code, "error %d" % e.code)
    return str(e)[:80] or "failed"


# ---- PDF text, offline
def pdf_text(path, limit=12000):
    """Text from a PDF without any cloud. Empty string for scans or unusual encodings."""
    for mod in ("pypdf", "PyPDF2"):
        try:
            m = __import__(mod)
            r = m.PdfReader(str(path))
            t = "\n".join((p.extract_text() or "") for p in r.pages)
            if t.strip():
                return t[:limit]
        except Exception:
            continue
    try:
        raw = Path(path).read_bytes()
    except Exception:
        return ""
    out = []
    for m in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", raw, re.S):
        s = m.group(1)
        try:
            s = zlib.decompress(s)
        except Exception:
            pass
        if b"BT" not in s:
            continue
        for t in re.finditer(rb"\[(.*?)\]\s*TJ|\((.*?)(?<!\\)\)\s*Tj|T\*|ET", s, re.S):
            g = t.group(0)
            if g in (b"T*", b"ET"):
                out.append("\n")
            elif t.group(1) is not None:
                out.append("".join(_unesc(x) for x in re.findall(rb"\((.*?)(?<!\\)\)", t.group(1), re.S)))
            elif t.group(2) is not None:
                out.append(_unesc(t.group(2)))
        out.append("\n")
    text = re.sub(r"\n{3,}", "\n\n", "".join(out)).strip()
    return text[:limit] if len(re.sub(r"\W", "", text)) > 30 else ""


def _unesc(b):
    s = b.replace(b"\\(", b"(").replace(b"\\)", b")").replace(b"\\\\", b"\\")
    return s.decode("latin-1", "replace")


def read_pdf(path, question=None):
    """('answer', text) from the cloud, ('rewrite', message) for the local brain, or ('answer', reason)."""
    if not Path(path).exists():
        return ("answer", "I can't find that PDF.")
    q = question or "Summarize this document in a few short paragraphs."
    text = pdf_text(path)
    if text and not online_ok():
        return ("rewrite", "%s\n\n[document text]\n%s" % (q, text[:3500]))
    if online_ok() and cloud.keys().get("gemini"):
        try:
            return ("answer", _gemini_file(path, q))
        except Exception as e:
            if text:
                return ("rewrite", "%s\n\n[document text]\n%s" % (q, text[:3500]))
            return ("answer", "I couldn't read that PDF online (%s)." % _why(e))
    if text:
        return ("rewrite", "%s\n\n[document text]\n%s" % (q, text[:3500]))
    return ("answer", "This PDF has no readable text (it may be a scan). Turn on online help (/setup, then /cloud on) and I can read it with Google Gemini.")


# ---- PDF creation, offline
def _wrap(text, width):
    lines = []
    for para in str(text).replace("\r", "").split("\n"):
        words, cur = para.split(" "), ""
        for w in words:
            while len(w) > width:
                if cur:
                    lines.append(cur); cur = ""
                lines.append(w[:width]); w = w[width:]
            if len(cur) + len(w) + (1 if cur else 0) <= width:
                cur = (cur + " " + w) if cur else w
            else:
                lines.append(cur); cur = w
        lines.append(cur)
    return lines


def _pdfstr(s):
    return s.encode("latin-1", "replace").decode("latin-1").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(title, body, path):
    """Simple A4 text PDF, built-in fonts, no libraries. Returns the path."""
    W, H, M = 595, 842, 56
    size, lead = 11, 15
    per_page = int((H - 2 * M) / lead)
    lines = [("T", t) for t in _wrap(title, 52)] + [("B", "")] if title else []
    lines += [("B", t) for t in _wrap(body, 88)]
    pages = [lines[i:i + per_page] for i in range(0, max(1, len(lines)), per_page)] or [[]]
    objs = []

    def add(b):
        objs.append(b if isinstance(b, bytes) else b.encode("latin-1"))
        return len(objs)
    add("<< /Type /Catalog /Pages 2 0 R >>")
    add(b"")                                    # pages, filled below
    f1 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    f2 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    kids = []
    for pg in pages:
        ops = ["BT", "%d TL" % lead, "%d %d Td" % (M, H - M)]
        for kind, t in pg:
            ops.append("/%s %d Tf" % ("F2" if kind == "T" else "F1", 16 if kind == "T" else size))
            ops.append("(%s) '" % _pdfstr(t))
        ops.append("ET")
        stream = "\n".join(ops).encode("latin-1", "replace")
        c = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        p = add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %d %d] /Resources << /Font << /F1 %d 0 R /F2 %d 0 R >> >> /Contents %d 0 R >>" % (W, H, f1, f2, c))
        kids.append(p)
    objs[1] = ("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % k for k in kids), len(kids))).encode()
    out = b"%PDF-1.4\n"
    offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += b"%d 0 obj\n" % i + o + b"\nendobj\n"
    x = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for o in offs:
        out += b"%010d 00000 n \n" % o
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_bytes(out)
    return str(path)


# ---------------------------------------------------------------- image generation
def _safe_name(s, n=28):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:n] or "image"


def generate_image(prompt):
    """(path, None) or (None, reason). Saved in the Library folder."""
    k = cloud.keys()
    if not (k.get("cloudflare") and k.get(CF_ACCT)):
        return None, "Image generation needs a free Cloudflare account. Say /setup cloudflare."
    if not online_ok():
        return None, "Online help is off. Say /cloud on and I'll draw it."
    try:
        r = _cf_run(CF_IMAGE, {"prompt": prompt[:1000], "steps": 4})
        raw = base64.b64decode(r["image"])
    except Exception as e:
        return None, "I couldn't generate that (%s)." % _why(e)
    ext = "png" if raw[:4] == b"\x89PNG" else "jpg"
    UPLOADS.mkdir(parents=True, exist_ok=True)
    p = UPLOADS / ("%d-image-%s.%s" % (int(time.time() * 1000), _safe_name(prompt), ext))
    p.write_bytes(raw)
    s = cloud.state(); s["used"]["cloudflare"] = s["used"].get("cloudflare", 0) + 1; cloud._save_state(s)
    return str(p), None


# ---------------------------------------------------------------- finding the latest upload
def latest(kind):
    exts = {"image": (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"), "pdf": (".pdf",)}[kind]
    try:
        fs = [f for f in UPLOADS.iterdir() if f.suffix.lower() in exts]
        return str(max(fs, key=lambda f: f.stat().st_mtime)) if fs else None
    except Exception:
        return None


# ---------------------------------------------------------------- commands and natural requests
ATT = re.compile(r"\[attached (image|pdf|file): (.+?) \(file: (.+?)\)")
DRAW = re.compile(r"^(?:please )?(?:draw|generate|create|make|paint|design)(?: me)?(?: an?| the)? (?:image|picture|photo|drawing|illustration|painting|logo|poster|wallpaper)(?: of| showing| for| with)? (.+)$", re.I)


def image_reply(prompt):
    path, why = generate_image(prompt)
    if not path:
        return why
    return "Here it is.\n[image: %s]\nSaved in your Library as %s." % (path, Path(path).name)


def command(msg):
    """/image, /see, /pdf and /key|/setup cloudflare. None when the message is not one of these."""
    parts = msg.strip().split(None, 1)
    if not parts:
        return None
    c = parts[0].lower()
    rest = parts[1].strip() if len(parts) > 1 else ""
    if c == "/image":
        return image_reply(rest) if rest else "Say what to draw, for example: /image a lighthouse at sunset, oil painting"
    if c == "/see":
        p = latest("image")
        if not p:
            return "Attach a photo first (the + button), then say /see or ask about it."
        t, why = see(p, rest or None)
        return t or "I couldn't look at it: %s." % why
    if c == "/pdf":
        w = rest.split(None, 1)
        sub = w[0].lower() if w else ""
        arg = w[1].strip() if len(w) > 1 else ""
        if sub == "read":
            p = latest("pdf")
            if not p:
                return "Attach a PDF first (the + button), then say /pdf read."
            kind, val = read_pdf(p, arg or None)
            return val if kind == "answer" else "(offline text of %s)\n%s" % (Path(p).name, val.split("[document text]\n", 1)[1][:1500])
        if sub == "make":
            title, _, body = arg.partition("|")
            if not body.strip():
                return "Usage: /pdf make Title | the text of the document"
            UPLOADS.mkdir(parents=True, exist_ok=True)
            p = make_pdf(title.strip(), body.strip(), UPLOADS / ("%d-%s.pdf" % (int(time.time() * 1000), _safe_name(title or "document"))))
            return "Created %s. It is in your Library (Attach, then Files)." % Path(p).name
        return "Usage: /pdf read [question]   or   /pdf make Title | text"
    if c in ("/key", "/setup") and rest.lower().startswith("cloudflare"):
        tail = rest[len("cloudflare"):].strip().lower()
        if c == "/setup":
            return cloud.provider_guide("cloudflare", open_site=tail == "open")
        if tail == "remove":
            cloud.del_key("cloudflare"); cloud.del_key(CF_ACCT); return "Removed the Cloudflare keys."
        raw = cloud._clipboard() if tail in ("", "paste") else rest.split(None, 1)[1].strip()
        return _save_cf(raw)
    return None


def _save_cf(raw):
    raw = (raw or "").strip()
    if re.fullmatch(r"[0-9a-f]{32}", raw):
        cloud.set_key(CF_ACCT, raw); what = "Account ID"
    elif re.fullmatch(r"[A-Za-z0-9_\-]{35,60}", raw):
        cloud.set_key("cloudflare", raw); what = "API token"
    else:
        return "I didn't find a Cloudflare Account ID (32 letters and numbers) or an API token on your clipboard. Copy one and say /key cloudflare paste. /setup cloudflare shows where."
    k = cloud.keys()
    if k.get("cloudflare") and k.get(CF_ACCT):
        try:
            _cf_run(CF_TEXT, {"messages": [{"role": "user", "content": "Reply with the word: ready"}], "max_tokens": 6}, 40)
            r = "working"
            s = cloud.state(); s["cool"].pop("cloudflare", None); cloud._save_state(s)
        except urllib.error.HTTPError as e:
            r = "the %s" % {401: "token was rejected", 403: "token was rejected or lacks Workers AI permission"}.get(e.code, "provider answered with error %d" % e.code)
        except Exception as e:
            r = "could not check (%s)" % _why(e)
        return "Saved the %s. Cloudflare test: %s." % (what, r)
    need = "API token" if k.get(CF_ACCT) else "Account ID"
    return "Saved the %s. Now copy the %s and say /key cloudflare paste again." % (what, need)


def process(msg):
    """Looks at a chat message before the brain does.
    Returns None (carry on), ('answer', text) or ('rewrite', new_message)."""
    m = ATT.search(msg)
    if m:
        kind, name, path = m.group(1), m.group(2), m.group(3)
        q = msg.split("\n\n[attached", 1)[0].strip()
        q = "" if q.lower().startswith("look at the attachment") else q
        if kind == "pdf":
            return read_pdf(path, q or None)
        if kind == "image" and online_ok():
            t, why = see(path, q or None)
            if t:
                return ("answer", t)
        return None
    d = DRAW.match(msg.strip().rstrip(".!?"))
    if d:
        return ("answer", image_reply(d.group(1)))
    return None
