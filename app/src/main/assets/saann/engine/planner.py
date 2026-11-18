import json, re
from core.eventlog import log_event
from core.gateway import ask, servers
from engine.primitives import _kw, _covered
from engine.runner import run, Budget, _parse, _cloud_allowed
from engine.validate import validate

OPS = ["answer_from_text", "summarize", "extract_facts", "classify", "calc"]
PLAN_SCHEMA = {"type": "object", "required": ["steps"], "properties": {
    "steps": {"type": "array", "items": {
        "type": "object", "required": ["op", "arg"],
        "properties": {"op": {"type": "string", "enum": OPS},
                       "arg": {"type": "string"}}}}}}

PROMPT = """You split a request about a document into small steps.
Allowed ops and what arg must be:
- answer_from_text: arg is ONE specific question about the document
- summarize: arg is empty
- extract_facts: arg is empty
- classify: arg is labels separated by commas
- calc: arg is a math expression
Use as few steps as needed, at most 5. One question per answer_from_text step. Do not add steps the request did not ask for.

Example request: When was it built and how long is it? Also summarize it.
Example plan: {"steps":[{"op":"answer_from_text","arg":"When was it built?"},{"op":"answer_from_text","arg":"How long is it?"},{"op":"summarize","arg":""}]}

Request: {request}
Plan:"""

def check_plan(plan, request):
    steps = plan["steps"]
    if not 1 <= len(steps) <= 5:
        return False, "need 1 to 5 steps"
    out, seen = [], set()
    for s in steps:
        op, arg = s["op"], s["arg"].strip()
        if op == "answer_from_text":
            if len(arg) < 8:
                return False, "question too short: " + arg
            if _covered(_kw(arg), request) < 0.5:
                return False, "step not about the request: " + arg
        elif op == "classify":
            labels = [x.strip() for x in arg.split(",") if x.strip()]
            if len(labels) < 2:
                return False, "classify needs 2 or more labels"
            arg = ",".join(labels)
        elif op == "calc":
            if not re.fullmatch(r"[0-9+\-*/(). %^]+", arg):
                return False, "bad expression: " + arg
        else:
            arg = ""
        key = (op, arg.lower())
        if key in seen:
            continue
        seen.add(key)
        out.append({"op": op, "arg": arg})
    if "summar" in request.lower() and not any(s["op"] == "summarize" for s in out):
        out.append({"op": "summarize", "arg": ""})
    return True, out

def make_plan(agent, request, private=False):
    sv = servers()
    tiers = [t for t in ("fast", "main") if t in sv]
    if _cloud_allowed(private):
        tiers.append("cloud")
    trail = []
    for t in tiers:
        prompt = PROMPT.replace("{request}", request)
        if trail:
            prompt += "\n(Your previous plan was rejected: %s. Fix it.)" % trail[-1]
        r = ask(agent, prompt, private=private, force=t, schema=PLAN_SCHEMA,
                temperature=0.0, max_tokens=300)
        if r["text"] is None:
            trail.append("%s: %s" % (t, r.get("error")))
            continue
        try:
            plan = _parse(r["text"])
            errs = validate(plan, PLAN_SCHEMA)
            if errs:
                raise ValueError("; ".join(errs[:2]))
            ok, val = check_plan(plan, request)
            if not ok:
                raise ValueError(val)
        except Exception as e:
            trail.append("%s: %s" % (t, e))
            continue
        log_event(agent, "plan", input=request, output=json.dumps(val),
                  model=r.get("model", t), outcome="verified")
        return {"ok": True, "steps": val, "tier": t, "trail": trail}
    log_event(agent, "plan", input=request, output=" | ".join(trail),
              status="failed", outcome="failed")
    return {"ok": False, "reason": " | ".join(trail)}

def execute(agent, steps, text, private=False):
    budget = Budget(calls=14, seconds=1200)
    results = []
    for s in steps:
        op, arg = s["op"], s["arg"]
        if op == "answer_from_text":
            r = run(agent, op, private=private, budget=budget, text=text, question=arg)
        elif op == "classify":
            r = run(agent, op, private=private, budget=budget, text=text,
                    labels=arg.split(","))
        elif op == "calc":
            r = run(agent, op, expression=arg)
        else:
            r = run(agent, op, private=private, budget=budget, text=text)
        results.append((s, r))
    return results

def render(results):
    lines = []
    for s, r in results:
        op, arg = s["op"], s["arg"]
        if not r.get("ok"):
            lines.append("- %s: FAILED (%s)" % (arg or op, r.get("reason")))
            continue
        v = r["value"]
        if op == "answer_from_text":
            if v.get("confident"):
                lines.append('- %s %s  [source: "%s"]' % (arg, v["answer"], v["quote"]))
            else:
                lines.append("- %s unknown (not stated in the document)" % arg)
        elif op == "summarize":
            lines.append("- Summary: " + v["summary"])
        elif op == "extract_facts":
            lines.append("- Facts: " + " | ".join(f["claim"] for f in v["facts"]))
        elif op == "classify":
            lines.append("- Classification: " + v["label"])
        else:
            lines.append("- %s = %s" % (arg, v))
    return "\n".join(lines)

def solve(agent, request, text, private=False):
    p = make_plan(agent, request, private)
    if not p["ok"]:
        return {"ok": False, "reason": p["reason"]}
    results = execute(agent, p["steps"], text, private)
    good = all(r.get("ok") for _, r in results)
    log_event(agent, "task", input=request, output=render(results)[:500],
              outcome="verified" if good else "partial")
    return {"ok": True, "tier": p["tier"], "trail": p["trail"],
            "steps": p["steps"], "report": render(results)}

# ---- v2: rules first, LLM planner second, code repairs the plan ----
WH = {"when", "who", "whom", "whose", "what", "where", "why", "how", "which",
      "is", "are", "was", "were", "did", "does", "do", "can", "has", "have",
      "will"}
LEADS = ["please", "also", "then", "and", "plus", "can you", "could you",
         "would you", "tell me", "let me know", "find out", "give me",
         "i want to know", "i need to know"]
OPRE = re.compile(r"summar|overview|gist|tl;?dr|\bfacts\b|calculat|comput", re.I)
SPLIT = re.compile(r"(\?|;|\.\s|,?\s+(?:and|also|then|plus)\s+|,\s+)", re.I)

def _strip_leads(c):
    c = c.strip(" ,.;:!?")
    again = True
    while again:
        again = False
        for l in LEADS:
            if c.lower().startswith(l + " "):
                c = c[len(l):].strip(" ,")
                again = True
    return c

def _starts_clause(txt):
    t = _strip_leads(txt)
    w = re.match(r"[A-Za-z']+", t)
    return bool(w) and (w.group(0).lower() in WH or bool(OPRE.search(t)))

def clauses(request):
    parts = SPLIT.split(request.strip())
    out, cur = [], parts[0]
    for i in range(1, len(parts), 2):
        d = parts[i]
        nxt = parts[i + 1] if i + 1 < len(parts) else ""
        if d.strip() in ("?", ";", ".") or _starts_clause(nxt):
            out.append(cur)
            cur = nxt
        else:
            cur += d + nxt
    out.append(cur)
    return [c.strip() for c in out if c.strip(" ,.;:!?")]

def _clause_step(c):
    c0 = _strip_leads(c)
    low = c0.lower()
    if not c0:
        return None
    if re.search(r"summar|overview|gist|tl;?dr", low):
        return {"op": "summarize", "arg": ""}
    if "facts" in low and re.search(r"\b(list|extract|key|main|important|give)\b", low):
        return {"op": "extract_facts", "arg": ""}
    m = re.search(r"(?:calculate|compute|what is|what's)\s+(\d[\d+\-*/(). %]*\d)", low)
    if m:
        return {"op": "calc", "arg": m.group(1).strip()}
    w = re.match(r"[a-z']+", low)
    if w and w.group(0) in WH:
        q = c0[0].upper() + c0[1:]
        if not q.endswith("?"):
            q += "?"
        return {"op": "answer_from_text", "arg": q}
    return None

def rule_plan(request):
    steps, residue, seen = [], [], set()
    for c in clauses(request):
        s = _clause_step(c)
        if s is None:
            residue.append(c)
            continue
        k = (s["op"], s["arg"].lower())
        if k not in seen:
            seen.add(k)
            steps.append(s)
    return steps, residue

def _allowed(op, low):
    if op == "summarize":
        return bool(re.search(r"summar|overview|gist|tl;?dr", low))
    if op == "extract_facts":
        return "fact" in low
    if op == "classify":
        return bool(re.search(r"classif|categor|label", low))
    if op == "calc":
        return bool(re.search(r"calculat|comput|\d\s*[-+*/]\s*\d", low))
    return True

def repair(llm_steps, request):
    low = request.lower()
    rs, _ = rule_plan(request)
    pool = [s for s in llm_steps if _allowed(s["op"], low)]
    final, used = [], set()
    for r in rs:
        pick = None
        for j, s in enumerate(pool):
            if j in used or s["op"] != r["op"]:
                continue
            if r["op"] != "answer_from_text" or \
                    _covered(_kw(r["arg"]), s["arg"]) >= 0.6:
                pick = j
                break
        if pick is None:
            final.append(r)
        else:
            used.add(pick)
            final.append(pool[pick])
    for j, s in enumerate(pool):
        if j in used:
            continue
        if s["op"] == "answer_from_text" and any(
                f["op"] == "answer_from_text" and
                _covered(_kw(s["arg"]), f["arg"]) >= 0.8 for f in final):
            continue
        final.append(s)
    out, seen = [], set()
    for s in final:
        k = (s["op"], s["arg"].lower())
        if k not in seen:
            seen.add(k)
            out.append(s)
    return out[:5]

def make_plan(agent, request, private=False):
    rs, residue = rule_plan(request)
    if rs and not residue:
        log_event(agent, "plan", input=request, output=json.dumps(rs),
                  model="rules", outcome="verified")
        return {"ok": True, "steps": rs[:5], "tier": "rules", "trail": []}
    sv = servers()
    tiers = [t for t in ("fast", "main") if t in sv]
    if _cloud_allowed(private):
        tiers.append("cloud")
    trail = []
    for t in tiers:
        prompt = PROMPT.replace("{request}", request)
        if trail:
            prompt += "\n(Your previous plan was rejected: %s. Fix it.)" % trail[-1]
        r = ask(agent, prompt, private=private, force=t, schema=PLAN_SCHEMA,
                temperature=0.0, max_tokens=300)
        if r["text"] is None:
            trail.append("%s: %s" % (t, r.get("error")))
            continue
        try:
            plan = _parse(r["text"])
            errs = validate(plan, PLAN_SCHEMA)
            if errs:
                raise ValueError("; ".join(errs[:2]))
            ok, val = check_plan(plan, request)
            if not ok:
                raise ValueError(val)
            val = repair(val, request)
            if not val:
                raise ValueError("plan empty after repair")
        except Exception as e:
            trail.append("%s: %s" % (t, e))
            continue
        log_event(agent, "plan", input=request, output=json.dumps(val),
                  model=r.get("model", t), outcome="verified")
        return {"ok": True, "steps": val, "tier": t, "trail": trail}
    if rs:
        log_event(agent, "plan", input=request, output=json.dumps(rs),
                  model="rules-partial", outcome="partial")
        return {"ok": True, "steps": rs[:5], "tier": "rules-partial",
                "trail": trail, "ignored": residue}
    log_event(agent, "plan", input=request, output=" | ".join(trail),
              status="failed", outcome="failed")
    return {"ok": False, "reason": " | ".join(trail)}

def solve(agent, request, text, private=False):
    p = make_plan(agent, request, private)
    if not p["ok"]:
        return {"ok": False, "reason": p["reason"]}
    results = execute(agent, p["steps"], text, private)
    report = render(results)
    if p.get("ignored"):
        report += "\n- NOT HANDLED: " + "; ".join(p["ignored"])
    good = all(r.get("ok") for _, r in results) and not p.get("ignored")
    log_event(agent, "task", input=request, output=report[:500],
              outcome="verified" if good else "partial")
    return {"ok": True, "tier": p["tier"], "trail": p["trail"],
            "steps": p["steps"], "report": report}

# ---- v3: plan skills and fact memory ----
from engine import skills

_plan_v2 = make_plan

def make_plan(agent, request, private=False):
    s = skills.find_skill(request)
    if s:
        log_event(agent, "plan", input=request, output=json.dumps(s["steps"]),
                  model="skill", outcome="verified")
        return {"ok": True, "steps": s["steps"], "tier": "skill",
                "trail": [], "skill_sig": s["sig"]}
    return _plan_v2(agent, request, private)

def execute(agent, steps, text, private=False, source="user-text"):
    budget = Budget(calls=14, seconds=1200)
    results = []
    for s in steps:
        op, arg = s["op"], s["arg"]
        if op == "answer_from_text" and not text:
            hit = skills.recall(arg)
            if hit:
                note = " (from memory: %s, %d days old%s)" % (
                    hit["source"], hit["age_days"],
                    ", may be outdated" if hit["age_days"] > 30 else "")
                r = {"ok": True, "source": "memory",
                     "value": {"answer": hit["answer"] + note,
                               "quote": hit["quote"], "confident": True}}
            else:
                r = {"ok": False, "reason": "no document given and not in memory"}
        elif op in ("summarize", "extract_facts", "classify") and not text:
            r = {"ok": False, "reason": "needs a document"}
        elif op == "answer_from_text":
            r = run(agent, op, private=private, budget=budget, text=text, question=arg)
            v = r.get("value") or {}
            if r.get("ok") and v.get("confident"):
                skills.remember(arg, v["answer"], v["quote"], source)
        elif op == "classify":
            r = run(agent, op, private=private, budget=budget, text=text,
                    labels=arg.split(","))
        elif op == "calc":
            r = run(agent, op, expression=arg)
        else:
            r = run(agent, op, private=private, budget=budget, text=text)
        results.append((s, r))
    return results

def solve(agent, request, text=None, private=False, source="user-text"):
    p = make_plan(agent, request, private)
    if not p["ok"]:
        return {"ok": False, "reason": p["reason"]}
    results = execute(agent, p["steps"], text, private, source)
    report = render(results)
    if p.get("ignored"):
        report += "\n- NOT HANDLED: " + "; ".join(p["ignored"])
    good = all(r.get("ok") for _, r in results) and not p.get("ignored")
    if p["tier"] not in ("rules", "rules-partial"):
        skills.record(request, p["steps"], good, p.get("skill_sig"))
    log_event(agent, "task", input=request, output=report[:500],
              outcome="verified" if good else "partial")
    return {"ok": True, "tier": p["tier"], "trail": p["trail"],
            "steps": p["steps"], "report": report}

# ---- ANN student: handles clauses the rules cannot parse ----
try:
    from engine import ann as _ann
except Exception:
    _ann = None

_clause_step_rules = _clause_step

def _clause_step(c):
    s = _clause_step_rules(c)
    if s is not None or _ann is None:
        return s
    try:
        return _ann.step_from_clause(c)
    except Exception:
        return None
