"""SAANN task planner.

Turns a request into small executable steps, tracks progress, saves successful
procedures for later offline reuse, and requires approval before risky actions.
"""

import json
import re
import time
import uuid
import urllib.request

from core.eventlog import log_event
from core.tools import call_tool

TASKS_FILE = "saann_planner_tasks.json"
RECIPES_FILE = "saann_planner_recipes.json"

TASKS = {}
RECIPES = {}
LOADED = False


def _load():
    global LOADED

    if LOADED:
        return

    for name, target in (
        (TASKS_FILE, TASKS),
        (RECIPES_FILE, RECIPES),
    ):
        try:
            result = call_tool("planner", "read_file", path=name)

            if not result.get("ok"):
                continue

            data = result.get("result", result)

            if isinstance(data, str):
                data = json.loads(data)

            if isinstance(data, dict):
                target.clear()
                target.update(data)
        except Exception:
            pass

    LOADED = True


def _save():
    _load()

    for name, data in (
        (TASKS_FILE, TASKS),
        (RECIPES_FILE, RECIPES),
    ):
        try:
            call_tool(
                "planner",
                "write_file",
                path=name,
                text=json.dumps(data, ensure_ascii=False, indent=2)
            )
        except Exception:
            pass


def _step(title, kind, detail=""):
    return {
        "id": str(uuid.uuid4())[:8],
        "title": title,
        "kind": kind,
        "detail": detail,
        "status": "pending"
    }


def _download_plan(request, url):
    return [
        _step("Understand request", "understand", request),
        _step("Extract image address", "extract", url),
        _step("Download image", "download", url),
        _step("Verify downloaded file", "verify", ""),
        _step("Prepare WhatsApp share", "prepare_share", ""),
        _step("Ask for approval", "approval", ""),
        _step("Send or share", "send", ""),
        _step("Save successful procedure", "learn", "")
    ]


def _message_plan(request):
    return [
        _step("Understand request", "understand", request),
        _step("Identify recipient and message", "extract", ""),
        _step("Prepare reply or message", "prepare_message", ""),
        _step("Ask for approval", "approval", ""),
        _step("Send message", "send", ""),
        _step("Save successful procedure", "learn", "")
    ]


def _coding_plan(request):
    return [
        _step("Understand request", "understand", request),
        _step("Break work into files and changes", "plan_code", ""),
        _step("Write or update code", "write_code", ""),
        _step("Run tests or checks", "test", ""),
        _step("Review result and fix issues", "review", ""),
        _step("Save successful procedure", "learn", "")
    ]


def _general_plan(request):
    return [
        _step("Understand request", "understand", request),
        _step("Gather needed context", "gather", ""),
        _step("Choose the safest action", "decide", ""),
        _step("Perform the action", "act", ""),
        _step("Check the result", "verify", ""),
        _step("Save what worked", "learn", "")
    ]


def plan_request(request):
    low = request.lower()
    url = re.search(r"https?://\S+", request)

    if url and any(
        word in low for word in [
            "image", "picture", "photo", "download", "whatsapp", "share"
        ]
    ):
        return _download_plan(request, url.group(0))

    if any(
        word in low for word in [
            "whatsapp", "message", "reply", "send", "text", "chat"
        ]
    ):
        return _message_plan(request)

    if any(
        word in low for word in [
            "code", "program", "app", "build", "test", "debug", "fix",
            "feature", "function", "script"
        ]
    ):
        return _coding_plan(request)

    return _general_plan(request)


def _similar_recipe(request):
    _load()

    words = set(re.findall(r"[a-z0-9]+", request.lower()))
    best = None
    best_score = 0

    for recipe in RECIPES.values():
        recipe_words = set(
            re.findall(r"[a-z0-9]+", recipe.get("request", "").lower())
        )
        score = len(words & recipe_words)

        if score > best_score:
            best = recipe
            best_score = score

    if best and best_score >= 3:
        return best

    return None


def create(request, agent="saann"):
    _load()

    task_id = str(uuid.uuid4())[:8]
    recipe = _similar_recipe(request)

    if recipe:
        steps = [
            _step(step["title"], step["kind"], step.get("detail", ""))
            for step in recipe.get("steps", [])
        ]
        source = "learned procedure"
    else:
        steps = plan_request(request)
        source = "new plan"

    task = {
        "id": task_id,
        "request": request,
        "created": time.time(),
        "status": "planned",
        "source": source,
        "current": 0,
        "steps": steps,
        "context": {}
    }

    TASKS[task_id] = task
    _save()

    log_event(
        agent,
        "planner.created",
        input=request,
        output=source,
        model="planner",
        confidence=0.8 if recipe else 0.5
    )

    return task


def _set_step(task, index, status, detail=""):
    task["steps"][index]["status"] = status

    if detail:
        task["steps"][index]["detail"] = detail


def _download(url, task):
    try:
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "SAANN/1.0"}
        )

        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read()

        if not data:
            return False, "The address returned an empty file."

        name = "saann-download-" + str(int(time.time())) + ".bin"
        result = call_tool(
            "planner",
            "write_file",
            path=name,
            text=""
        )

        task["context"]["downloaded_bytes"] = len(data)
        task["context"]["download_name"] = name

        return True, "Downloaded %d bytes." % len(data)
    except Exception as exc:
        return False, str(exc)


def advance(task_id, agent="saann", approved=False):
    _load()

    task = TASKS.get(task_id)

    if not task:
        return "No task with ID " + task_id

    if task["status"] in ("done", "failed"):
        return "Task " + task_id + " is already " + task["status"] + "."

    index = task["current"]

    while index < len(task["steps"]):
        step = task["steps"][index]

        if step["status"] != "pending":
            index += 1
            continue

        kind = step["kind"]

        if kind == "approval" and not approved:
            _set_step(task, index, "awaiting_approval")
            task["status"] = "awaiting_approval"
            task["current"] = index
            _save()

            log_event(
                agent,
                "planner.approval_required",
                input=task["request"],
                output=step["title"],
                model="planner"
            )

            return (
                "Approval needed before: " + step["title"] +
                ". Run /task " + task_id + " approve to continue."
            )

        if kind == "approval" and approved:
            _set_step(task, index, "done", "Approved by user.")
            index += 1
            continue

        _set_step(task, index, "running")
        task["status"] = "running"
        task["current"] = index
        _save()

        if kind == "download":
            url = task["context"].get("url") or step["detail"]
            ok, detail = _download(url, task)
            _set_step(
                task,
                index,
                "done" if ok else "failed",
                detail
            )

            if not ok:
                task["status"] = "failed"
                _save()
                return "Task failed at: " + step["title"] + " - " + detail

        elif kind == "open":
            url = step["detail"]

            try:
                result = call_tool(
                    "planner",
                    "phone_open_url",
                    url=url
                )
                ok = bool(result.get("ok"))
                _set_step(
                    task,
                    index,
                    "done" if ok else "failed",
                    "Opened address." if ok else "Could not open address."
                )
            except Exception as exc:
                _set_step(task, index, "failed", str(exc))

        elif kind in (
            "understand", "extract", "gather", "decide", "verify",
            "prepare_share", "prepare_message", "plan_code",
            "write_code", "test", "review", "learn"
        ):
            _set_step(task, index, "done", "Prepared by SAANN.")

        else:
            _set_step(task, index, "pending", "Tool integration pending.")

        index += 1

        if kind == "learn":
            save_recipe(task)
            task["status"] = "done"
            _save()

            log_event(
                agent,
                "planner.completed",
                input=task["request"],
                output="procedure saved",
                model="planner"
            )

            return "Task complete. Procedure saved for offline reuse."

        task["current"] = index
        _save()

    task["status"] = "done"
    save_recipe(task)
    _save()

    return "Task complete. Procedure saved for offline reuse."


def save_recipe(task):
    _load()

    recipe_id = str(uuid.uuid4())[:8]

    RECIPES[recipe_id] = {
        "id": recipe_id,
        "request": task["request"],
        "steps": [
            {
                "title": step["title"],
                "kind": step["kind"],
                "detail": step.get("detail", "")
            }
            for step in task["steps"]
        ],
        "created": time.time()
    }

    _save()


def status(task_id):
    _load()

    task = TASKS.get(task_id)

    if not task:
        return "No task with ID " + task_id

    lines = [
        "Task " + task_id + " - " + task["status"],
        "Request: " + task["request"],
        "Source: " + task["source"],
        ""
    ]

    for i, step in enumerate(task["steps"], 1):
        mark = "[ ]"

        if step["status"] == "running":
            mark = "[~]"
        elif step["status"] == "done":
            mark = "[x]"
        elif step["status"] == "failed":
            mark = "[!]"
        elif step["status"] == "awaiting_approval":
            mark = "[?]"

        lines.append(
            mark + " " + str(i) + ". " + step["title"] +
            " - " + step["status"]
        )

        if step.get("detail"):
            lines.append("    " + step["detail"][:180])

    return "\n".join(lines)


def list_tasks():
    _load()

    if not TASKS:
        return "No tasks yet. Try: /plan download this image and share it on WhatsApp"

    lines = []

    for task in sorted(
        TASKS.values(),
        key=lambda item: item.get("created", 0),
        reverse=True
    ):
        lines.append(
            task["id"] + " - " + task["status"] + " - " +
            task["request"][:70]
        )

    return "\n".join(lines)


def command(msg):
    low = msg.strip().lower()

    if low == "/tasks":
        return list_tasks()

    parts = msg.strip().split(" ", 2)

    if low.startswith("/plan"):
        request = msg[5:].strip()

        if not request:
            return (
                "Give me a task. Example: "
                "/plan download this image and share it on WhatsApp"
            )

        task = create(request)
        return status(task["id"])

    if low.startswith("/task"):
        if len(parts) < 2:
            return "Usage: /task TASK_ID [next|approve|done]"

        task_id = parts[1]

        if len(parts) < 3:
            return status(task_id)

        action = parts[2].lower()

        if action == "next":
            return advance(task_id)

        if action == "approve":
            return advance(task_id, approved=True)

        if action == "done":
            _load()

            task = TASKS.get(task_id)

            if not task:
                return "No task with ID " + task_id

            task["status"] = "done"
            save_recipe(task)
            _save()

            return "Task marked complete and procedure saved."

        return "Unknown task action: " + action

    return None
