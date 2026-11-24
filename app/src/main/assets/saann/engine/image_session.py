"""Image operations scoped to the history supplied for this request."""
import json
import re
from pathlib import Path

EDIT = re.compile(
    r"^(?:please\s+)?(?:"
    r"(?:edit|retouch|recolor)\s+(?:it|this|that|the image|the photo)\b|"
    r"make\s+(?:it|this image|that image|the image|the photo)\s+"
    r"(?:darker|brighter|lighter|sharper|blurry|black and white|"
    r"more colorful|less colorful)\b|"
    r"(?:remove|replace|blur|change)\s+.+\s+"
    r"(?:from|in|on)\s+(?:it|this image|that image|the image|the photo)\b"
    r")", re.I
)
ATTACHED_EDIT = re.compile(
    r"^(?:please\s+)?(?:edit|modify|retouch|recolor|remove|replace|"
    r"crop|blur|brighten|darken)\b", re.I
)
SEE = re.compile(
    r"^(?:please\s+)?(?:"
    r"describe (?:it|this image|that image|the image|the photo)|"
    r"what(?:'s| is) in (?:it|this image|that image|the image|the photo)|"
    r"read the text (?:in|on) (?:it|this image|that image|the image|the photo)"
    r")[.?!]*$", re.I
)


def _history_image(history):
    try:
        rows = json.loads(history or "[]")
    except (ValueError, TypeError):
        return None, "Chat image history could not be read. Attach the image again."

    if not isinstance(rows, list):
        return None, "Attach the image you want to use."

    for row in reversed(rows):
        if not isinstance(row, dict):
            continue

        paths = []
        images = row.get("images")
        if isinstance(images, list):
            paths.extend(p for p in images if isinstance(p, str))

        attachments = row.get("attachments")
        if isinstance(attachments, list):
            paths.extend(
                a["path"] for a in attachments
                if isinstance(a, dict)
                and a.get("kind") == "image"
                and isinstance(a.get("path"), str)
            )

        paths = list(dict.fromkeys(paths))
        if not paths:
            continue
        if len(paths) != 1:
            return None, (
                "There are multiple images in the most recent image message. "
                "Attach just the one you want to use."
            )
        if not Path(paths[0]).is_file():
            return None, "That conversation image is missing. Attach it again."
        return paths[0], None

    return None, "Attach the image you want me to analyze or edit."


def _step(phase, label, ok, result=""):
    try:
        from engine import agent
        callback = getattr(agent, "STEP", None)
        if callable(callback):
            callback(phase, label, {}, ok, result)
    except Exception:
        pass


def _perform(kind, path, prompt):
    from engine import media

    label = {
        "generate": "Generate image",
        "edit": "Edit image",
        "see": "Analyze image"
    }[kind]
    _step("start", label, True)

    try:
        if kind == "generate":
            output, error = media.generate_image(prompt)
        elif kind == "edit":
            output, error = media.edit_image(path, prompt)
        else:
            if not media.online_ok():
                _step("end", label, False, "Online image processing is off")
                return "Online image processing is disabled. Enable it in Setup."
            output, error = media.see(path, prompt or None)

        if not output:
            _step("end", label, False, "No usable result")
            return error or "The provider returned no usable image result."

        if kind == "see":
            _step("end", label, True, "Analysis received")
            return output

        if not Path(output).is_file():
            _step("end", label, False, "Output file missing")
            return "The image result could not be saved. Please try again."

        _step("end", label, True, "Image received and saved")
        if kind == "edit":
            return (
                "Here is the edited image. The source image is unchanged.\n"
                "[image: %s]\nSaved in your Library." % output
            )
        return "Here it is.\n[image: %s]\nSaved in your Library." % output

    except Exception:
        _step("end", label, False, "Image operation failed")
        return (
            "The image operation failed. No result is being claimed. "
            "If editing, your source image is unchanged."
        )


def plan(message, history=None):
    """Return a deferred operation, or None for normal chat."""
    from engine import media

    text = str(message).strip()
    attachment = media.ATT.search(text)

    if attachment:
        kind, name, path = attachment.groups()
        if kind != "image":
            return None
        question = text[:attachment.start()].strip()
        if question.lower().startswith("look at the attachment"):
            question = ""
        operation = (
            "edit"
            if ATTACHED_EDIT.match(question) or EDIT.match(question)
            else "see"
        )
        return lambda: _perform(operation, path, question)

    command = text.split(None, 1)
    first = command[0].lower() if command else ""
    rest = command[1].strip() if len(command) > 1 else ""

    if first == "/image":
        if not rest:
            return lambda: "Describe the image you want to generate."
        return lambda: _perform("generate", None, rest)

    drawing = media.DRAW.match(text.rstrip(".!?"))
    if drawing:
        return lambda: _perform("generate", None, drawing.group(1))

    # SAANN_IMAGE_CLARIFY_V2
    ambiguous = re.fullmatch(
        r"(?:please\s+)?(?:remove that|remove it|change that|"
        r"change it|fix it|edit it|make it better)[.!?]*",
        text, re.I
    )
    if ambiguous:
        path, error = _history_image(history)
        if path is not None:
            return lambda: (
                "What exactly should I change in the image? "
                "Describe the object, area, or adjustment."
            )
        if error and (
            "multiple images" in error.lower()
            or "missing" in error.lower()
        ):
            return lambda: error

    editing = first == "/edit" or bool(EDIT.match(text))
    seeing = first == "/see" or bool(SEE.fullmatch(text))
    if not editing and not seeing:
        return None

    prompt = rest if first in ("/edit", "/see") else text
    if editing and not prompt:
        return lambda: "Describe what you want changed in the image."

    path, error = _history_image(history)
    if error:
        return lambda: error

    operation = "edit" if editing else "see"
    return lambda: _perform(operation, path, prompt)
