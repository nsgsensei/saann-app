"""Starts the llama-server model servers on demand."""
import os, shutil, subprocess, sys, threading, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVERS = {
    "main": {"port": 8080, "model": "qwen-1.5b.gguf", "args": ["-c", "4096", "-t", "4"]},
    "fast": {"port": 8081, "model": "qwen-0.5b.gguf", "args": ["-c", "2048", "-t", "2"]},
}
_lock = threading.Lock()
_started = {}
_gave_up = 0.0

def up(port):
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=1.0) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False

def _launch(name):
    s = SERVERS[name]
    exe = shutil.which("llama-server")
    model = ROOT / "models" / s["model"]
    if not exe or not model.is_file():
        return False
    (ROOT / "data").mkdir(parents=True, exist_ok=True)
    log = open(ROOT / "data" / (name + ".log"), "ab")
    subprocess.Popen([exe, "-m", str(model), "--port", str(s["port"])] + s["args"],
                     stdout=log, stderr=log, stdin=subprocess.DEVNULL, start_new_session=True)
    _started[name] = time.time()
    return True

def ensure(timeout=120):
    """Make sure both servers are up. Returns True if main is answering."""
    global _gave_up
    if up(SERVERS["main"]["port"]):
        return True
    with _lock:
        if time.time() - _gave_up < 60:
            return False
        if shutil.which("termux-wake-lock"):
            subprocess.run(["termux-wake-lock"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        launched = False
        for name, s in SERVERS.items():
            if up(s["port"]) or time.time() - _started.get(name, 0) < 120:
                launched = launched or name in _started
                continue
            if _launch(name):
                launched = True
                sys.stderr.write("[servers] starting %s model...\n" % name)
        if not launched and not _started:
            _gave_up = time.time()
            return False
        end = time.time() + timeout
        while time.time() < end:
            if up(SERVERS["main"]["port"]):
                return True
            time.sleep(1.5)
        _gave_up = time.time()
        return False

def status():
    return "\n".join("%s (port %d): %s" % (n, s["port"], "up" if up(s["port"]) else
                     ("loading" if time.time() - _started.get(n, 0) < 120 else "down"))
                     for n, s in SERVERS.items())

def stop():
    subprocess.run(["pkill", "llama-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    _started.clear()
    return "model servers stopped"

def command(arg):
    arg = arg.strip().lower()
    if arg == "start":
        return ("servers ready\n" if ensure() else "main server not ready, check data/main.log\n") + status()
    if arg == "stop":
        return stop()
    return status() + "\n/servers start | stop"
