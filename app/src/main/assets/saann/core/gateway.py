import json, os, urllib.request
from core.eventlog import log_event
from core import models

THRESHOLD = 0.8
SYSTEM = "You are SAANN, a concise assistant."
STUDENTS = {}

def register_student(task_type, fn):
    STUDENTS[task_type] = fn

def _post(url, body, headers):
    req = urllib.request.Request(url, json.dumps(body).encode(), headers)
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)

def servers():
    c = models.load()
    return c.get("servers") or {"main": {"model": c["active_local"], "port": 8080}}

def _local(prompt, max_tokens, schema, temperature, tier="main"):
    sv = servers()
    cfg = sv.get(tier) or sv.get("main") or list(sv.values())[0]
    body = {"messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": prompt}],
            "max_tokens": max_tokens, "temperature": temperature}
    if schema:
        body["response_format"] = {"type": "json_object", "schema": schema}
    try:
        from engine import media
        if media._stream_cb() and not schema:
            return media.local_stream("http://127.0.0.1:%d/v1/chat/completions" % cfg["port"], body), cfg["model"]
    except Exception:
        pass
    data = _post("http://127.0.0.1:%d/v1/chat/completions" % cfg["port"],
                 body, {"Content-Type": "application/json"})
    return data["choices"][0]["message"]["content"].strip(), cfg["model"]

def _cloud(prompt, max_tokens, schema, temperature):
    from engine import cloud

    result = cloud.complete(
        prompt,
        max_tokens=max_tokens,
        schema=schema,
        temperature=temperature,
        system=SYSTEM,
    )

    if not result:
        raise RuntimeError(
            "Cloud providers unavailable. "
            "Run /cloud test for provider diagnostics."
        )

    return result


def _cloud_diagnostics():
    from engine import cloud

    connected = cloud.connected()
    if not connected:
        return "No cloud provider key is saved. Run /setup."

    lines = []
    for name in connected:
        state = cloud.state()
        used = state["used"].get(name, 0)
        daily = cloud.PROVIDERS[name]["daily"]
        cooling = state["cool"].get(name, 0) > __import__("time").time()
        lines.append(
            "%s: %d of about %d requests today%s"
            % (cloud.PROVIDERS[name]["name"], used, daily,
               " (temporarily paused)" if cooling else "")
        )

    return "; ".join(lines)


def ask(agent, prompt, task_type="general", private=False, hard=False,
        max_tokens=300, schema=None, temperature=0.3, force=None,
        tier="main"):
    if force in servers():
        tier, force = force, "local"
    student = STUDENTS.get(task_type)
    conf = 0.0
    if student:
        ans, conf = student(prompt)
        if conf >= THRESHOLD:
            eid = log_event(agent, "gateway.student", input=prompt,
                            output=ans, model=task_type + "-ann",
                            confidence=conf)
            return {"text": ans, "source": "ann", "event_id": eid}
    mode = models.load()["mode"]
    if private or mode == "local":
        order = ["local"]
    elif force:
        order = [force]
    elif mode == "cloud":
        # SAANN_CLOUD_ONLY_V8
        order = ["cloud"]
    else:
        order = ["cloud", "local"] if hard else ["local", "cloud"]
    if models.cap_reached():
        order = [o for o in order if o != "cloud"]
    err = "Cloud usage cap reached." if not order else None
    for src in order:
        try:
            if src == "local":
                text, mid = _local(prompt, max_tokens, schema, temperature, tier)
            else:
                text, mid = _cloud(prompt, max_tokens, schema, temperature)
            eid = log_event(agent, "gateway." + src, input=prompt,
                            output=text, model=mid, confidence=conf,
                            outcome="unverified")
            return {"text": text, "source": src, "model": mid, "event_id": eid}
        except Exception as e:
            err = str(e)
    eid = log_event(agent, "gateway.error", input=prompt, output=err,
                    status="error")
    return {"text": None, "source": "error", "error": err, "event_id": eid}
