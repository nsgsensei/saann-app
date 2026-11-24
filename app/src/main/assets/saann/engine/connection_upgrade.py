"""Account-aware model discovery for interactive setup."""
import hashlib
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from engine import cloud

_lock = threading.RLock()
_cache = {}

FALLBACKS = {
    "gemini": [
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-flash-latest",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash"
    ],
    "groq": [
        "openai/gpt-oss-20b",
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant"
    ]
}


def _get(url, headers):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


# SAANN_PROVIDER_RECOVERY_V4
def _error(exc):
    if isinstance(exc, urllib.error.HTTPError):
        return {
            400: (
                "HTTP 400: the request or model settings were rejected. "
                "This does not by itself mean the key is invalid."
            ),
            401: (
                "HTTP 401: authentication failed. Copy the complete API "
                "credential from this provider's official console. Check "
                "whether it was revoked or expired. Never use your account "
                "password as an API key."
            ),
            403: (
                "HTTP 403: access was denied. Check the credential's "
                "permissions, project/account access, and any provider "
                "restrictions. Repeated retries will not grant access."
            ),
            404: (
                "HTTP 404: the model or endpoint was not found. "
                "SAANN can try another compatible model."
            ),
            408: (
                "HTTP 408: the request timed out. Check connectivity "
                "and try again shortly."
            ),
            429: (
                "HTTP 429: a usage or rate limit was reached. "
                "Wait before retrying and check your provider's quota "
                "dashboard. SAANN will not enable billing automatically."
            ),
            500: (
                "HTTP 500: the provider encountered a server error. "
                "Try again later."
            ),
            502: (
                "HTTP 502: the provider gateway failed. Try again later."
            ),
            503: (
                "HTTP 503: the provider is temporarily unavailable. "
                "Try again later."
            ),
            504: (
                "HTTP 504: the provider gateway timed out. Try again later."
            ),
        }.get(
            exc.code,
            "Provider request failed (HTTP %s). "
            "Check the provider's service status and account console."
            % exc.code
        )

    if isinstance(exc, (TimeoutError, urllib.error.URLError)):
        return (
            "The provider could not be reached. Check your internet "
            "connection and device date/time, then retry. "
            "This does not establish that the key is invalid."
        )

    return (
        "The provider response could not be processed. "
        "This may be a response-format or compatibility issue, "
        "not an invalid key."
    )


def _models(name, key):
    if name == "groq":
        data = _get(
            "https://api.groq.com/openai/v1/models",
            {"Authorization": "Bearer " + key}
        )
        models = [
            item["id"] for item in data.get("data", [])
            if item.get("id")
        ]
        blocked = (
            "whisper", "orpheus", "guard",
            "safeguard", "embedding"
        )
        models = [
            model for model in models
            if not any(word in model.lower() for word in blocked)
        ]
        def rank(model):
            if model == "openai/gpt-oss-20b":
                return (0, model)
            if model == "openai/gpt-oss-120b":
                return (1, model)
            return (2, model)
    else:
        models = []
        token = ""
        for _ in range(10):
            url = (
                "https://generativelanguage.googleapis.com/v1beta/models"
                "?pageSize=1000"
            )
            if token:
                url += "&pageToken=" + urllib.parse.quote(token, safe="")
            data = _get(url, {"x-goog-api-key": key})
            for item in data.get("models", []):
                model = item.get("name", "").removeprefix("models/")
                if (
                    model.startswith("gemini-")
                    and "generateContent" in item.get(
                        "supportedGenerationMethods", []
                    )
                    and not any(word in model.lower() for word in (
                        "tts", "image", "live", "audio", "robotics"
                    ))
                ):
                    models.append(model)
            token = data.get("nextPageToken", "")
            if not token:
                break

        def rank(model):
            value = model.lower()
            return (
                0 if "flash" in value else 1,
                1 if "preview" in value or "exp" in value else 0,
                model
            )

    return sorted(set(models), key=rank)


def test(name):
    key = cloud.keys().get(name)
    if not key:
        return "no key saved yet"

    fingerprint = hashlib.sha256(
        (name + "\0" + key + "\0" +
         cloud.keys().get("cloudflare_account", "") +
         "\0" + cloud.model_of(name)).encode()
    ).hexdigest()

    with _lock:
        cached = _cache.get(fingerprint)
        if cached and time.monotonic() - cached[0] < 300:
            return cached[1]

        if name not in ("groq", "gemini"):
            try:
                text, mid = cloud._call(
                    name, key, "Reply with ready.",
                    128, None, 0.0, "Reply briefly."
                )
                if not text:
                    return "provider returned no final text"
                _clear_cooldown(name)
                result = "working (%s)" % mid
                _cache[fingerprint] = (time.monotonic(), result)
                return result
            except Exception as exc:
                return _error(exc)

        discovery_error = ""

        try:
            candidates = _models(name, key)
        except Exception as exc:
            discovery_error = _error(exc)

            # Do not turn denied access or rate limiting into a retry storm.
            if isinstance(exc, urllib.error.HTTPError):
                if exc.code in (401, 403, 429):
                    return name + " model discovery: " + discovery_error

            candidates = list(FALLBACKS.get(name, []))
            if not candidates:
                return name + " model discovery: " + discovery_error

        if not candidates:
            return "key accepted, but no suitable text models were listed"

        state = cloud.state()
        previous = state["models"].get(name)
        if previous in candidates:
            candidates.remove(previous)
            candidates.insert(0, previous)

        errors = []
        for model in candidates[:8]:
            try:
                state = cloud.state()
                state["models"][name] = model
                cloud._save_state(state)

                text, mid = cloud._call(
                    name, key, "Reply with the single word ready.",
                    1024, None, 0.0, "Reply briefly."
                )
                if not text:
                    errors.append(model + ": no final text")
                    continue

                _clear_cooldown(name)
                result = "working (%s)" % mid

                new_fingerprint = hashlib.sha256(
                    (name + "\0" + key + "\0" +
                     cloud.keys().get("cloudflare_account", "") +
                     "\0" + cloud.model_of(name)).encode()
                ).hexdigest()
                _cache[new_fingerprint] = (time.monotonic(), result)
                return result

            except Exception as exc:
                errors.append(model + ": " + _error(exc))
                if isinstance(exc, urllib.error.HTTPError):
                    if exc.code in (401, 403, 429, 500, 502, 503, 504):
                        break

        state = cloud.state()
        if previous is None:
            state["models"].pop(name, None)
        else:
            state["models"][name] = previous
        cloud._save_state(state)

        if not errors and discovery_error:
            errors.append(
                "model discovery was denied: " + discovery_error
            )

        return (
            "; ".join(errors) or
            "no working text model found"
        )


def _clear_cooldown(name):
    state = cloud.state()
    state["cool"].pop(name, None)
    cloud._save_state(state)


def install():
    cloud.test = test
