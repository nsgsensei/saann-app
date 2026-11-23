import json, re, time
from core.eventlog import log_event, _conn
from core import models
from core.gateway import ask
from engine.primitives import CODE, LLM
from engine.validate import validate

class Budget:
    def __init__(self, calls=6, seconds=300):
        self.calls = calls
        self.deadline = time.time() + seconds
    def ok(self):
        return self.calls > 0 and time.time() < self.deadline
    def use(self):
        self.calls -= 1

def _parse(text):
    if not text:
        raise ValueError("empty reply")
    try:
        return json.loads(text.strip())
    except Exception:
        pass
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("no JSON found")
    return json.loads(m.group(0))

def _cloud_allowed(private):
    c = models.load()
    return (not private) and c["mode"] in ("cloud", "hybrid") \
        and not models.cap_reached()

def run(agent, name, private=False, budget=None, **inputs):
    budget = budget or Budget()
    if name in CODE:
        try:
            val = CODE[name](**inputs)
            log_event(agent, "step." + name, input=str(inputs)[:300],
                      output=str(val)[:300], model="code", outcome="verified")
            return {"ok": True, "value": val, "source": "code"}
        except Exception as e:
            log_event(agent, "step." + name, input=str(inputs)[:300],
                      output=str(e), model="code", status="failed",
                      outcome="failed")
            return {"ok": False, "reason": str(e)}
    spec = LLM.get(name)
    if not spec:
        return {"ok": False, "reason": "unknown primitive"}
    inp = {**spec.get("defaults", {}), **inputs}
    if "prep" in spec:
        inp = spec["prep"](inp)
    schema = spec["schema"](inp) if callable(spec["schema"]) else spec["schema"]
    base = spec["prompt"].format(**inp)
    mode = models.load().get("mode", "local")
    if mode == "cloud" and not private:
        attempts = [("cloud", 0.0)] if _cloud_allowed(private) else []
    else:
        attempts = [("local", 0.2), ("local", 0.0)]
        if _cloud_allowed(private):
            attempts.append(("cloud", 0.0))
    reason = ""
    history = []
    soft = None
    for i, (src, temp) in enumerate(attempts):
        if reason:
            history.append(reason)
        if not budget.ok():
            reason = "budget exhausted"
            break
        prompt = base if i == 0 else base + (
            f"\n\nYour previous reply was rejected: {reason}. "
            "Reply again with valid JSON only.")
        budget.use()
        r = ask(agent, prompt, private=private, force=src, schema=schema,
                temperature=temp, max_tokens=spec.get("max_tokens", 300))
        if r["text"] is None:
            reason = r.get("error", "model error")
            continue
        try:
            out = _parse(r["text"])
            errs = validate(out, schema)
            if errs:
                reason = "; ".join(errs[:3])
                continue
            ok, val, why = spec["check"](out, inp)
            if not ok:
                reason = why
                continue
        except Exception as e:
            reason = str(e)
            continue
        uncertain = isinstance(val, dict) and val.get("confident") is False
        if uncertain and i < len(attempts) - 1 and budget.ok():
            soft = (val, r, i)
            reason = "model said it was unsure"
            continue
        log_event(agent, "step." + name, input=str(inputs)[:300],
                  output=json.dumps(val)[:500], model=r.get("model", src),
                  outcome="uncertain" if uncertain else "verified")
        return {"ok": True, "value": val, "uncertain": uncertain,
                "attempts": i + 1, "source": r["source"]}
    if soft:
        val, r, i = soft
        log_event(agent, "step." + name, input=str(inputs)[:300],
                  output=json.dumps(val)[:500], model=r.get("model", "?"),
                  outcome="uncertain")
        return {"ok": True, "value": val, "uncertain": True,
                "attempts": i + 1, "source": r["source"]}
    reason = " | ".join(history + [reason])
    log_event(agent, "step." + name, input=str(inputs)[:300], output=reason,
              status="failed", outcome="failed")
    return {"ok": False, "reason": reason}

def pass_rates():
    with _conn() as c:
        rows = c.execute(
            "SELECT action, outcome, COUNT(*) FROM events "
            "WHERE action LIKE 'step.%' GROUP BY action, outcome").fetchall()
    out = {}
    for a, o, n in rows:
        out.setdefault(a[5:], {})[o] = n
    return out
