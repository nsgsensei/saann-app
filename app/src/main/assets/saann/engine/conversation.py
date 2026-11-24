"""Session-aware conversation boundary for the Android app."""
import json
import threading

_lock = threading.RLock()
_local = threading.local()
_record = None
_states = {}


def _install():
    global _record
    from engine import brain

    if _record is not None:
        return

    _record = brain.note_turn

    def note_turn(message, answer):
        if getattr(_local, "active", False):
            return None
        return _record(message, answer)

    brain.note_turn = note_turn


def _restore_history(history):
    from engine import brain

    rows = json.loads(history or "[]")
    if not isinstance(rows, list):
        raise ValueError("Chat history must be a list")

    # The final user row is the request currently being processed.
    if rows and isinstance(rows[-1], dict):
        if rows[-1].get("m") is True:
            rows = rows[:-1]

    brain._TURNS.clear()
    pending = None

    for row in rows[-48:]:
        if not isinstance(row, dict):
            continue

        text = row.get("t")
        if not isinstance(text, str):
            continue

        if row.get("m") is True:
            pending = text
        elif row.get("m") is False and pending is not None:
            from engine.research import memory_text
            _record(memory_text(pending), text)
            pending = None


def run(message, responder, session_id=None, history=None):
    text = str(message)

    with _lock:
        _install()

        from engine import brain
        import saann
        import app_main

        if history is not None:
            _restore_history(history)
        else:
            brain._TURNS.clear()

        previous_state = app_main._state

        if session_id:
            state = _states.get(session_id)
            if state is None:
                state = saann.State()
                _states[session_id] = state

                if len(_states) > 64:
                    oldest = next(iter(_states))
                    if oldest != session_id:
                        del _states[oldest]

            app_main._state = state
        else:
            app_main._state = saann.State()

        # SAANN_RESEARCH_ROUTING_V8
        # SAANN_NATURAL_ROUTING_V9
        from engine import research, natural_route
        # SAANN_SESSION_IMAGES_V1
        from engine import image_session
        image_job = image_session.plan(text, history)
        decision = (
            {"route": "reply", "query": "", "question": ""}
            if image_job is not None
            else natural_route.decide(text)
        )
        research_query = (
            decision.get("query")
            if decision.get("route") == "research"
            else None
        )

        _local.active = True
        try:
            if not text.lstrip().startswith("/"):
                brain.think_text(text)

            if image_job is not None:
                answer = image_job()
            elif decision.get("route") == "clarify":
                answer = decision["question"]
            elif research_query:
                answer = research.answer(research_query)
            else:
                answer = responder(message)
            if answer is None:
                answer = (
                    "I could not produce a response. Please try again."
                )
        finally:
            _local.active = False
            app_main._state = previous_state

        if research_query or not text.lstrip().startswith("/"):
            _record(research.memory_text(text), str(answer))

        return answer
