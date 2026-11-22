"""Online help for SAANN: free cloud providers behind the existing gateway, with guided setup.
Local models and the brain come first. The cloud is asked only when mode is hybrid or cloud, the question
is not private (the gateway enforces that), and a provider has a key and quota left. Rejected, rate limited
or offline providers are skipped; if all fail the gateway falls back to the local models as before.
  /setup | /setup groq | /setup gemini (add "open")   guided steps
  /key groq paste | /key gemini paste | /key groq remove
  /cloud | /cloud on | /cloud off | /cloud test | /cloud model groq NAME
Keys live only in data/keys.json (private app folder), never in chat history, event log or brain.
"""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KEYS = ROOT / "data" / "keys.json"
STATE = ROOT / "data" / "cloud.json"

PROVIDERS = {
    "groq": {
        "name": "Groq", "gives": "fast replies (Llama) and later speech to text",
        "url": "https://api.groq.com/openai/v1/chat/completions", "model": "llama-3.3-70b-versatile",
        "site": "https://console.groq.com/keys", "prefix": "gsk_", "daily": 400,
        "steps": ["Open console.groq.com/keys (say \"/setup groq open\" and I'll open it for you).",
                  "Sign in with Google. Skip any optional questions.",
                  "Tap Create API Key, name it saann, tap Submit.",
                  "Tap Copy right away: the key (starts with gsk_) is shown only once.",
                  "Come back here and say: /key groq paste"],
    },
    "gemini": {
        "name": "Google Gemini", "gives": "stronger answers, images and PDFs (later)",
        "url": "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", "model": "gemini-2.0-flash",
        "site": "https://aistudio.google.com/app/apikey", "prefix": "AIza", "daily": 300,
        "steps": ["Open aistudio.google.com/app/apikey (say \"/setup gemini open\").",
                  "Sign in with your Google account and accept the terms.",
                  "Tap Create API key, pick any project (or create one).",
                  "Tap Copy: the key starts with AIza.",
                  "Come back here and say: /key gemini paste"],
    },
}
ORDER = ["groq", "gemini"]
COOLDOWN = 600


def _read(p, default):
    try:
        return json.loads(p.read_text())
    except Exception:
        return default


def _write(p, d, private=False):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(d))
    if private:
        try:
            os.chmod(p, 0o600)
        except Exception:
            pass


def keys():
    return _read(KEYS, {})


def set_key(name, key):
    k = keys()
    k[name] = key.strip()
    _write(KEYS, k, private=True)


def del_key(name):
    k = keys()
    k.pop(name, None)
    _write(KEYS, k, private=True)


def state():
    s = _read(STATE, {})
    day = time.strftime("%Y-%m-%d")
    if s.get("day") != day:
        s = {"day": day, "used": {}, "cool": s.get("cool", {}), "models": s.get("models", {})}
    s.setdefault("used", {}); s.setdefault("cool", {}); s.setdefault("models", {})
    return s


def _save_state(s):
    _write(STATE, s)


def model_of(name, s=None):
    return (s or state())["models"].get(name) or PROVIDERS[name]["model"]


def looks_like_key(name, text):
    t = (text or "").strip()
    return t.startswith(PROVIDERS[name]["prefix"]) and 20 <= len(t) <= 200 and " " not in t and "\n" not in t


def connected():
    k = keys()
    return [n for n in ORDER if k.get(n)]


def _post(url, body, headers, timeout=40):
    req = urllib.request.Request(url, json.dumps(body).encode(), headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def _call(name, key, prompt, max_tokens, schema, temperature, system):
    p = PROVIDERS[name]
    model = model_of(name)
    if schema:
        prompt += "\n\nReply with ONLY valid JSON matching this schema:\n" + json.dumps(schema)
    if name == "groq":
        body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
                "max_tokens": max_tokens, "temperature": temperature}
        if schema:
            body["response_format"] = {"type": "json_object"}
        d = _post(p["url"], body, {"Content-Type": "application/json", "Authorization": "Bearer " + key,
                                   "User-Agent": "saann/1.0"})
        return d["choices"][0]["message"]["content"].strip(), "groq:" + model
    if name == "gemini":
        body = {"systemInstruction": {"parts": [{"text": system}]},
                "contents": [{"role": "user", "parts": [{"text": prompt}]}],
                "generationConfig": {"maxOutputTokens": max_tokens, "temperature": temperature}}
        if schema:
            body["generationConfig"]["responseMimeType"] = "application/json"
        d = _post(p["url"].format(model=model), body, {"Content-Type": "application/json", "x-goog-api-key": key})
        return d["candidates"][0]["content"]["parts"][0]["text"].strip(), "gemini:" + model
    raise RuntimeError("unknown provider")


def complete(prompt, max_tokens=300, schema=None, temperature=0.3, system="You are SAANN, a concise assistant."):
    """(text, model_id) from the first working provider, or None when none is connected or all fail."""
    k = keys()
    s = state()
    now = time.time()
    for name in ORDER:
        if not k.get(name):
            continue
        if s["used"].get(name, 0) >= PROVIDERS[name]["daily"] or s["cool"].get(name, 0) > now:
            continue
        try:
            text, mid = _call(name, k[name], prompt, max_tokens, schema, temperature, system)
            s = state()
            s["used"][name] = s["used"].get(name, 0) + 1
            _save_state(s)
            return text, mid
        except urllib.error.HTTPError as e:
            s = state()
            if e.code in (401, 403):
                s["cool"][name] = now + 86400 * 365
            elif e.code in (429, 500, 502, 503):
                s["cool"][name] = now + COOLDOWN
            _save_state(s)
        except Exception:
            pass
    return None


def test(name):
    k = keys().get(name)
    if not k:
        return "no key saved yet"
    try:
        text, mid = _call(name, k, "Reply with the single word: ready", 8, None, 0.0, "Reply briefly.")
        s = state(); s["cool"].pop(name, None); _save_state(s)
        return "working (%s)" % mid
    except urllib.error.HTTPError as e:
        return {401: "the key was rejected", 403: "the key was rejected or lacks access",
                404: "that model name was not found (try /cloud model %s NAME)" % name,
                429: "limit reached for now"}.get(e.code, "the provider answered with error %d" % e.code)
    except Exception:
        return "could not reach the internet"


def _clipboard():
    try:
        from core.tools import call_tool
        r = call_tool("phone-agent", "phone_clipboard_get")
        return str(r["result"]).strip() if r.get("ok") else ""
    except Exception:
        return ""


def _open(url):
    try:
        from core.tools import call_tool
        r = call_tool("phone-agent", "phone_open_url", url=url)
        return bool(r.get("ok"))
    except Exception:
        return False


def _mode():
    try:
        from core import models
        return models.load().get("mode", "local")
    except Exception:
        return "local"


def _set_mode(m):
    try:
        from core import models
        c = models.load(); c["mode"] = m; models.save(c)
        return True
    except Exception:
        return False


def checklist():
    k = keys()
    n = len([p for p in ORDER if k.get(p)])
    out = ["Online help: %d of %d providers connected. Mode: %s." % (n, len(ORDER), _mode())]
    nxt = None
    for p in ORDER:
        ok = bool(k.get(p))
        out.append("%s %s - %s" % ("[x]" if ok else "[ ]", PROVIDERS[p]["name"], PROVIDERS[p]["gives"]))
        if not ok and nxt is None:
            nxt = p
    if nxt:
        out.append("Next: /setup %s  (steps, takes about two minutes)" % nxt)
    elif _mode() == "local":
        out.append("All connected. Say /cloud on to let me ask them when a question is too hard for the local models.")
    out.append("Private things (screen, clipboard, location, texts) never go online. Everything works offline without keys.")
    return "\n".join(out)


def provider_guide(name, open_site=False):
    p = PROVIDERS[name]
    lines = ["%s: %s" % (p["name"], p["gives"])]
    lines += ["%d. %s" % (i, s) for i, s in enumerate(p["steps"], 1)]
    lines.append("Don't type the key into the chat; copy it and use /key %s paste." % name)
    if open_site:
        lines.append("Opening the site now..." if _open(p["site"]) else "I couldn't open the browser; go to %s yourself." % p["site"].replace("https://", ""))
    return "\n".join(lines)


def usage_text():
    s = state()
    out = ["Mode: %s" % _mode()]
    now = time.time()
    for p in ORDER:
        if not keys().get(p):
            out.append("%s: not connected" % PROVIDERS[p]["name"]); continue
        note = ""
        if s["cool"].get(p, 0) > now:
            note = " (paused%s)" % (", key rejected" if s["cool"][p] - now > 86400 * 300 else ", limit hit")
        out.append("%s: %d of about %d today%s" % (PROVIDERS[p]["name"], s["used"].get(p, 0), PROVIDERS[p]["daily"], note))
    return "\n".join(out)


def command(msg):
    """Handles /setup, /key, /cloud. Returns text, or None when the message is not one of these."""
    parts = msg.strip().split()
    if not parts:
        return None
    c = parts[0].lower()
    a = parts[1:]
    if c == "/setup":
        if not a:
            return checklist()
        if a[0].lower() in PROVIDERS:
            return provider_guide(a[0].lower(), open_site=len(a) > 1 and a[1].lower() == "open")
        return "Providers: " + ", ".join(ORDER) + ". Try /setup groq"
    if c == "/key":
        if not a or a[0].lower() not in PROVIDERS:
            return "Usage: /key groq paste  (or gemini). Copy the key first; /setup shows how."
        name = a[0].lower()
        if len(a) > 1 and a[1].lower() == "remove":
            del_key(name); return "Removed the %s key." % PROVIDERS[name]["name"]
        if len(a) > 1 and a[1].lower() != "paste":
            raw = a[1]
            note = " Note: a key typed into the chat stays in your chat history; delete that message and use /key %s paste next time." % name
        else:
            raw, note = _clipboard(), ""
        if not looks_like_key(name, raw):
            return ("I didn't find a %s key on your clipboard (it should start with %s). Copy the key again, then say /key %s paste."
                    % (PROVIDERS[name]["name"], PROVIDERS[name]["prefix"], name))
        set_key(name, raw)
        s = state(); s["cool"].pop(name, None); _save_state(s)
        r = test(name)
        tail = " Say /cloud on to start using it." if (r.startswith("working") and _mode() == "local") else ""
        return "Saved the %s key. Test: %s.%s%s" % (PROVIDERS[name]["name"], r, tail, note)
    if c == "/cloud":
        if not a:
            return usage_text()
        w = a[0].lower()
        if w == "on":
            if not connected():
                return "No provider is connected yet. Say /setup."
            _set_mode("hybrid")
            return "Online help is on. Easy questions stay local; hard ones may use %s." % ", ".join(PROVIDERS[p]["name"] for p in connected())
        if w == "off":
            _set_mode("local"); return "Online help is off. Everything stays on this phone."
        if w == "test":
            return "\n".join("%s: %s" % (PROVIDERS[p]["name"], test(p)) for p in ORDER if keys().get(p)) or "Nothing connected yet."
        if w == "model" and len(a) >= 3 and a[1].lower() in PROVIDERS:
            s = state(); s["models"][a[1].lower()] = a[2]; _save_state(s); return "Model for %s is now %s." % (a[1].lower(), a[2])
        return "Usage: /cloud, /cloud on, /cloud off, /cloud test, /cloud model groq NAME"
    return None
