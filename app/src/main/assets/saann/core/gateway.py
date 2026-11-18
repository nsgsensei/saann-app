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
    data = _post("http://127.0.0.1:%d/v1/chat/completions" % cfg["port"],
                 body, {"Content-Type": "application/json"})
    return data["choices"][0]["message"]["content"].strip(), cfg["model"]

def _cloud(prompt, max_tokens, schema, temperature):
    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key:
        raise RuntimeError("no ANTHROPIC_API_KEY set")
    if schema:
        prompt += "\n\nReply with ONLY valid JSON matching this schema:\n" + json.dumps(schema)
    m = models.get(models.load()["active_cloud"])
    data = _post("https://api.anthropic.com/v1/messages", {
        "model": m["model"], "max_tokens": max_tokens, "system": SYSTEM,
        "temperature": temperature,
        "messages": [{"role": "user", "content": prompt}]},
        {"content-type": "application/json", "x-api-key": key,
         "anthropic-version": "2023-06-01"})
    u = data.get("usage", {})
    models.add_usage(u.get("input_tokens", 0) + u.get("output_tokens", 0))
    return data["content"][0]["text"].strip(), m["id"]

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
        order = ["cloud", "local"]
    else:
        order = ["cloud", "local"] if hard else ["local", "cloud"]
    if models.cap_reached():
        order = [o for o in order if o != "cloud"] or ["local"]
    err = None
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
