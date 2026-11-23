"""SAANN skill memory: remembers successful phone procedures for reuse."""

import json
import re
import time

from core.tools import call_tool

SKILLS_FILE = "saann_agent_skills.json"
SKILLS = {}
LOADED = False


def _load():
    global LOADED

    if LOADED:
        return

    try:
        result = call_tool("skills", "read_file", path=SKILLS_FILE)

        if result.get("ok"):
            data = result.get("result", result)

            if isinstance(data, str):
                data = json.loads(data)

            if isinstance(data, dict):
                SKILLS.clear()
                SKILLS.update(data)
    except Exception:
        pass

    LOADED = True


def _save():
    _load()

    try:
        call_tool(
            "skills",
            "write_file",
            path=SKILLS_FILE,
            text=json.dumps(SKILLS, ensure_ascii=False, indent=2)
        )
    except Exception:
        pass


def _words(text):
    return set(re.findall(r"[a-z0-9]+", (text or "").lower()))


def find(goal):
    _load()

    target = _words(goal)
    best = None
    best_score = 0

    for skill in SKILLS.values():
        score = len(target & _words(skill.get("goal", "")))

        if score > best_score:
            best = skill
            best_score = score

    if best and best_score >= 2:
        return best

    return None


def context(goal, limit=3):
    _load()

    target = _words(goal)
    scored = []

    for skill in SKILLS.values():
        score = len(target & _words(skill.get("goal", "")))

        if score:
            scored.append((score, skill))

    scored.sort(reverse=True, key=lambda item: item[0])

    lines = []

    for score, skill in scored[:limit]:
        steps = []

        for step in skill.get("steps", [])[-8:]:
            steps.append(
                "- " + step.get("tool", "?") + "(" +
                json.dumps(step.get("args", {}), ensure_ascii=False) + ")"
            )

        lines.append(
            "Goal: " + skill.get("goal", "") + "\n" + "\n".join(steps)
        )

    return "\n\n".join(lines)


def save(goal, steps):
    _load()

    if not steps:
        return

    skill_id = str(int(time.time() * 1000))[-12:]
    clean_steps = []

    for step in steps[-20:]:
        clean_steps.append(
            {
                "tool": step.get("tool", ""),
                "args": step.get("args", {}),
                "ok": bool(step.get("ok"))
            }
        )

    SKILLS[skill_id] = {
        "id": skill_id,
        "goal": goal,
        "steps": clean_steps,
        "created": time.time()
    }

    _save()
