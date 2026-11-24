import builtins
import os
import sys

_state = None

def init(files_dir, bridge):
    global _state
    root = os.path.join(files_dir, "saann")
    os.makedirs(os.path.join(root, "data"), exist_ok=True)
    sys.path.insert(0, root)
    os.chdir(root)
    import android_bridge
    android_bridge.B = bridge
    from engine import phone
    phone._run = android_bridge.run
    # approvals: the app shows a dialog instead of waiting on a keyboard
    builtins.input = lambda prompt="": bridge.confirm(str(prompt))
    import saann
    _state = saann.State()
    from engine import agent

    def _status(t):
        try:
            bridge.call("status", str(t), "")
        except Exception:
            pass

    agent.STATUS = _status

    def _step(phase, tool, args, ok, result):
        import json
        try:
            a = json.dumps(args)[:160] if args else ""
            bridge.call("live_step", json.dumps({"phase": phase, "tool": tool, "args": a,
                                                   "ok": bool(ok), "result": str(result)[:200]}), "")
        except Exception:
            pass

    agent.STEP = _step
    # SAANN_DISCOVERY_STARTUP_V2
    from engine import connection_upgrade
    connection_upgrade.install()
    return "ready"

FAST_OK = ("phone_battery", "phone_torch", "phone_volume_set", "phone_brightness", "phone_wifi", "phone_open_url")

def _fast_old(msg):
    """Answers simple things instantly, without any model: greetings, time, date, flashlight, battery ..."""
    import random, re, time
    t = msg.strip()
    g = t.lower().strip(" ?.!,")
    if re.fullmatch(r"(hi|hello|hey|yo|hiya|good (morning|afternoon|evening))( there| saann| saan)?", g):
        return random.choice(["Hi! What can I do for you?", "Hello! I'm here.", "Hey! What do you need?"])
    if re.fullmatch(r"(what('?s| is) )?(the )?(current )?time( is it)?( now| right now)?|what time is it( now)?|do you know what time it is", g):
        return time.strftime("It's %I:%M %p.").replace("It's 0", "It's ")
    if re.fullmatch(r"(what('?s| is) )?(today'?s |the )?(date|day)( is it)?( today)?|what day is (it|today)|which day is today", g):
        return time.strftime("Today is %A, %d %B %Y.")
    try:
        from engine import agent
        from core.tools import call_tool
        q = agent.quick(t)
        if q and q[0] in FAST_OK:
            r = call_tool("phone-agent", q[0], **q[1])
            if r["ok"]:
                s = str(r["result"])
                if q[0] == "phone_torch":
                    return "Flashlight " + q[1]["state"] + "."
                return s
            return "That failed: " + str(r["error"])
    except Exception:
        return None
    return None

def respond(msg):
    if msg.startswith("/do "):
        return _do(msg[4:])
    if not msg.startswith("/"):
        fast = _fast(msg)
        if fast is not None:
            return fast

        try:
            from engine import agent

            if agent.wants_phone(msg):
                result = agent.run(msg)

                if result and result.get("answer"):
                    return result["answer"]
        except Exception:
            pass

    import saann
    return saann.respond(msg, _state)


def stop():
    from engine import agent
    agent.STOP["flag"] = True


def _do(text):
    """Test one tool by hand:  /do 8 screen_look   or   /do screen_tap x=540 y=1200"""
    import shlex, time
    from core.tools import call_tool
    from engine import phone
    parts = shlex.split(text)
    if parts and parts[0].isdigit():
        time.sleep(min(30, int(parts[0])))
        parts = parts[1:]
    if not parts or parts[0] not in phone.SPEC:
        return "tools: " + ", ".join(phone.SPEC)
    args = {}
    for p in parts[1:]:
        k, _, v = p.partition("=")
        args[k] = int(v) if v.lstrip("-").isdigit() else v
    r = call_tool("phone-agent", parts[0], **args)
    return str(r.get("result") if r["ok"] else "ERROR: " + str(r["error"]))


def brain_snapshot():
    import json
    try:
        from engine import brain
        return json.dumps(brain.get().snapshot())
    except Exception:
        return "{}"


def brain_view():
    try:
        from engine import brain_view as _v
        return _v.json_text()
    except Exception as e:
        return '{"error": "%s"}' % str(e).replace('"', "'")

# Jarvis-style natural replies (final replies only; live step card untouched)
def respond(msg, _orig=respond):
    try:
        from engine import jarvis as _j
        f = _j.wrap(_orig)
    except Exception:
        f = _orig
    return f(msg)


def _bridge(name, a="", b=""):
    import android_bridge
    return android_bridge._call(name, a, b)

def _nice(secs):
    h, r = divmod(int(secs), 3600)
    m, s = divmod(r, 60)
    out = []
    if h: out.append("%d hour%s" % (h, "" if h == 1 else "s"))
    if m: out.append("%d minute%s" % (m, "" if m == 1 else "s"))
    if s: out.append("%d second%s" % (s, "" if s == 1 else "s"))
    return " ".join(out) or "0 seconds"

def _plan(t):
    """Matches one simple phone command and returns a function that does it, or None."""
    import re
    from engine import motor as mo
    s = re.sub(r"^(please|hey saann|saann|can you|could you|would you|i want you to|i need you to)[ ,]+", "", t.strip(" ?!.,"), flags=re.I)
    s = re.sub(r"[ ,]+(please|now|for me)$", "", s, flags=re.I).strip()
    I = re.I
    m = re.fullmatch(r"(?:set |start |make )?(?:a |an )?(?:timer|countdown)(?: for| of)? (.+)|(?:set |start )?(?:a |an )?(.+?) (?:timer|countdown)|remind me in (.+)", s, I)
    if m:
        secs = mo.parse_duration(next(x for x in m.groups() if x))
        if secs:
            return lambda: (_bridge("timer", secs, "SAANN"), "Timer set for %s." % _nice(secs))[1]
    m = re.fullmatch(r"(?:set )?(?:an? )?alarm (?:for|at) (.+)|wake me(?: up)? at (.+)", s, I)
    if m:
        hm = mo.parse_time(next(x for x in m.groups() if x))
        if hm:
            h, mi = hm
            return lambda: (_bridge("alarm", "%d:%d" % (h, mi), "SAANN"),
                            "Alarm set for %d:%02d %s." % ((h % 12) or 12, mi, "AM" if h < 12 else "PM"))[1]
    m = re.fullmatch(r"(?:turn |make )?(?:the )?(?:volume|sound) (up|down)|(louder)|(quieter)|(mute)(?: the phone)?", s, I)
    if m:
        d = "up" if (m.group(1) or "").lower() == "up" or m.group(2) else "down" if m.group(1) or m.group(3) else "mute"
        return lambda: (_bridge("vol_dir", d), "Volume " + d + ".")[1]
    m = re.fullmatch(r"(pause|resume|next|skip|previous|stop|play)(?: the)?(?: music| song| track| playback)?|play(?: some)? music|(?:go to the |play the )?(next|previous) (?:song|track)", s, I)
    if m:
        w = (m.group(1) or m.group(2) or "play").lower()
        a = {"skip": "next", "resume": "play"}.get(w, w)
        return lambda: (_bridge("media", a), {"pause": "Paused.", "stop": "Stopped.", "next": "Next track.", "previous": "Previous track."}.get(a, "Playing."))[1]
    m = re.fullmatch(r"(?:text|message|sms)\s+(.+?)\s+(?:saying|that says|saying that|that)\s+(.+)|send (?:a )?(?:text|message|sms) to (.+?)\s+(?:saying|that)\s+(.+)", s, I)
    if m:
        g = m.groups()
        who, body = (g[0], g[1]) if g[0] else (g[2], g[3])
        return lambda: "Opening a message to %s (%s). Tap send." % (who, _bridge("sms", who.strip(), body.strip()))
    m = re.fullmatch(r"(?:call|phone|ring|dial) (?!me\b|back\b)(.+)", s, I)
    if m:
        who = m.group(1).strip()
        return lambda: "Opening the dialer for %s (%s). Tap call." % (who, _bridge("dial", who))
    m = re.fullmatch(r"(?:search(?: the web)?(?: for)?|google|look up) (.+)", s, I)
    if m:
        q = m.group(1).strip()
        return lambda: (_bridge("search", q), "Searching for %s." % q)[1]
    m = re.fullmatch(r"(?:open|launch|start|run|go to) (?:the )?(?!https?://)(.+?)(?: app)?", s, I)
    if m and not re.search(r"flash ?light|torch|wifi|bluetooth", m.group(1), I):
        name = m.group(1).strip()
        return lambda: "Opening %s." % _bridge("open_app", name)
    return None

def _one(part):
    p = _plan(part)
    if p:
        return p
    try:
        from engine import agent
        q = agent.quick(part)
    except Exception:
        q = None
    if q and q[0] in FAST_OK:
        def run(q=q):
            from core.tools import call_tool
            r = call_tool("phone-agent", q[0], **q[1])
            if not r["ok"]:
                return "That failed: " + str(r["error"])
            return ("Flashlight " + q[1]["state"] + ".") if q[0] == "phone_torch" else str(r["result"])
        return run
    return None

def _fast(msg):
    import re
    out = _fast_old(msg)
    if out is not None:
        return out
    t = msg.strip()
    plans = []
    whole = _one(t)
    if whole:
        plans = [whole]
    else:
        parts = [x for x in re.split(r"\s*(?:,|;|\band then\b|\bthen\b|\band\b)\s+", t.strip(" ?!.,")) if x.strip()]
        if len(parts) > 1:
            plans = [_one(x) for x in parts]
            if not all(plans):
                return None
    if not plans:
        return None
    res = []
    for p in plans:
        try:
            res.append(p())
        except Exception as e:
            res.append("That failed: " + str(e))
    return " ".join(res)

def tasks_json():
    import json, time
    from engine import scheduler as s
    with s._db() as c:
        rows = c.execute("SELECT id,name,request,kind,at,every_min,enabled,next_run,last_status FROM tasks ORDER BY id").fetchall()
    out = []
    for r in rows:
        when = ("daily at " + str(r[4])) if r[3] == "daily" else "every %d min" % r[5]
        out.append({"id": r[0], "name": r[1], "request": r[2], "when": when, "enabled": bool(r[6]),
                    "next": time.strftime("%a %H:%M", time.localtime(r[7])), "last": r[8] or ""})
    return json.dumps(out)

def task_add(request, spec):
    from engine import scheduler as s
    spec = spec.strip().lower().split()
    try:
        if not request.strip():
            return "ERR:Say what the task should do"
        if len(spec) == 2 and spec[0] == "daily":
            s.add(request[:40], request, "", spec[1], None)
        elif len(spec) == 2 and spec[0] == "every":
            s.add(request[:40], request, "", None, int(spec[1]))
        else:
            return "ERR:Use 'daily 07:30' or 'every 60' (minutes, at least 15)"
    except Exception as e:
        return "ERR:" + str(e)
    return "ok"

def task_toggle(tid):
    from engine import scheduler as s
    with s._db() as c:
        c.execute("UPDATE tasks SET enabled=1-enabled WHERE id=?", (int(tid),))
    return "ok"

def task_remove(tid):
    from engine import scheduler as s
    with s._db() as c:
        c.execute("DELETE FROM tasks WHERE id=?", (int(tid),))
    return "ok"

def task_run_now(tid):
    from engine import scheduler as s
    with s._db() as c:
        row = c.execute("SELECT id,name,request,url FROM tasks WHERE id=?", (int(tid),)).fetchone()
    if not row:
        return "no such task"
    return str(respond(row[2]))

def tasks_run_due():
    import json, time
    from engine import scheduler as s
    with s._db() as c:
        due = c.execute("SELECT id,name,request,url,kind,at,every_min FROM tasks WHERE enabled=1 AND next_run<=?", (time.time(),)).fetchall()
    ran = []
    for row in due[:3]:
        try:
            out = str(respond(row[2]))
            status = "failed: no model" if "no answer" in out.lower() else "ok"
        except Exception as e:
            out, status = "", "failed: " + str(e)[:100]
        done = time.time()
        with s._db() as c:
            c.execute("UPDATE tasks SET last_run=?, last_status=?, next_run=? WHERE id=?",
                      (done, status, s._next(row[4], row[5], row[6], done), row[0]))
        ran.append({"name": row[1], "report": (out or status)[:900]})
    return json.dumps({"due": len(due), "ran": ran})


# SAANN_CONNECTED_SETUP_V1
_setup_credentials = {}
_setup_installed = False
_setup_legacy_keys = None


def setup_api(operation, payload="{}"):
    import json
    from engine import cloud, media
    # SAANN_DISCOVERY_SETUP_V2
    from engine import connection_upgrade
    connection_upgrade.install()

    global _setup_credentials
    global _setup_installed
    global _setup_legacy_keys

    try:
        data = json.loads(str(payload) or "{}")

        if not _setup_installed:
            _setup_legacy_keys = cloud.keys

            def combined_keys():
                old = dict(_setup_legacy_keys() or {})
                old.update(_setup_credentials)
                return old

            cloud.keys = combined_keys
            _setup_installed = True

        if operation == "restore":
            allowed = (
                "gemini", "groq", "cloudflare",
                "cloudflare_account"
            )
            _setup_credentials = {
                name: str(data[name]).strip()
                for name in allowed
                if str(data.get(name, "")).strip()
            }
            return json.dumps({"ok": True})

        if operation == "test":
            name = str(data.get("provider", ""))
            if name not in ("gemini", "groq", "cloudflare"):
                return json.dumps({
                    "ok": False, "message": "Unknown provider"
                })

            key = str(data.get("key", "")).strip()
            if not key:
                return json.dumps({
                    "ok": False, "message": "Enter the API key first."
                })

            if any(ch.isspace() for ch in key):
                return json.dumps({
                    "ok": False,
                    "message": (
                        "The credential contains spaces or line breaks. "
                        "Copy the complete secret again, without extra text."
                    )
                })

            account = str(data.get("account", "")).strip()
            if name == "cloudflare" and not account:
                return json.dumps({
                    "ok": False,
                    "message": "Enter the Cloudflare Account ID."
                })

            # Test credentials temporarily; roll back on every failure path.
            previous_credentials = dict(_setup_credentials)
            previous_models = dict(cloud.state().get("models", {}))
            ok = False

            try:
                _setup_credentials[name] = key
                if name == "cloudflare":
                    _setup_credentials["cloudflare_account"] = account

                # A deliberate user test must not reuse a cached success.
                with connection_upgrade._lock:
                    connection_upgrade._cache.clear()
                    result = cloud.test(name)

                ok = isinstance(result, str) and result.startswith("working")
                return json.dumps({"ok": ok, "message": result})
            finally:
                if not ok:
                    _setup_credentials.clear()
                    _setup_credentials.update(previous_credentials)
                    restored = cloud.state()
                    if name in previous_models:
                        restored["models"][name] = previous_models[name]
                    else:
                        restored["models"].pop(name, None)
                    cloud._save_state(restored)

        if operation == "image_test":
            # Explicitly requested by the user in the setup screen.
            import base64

            result = media._cf_run(
                media.CF_IMAGE,
                {"prompt": "A small orange circle on a black background",
                 "steps": 4}
            )
            encoded = result.get("image", "")
            raw = base64.b64decode(encoded, validate=True)

            if not raw:
                return json.dumps({
                    "ok": False,
                    "message": "The provider returned an empty image."
                })

            media.UPLOADS.mkdir(parents=True, exist_ok=True)
            suffix = ".png" if raw.startswith(b"\x89PNG") else ".jpg"
            path = media.UPLOADS / ("setup-image-test" + suffix)
            path.write_bytes(raw)

            return json.dumps({
                "ok": True,
                "message": "Image generated and saved in Library.",
                "path": str(path)
            })

        if operation == "finish":
            if not cloud.connected():
                return json.dumps({
                    "ok": False,
                    "message": "Connect a text provider first."
                })

            # Cloud-only is intentional: local servers remain disabled.
            if not cloud._set_mode("cloud"):
                return json.dumps({
                    "ok": False,
                    "message": "Could not save cloud mode."
                })

            return json.dumps({
                "ok": True,
                "message": "Cloud mode enabled."
            })

        return json.dumps({
            "ok": False, "message": "Unknown setup operation."
        })

    except Exception as exc:
        # Do not return exception text which could contain credentials.
        import urllib.error

        if isinstance(exc, urllib.error.HTTPError):
            message = {
                400: "The provider rejected the request. Check model support.",
                401: "The key was rejected.",
                403: "Access denied. Check permissions and account access.",
                404: "The configured model or endpoint was not found.",
                429: "Quota or rate limit reached. Try again later."
            }.get(exc.code, "Provider HTTP error %s." % exc.code)
        else:
            message = (
                "Connection or response failed. Check internet, "
                "account access, and provider settings."
            )

        return json.dumps({"ok": False, "message": message})


# SAANN_CONVERSATION_BOUNDARY_V5
_android_inner_respond = respond


def respond(msg):
    from engine.conversation import run
    return run(msg, _android_inner_respond)


# SAANN_SESSION_ENTRY_V6
def respond_chat(message, session_id, history_json):
    from engine.conversation import run
    return run(
        message,
        _android_inner_respond,
        session_id=str(session_id),
        history=str(history_json)
    )


# SAANN_DETAIL_SNAPSHOT_V7
_original_detail_view = brain_view


def brain_view():
    import json
    from engine import brain

    try:
        report = json.loads(_original_detail_view())
        if not isinstance(report, dict):
            report = {"error": "Unexpected detail response"}
    except Exception:
        report = {"error": "Could not load the detailed brain report"}

    try:
        report["association_snapshot"] = brain.get().snapshot()
    except Exception:
        report["association_snapshot_error"] = "Snapshot unavailable"

    return json.dumps(report)


# SAANN_UI_CONNECTION_SNAPSHOT_V1
def ui_connection_snapshot():
    """Local configuration only. Never returns credentials or tests providers."""
    import json
    from engine import cloud

    keys = cloud.keys()
    providers = []
    for name in ("gemini", "groq", "cloudflare"):
        model = ""
        try:
            model = str(cloud.model_of(name) or "")
        except Exception:
            pass
        configured = bool(keys.get(name))
        if name == "cloudflare":
            configured = configured and bool(keys.get("cloudflare_account"))
        providers.append({
            "name": name,
            "configured": configured,
            "model": model
        })
    return json.dumps({"providers": providers})
