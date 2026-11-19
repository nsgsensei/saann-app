"""Screen control through the Android Accessibility Service (app only)."""
from engine import phone
from engine.phone import ptool, _text

@ptool("phone_a11y_settings", "phone.write", "write", {},
       "open Android accessibility settings so the user can turn SAANN on")
def a11y_settings():
    phone._run(["saann-a11y-settings"])
    return "opened"

@ptool("phone_open_app", "phone.write", "write", {"app": "string"},
       "open an installed app by its name")
def open_app(app):
    return phone._run(["saann-open-app", _text(app, 60)])

@ptool("screen_read", "phone.screen", "read", {},
       "list the visible text, buttons and input fields on the current screen", private=True)
def screen_read():
    return phone._run(["saann-screen-read"])[:3000]

@ptool("screen_click", "phone.screen", "sensitive", {"label": "string"},
       "tap the button or item that shows this exact visible text")
def screen_click(label):
    return phone._run(["saann-screen-click", _text(label, 80)])

@ptool("screen_type", "phone.screen", "sensitive", {"text": "string"},
       "type text into the focused input field")
def screen_type(text):
    return phone._run(["saann-screen-type", _text(text, 500)])

@ptool("screen_swipe", "phone.screen", "write", {"direction": "string"},
       "swipe up, down, left or right (swipe up scrolls the page down)")
def screen_swipe(direction):
    if direction not in ("up", "down", "left", "right"):
        raise ValueError("direction must be up, down, left or right")
    return phone._run(["saann-screen-swipe", direction])

@ptool("screen_button", "phone.screen", "write", {"button": "string"},
       "press back, home, recents or notifications")
def screen_button(button):
    if button not in ("back", "home", "recents", "notifications"):
        raise ValueError("button must be back, home, recents or notifications")
    return phone._run(["saann-screen-button", button])

# ---------------- upgrade: see, capture, precise touch ----------------
import time

def _n(v, lo=0, hi=10000):
    v = int(v)
    if not lo <= v <= hi:
        raise ValueError("number %d is out of range %d to %d" % (v, lo, hi))
    return v

@ptool("screen_info", "phone.screen", "read", {}, "screen size in pixels and the app now in front")
def screen_info():
    return phone._run(["saann-screen-info"])

@ptool("screen_shot", "phone.screen", "read", {}, "take a screenshot and save it as an image file", private=True)
def screen_shot():
    return phone._run(["saann-screen-shot"])

@ptool("screen_look", "phone.screen", "read", {},
       "screenshot the screen and read ALL text in it with pixel positions; use when screen_read shows little",
       private=True)
def screen_look():
    return phone._run(["saann-screen-look"])[:3000]

@ptool("screen_tap", "phone.screen", "sensitive", {"x": "integer", "y": "integer"}, "tap at a pixel position")
def screen_tap(x, y):
    return phone._run(["saann-screen-tap", "%d,%d" % (_n(x), _n(y))])

@ptool("screen_double_tap", "phone.screen", "sensitive", {"x": "integer", "y": "integer"},
       "double tap at a pixel position")
def screen_double_tap(x, y):
    return phone._run(["saann-screen-double-tap", "%d,%d" % (_n(x), _n(y))])

@ptool("screen_long_press", "phone.screen", "sensitive", {"x": "integer", "y": "integer"},
       "press and hold at a pixel position")
def screen_long_press(x, y):
    return phone._run(["saann-screen-long-press", "%d,%d" % (_n(x), _n(y))])

@ptool("screen_drag", "phone.screen", "write",
       {"x1": "integer", "y1": "integer", "x2": "integer", "y2": "integer"},
       "drag from one pixel position to another")
def screen_drag(x1, y1, x2, y2):
    return phone._run(["saann-screen-drag", "%d,%d,%d,%d" % (_n(x1), _n(y1), _n(x2), _n(y2))])

@ptool("screen_swipe_angle", "phone.screen", "write",
       {"x": "integer", "y": "integer", "angle": "integer", "distance": "integer"},
       "swipe from x,y toward an angle in degrees (0 right, 90 up, 180 left, 270 down) for a distance in pixels")
def screen_swipe_angle(x, y, angle, distance):
    return phone._run(["saann-screen-swipe-angle",
                       "%d,%d,%d,%d" % (_n(x), _n(y), _n(angle, 0, 360), _n(distance, 1, 3000))])

@ptool("screen_pinch", "phone.screen", "write", {"x": "integer", "y": "integer", "percent": "integer"},
       "pinch at x,y; percent above 100 zooms in, below 100 zooms out")
def screen_pinch(x, y, percent):
    return phone._run(["saann-screen-pinch", "%d,%d,%d" % (_n(x), _n(y), _n(percent, 20, 500))])

@ptool("screen_scroll", "phone.screen", "write", {"direction": "string"},
       "scroll the page: down shows content below, up shows content above")
def screen_scroll(direction):
    if direction not in ("up", "down", "left", "right"):
        raise ValueError("direction must be up, down, left or right")
    return phone._run(["saann-screen-scroll", direction])

@ptool("screen_copy", "phone.screen", "write", {"label": "string"},
       "copy the text of the item showing this label to the clipboard", private=True)
def screen_copy(label):
    return phone._run(["saann-screen-copy", _text(label, 80)])

@ptool("screen_paste", "phone.screen", "sensitive", {}, "paste the clipboard into the focused input field")
def screen_paste():
    return phone._run(["saann-screen-paste"])

@ptool("screen_enter", "phone.screen", "sensitive", {}, "press Enter or Search on the focused input field")
def screen_enter():
    return phone._run(["saann-screen-enter"])

@ptool("phone_system", "phone.write", "write", {"action": "string"},
       "quick_settings, lock, power, or screenshot (saves to the gallery)")
def phone_system(action):
    if action not in ("quick_settings", "lock", "power", "screenshot"):
        raise ValueError("action must be quick_settings, lock, power or screenshot")
    return phone._run(["saann-system", action])

@ptool("phone_list_apps", "phone.read", "read", {}, "names of installed apps")
def phone_list_apps():
    return phone._run(["saann-list-apps"])

@ptool("phone_wait", "phone.read", "read", {"seconds": "integer"},
       "wait 1 to 10 seconds for a page or app to load")
def phone_wait(seconds):
    time.sleep(_n(seconds, 1, 10))
    return "waited %d seconds" % seconds
