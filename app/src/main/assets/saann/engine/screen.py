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
