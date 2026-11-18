import hashlib, ipaddress, json, re, socket, sys, time
import urllib.parse, urllib.request, urllib.robotparser
from html.parser import HTMLParser
from pathlib import Path
from core.tools import tool, call_tool

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "data" / "pages"
UA = "SAANN/0.1 (personal research agent)"
MAX_BYTES = 1500000
MAX_CHARS = 200000

def _check_url(url):
    p = urllib.parse.urlparse(url)
    if p.scheme not in ("http", "https"):
        raise ValueError("only http and https links are allowed")
    if not p.hostname:
        raise ValueError("no host in link")
    if p.port not in (None, 80, 443):
        raise ValueError("unusual port blocked")
    for info in socket.getaddrinfo(p.hostname, None):
        ip = ipaddress.ip_address(info[4][0])
        if (ip.is_private or ip.is_loopback or ip.is_link_local
                or ip.is_reserved or ip.is_multicast or ip.is_unspecified):
            raise ValueError("blocked address: " + str(ip))

class _Redir(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def _get(url, timeout=20):
    _check_url(url)
    opener = urllib.request.build_opener(_Redir)
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept": "text/html,text/plain;q=0.9"})
    with opener.open(req, timeout=timeout) as r:
        ctype = r.headers.get("Content-Type", "")
        cs = r.headers.get_content_charset() or "utf-8"
        data = r.read(MAX_BYTES + 1)
    return data[:MAX_BYTES], ctype, cs, len(data) > MAX_BYTES

def _robots_ok(url):
    p = urllib.parse.urlparse(url)
    try:
        data, _, cs, _ = _get("%s://%s/robots.txt" % (p.scheme, p.netloc), 8)
        rp = urllib.robotparser.RobotFileParser()
        rp.parse(data.decode(cs, "replace").splitlines())
        return rp.can_fetch(UA, url)
    except Exception:
        return True

class _Text(HTMLParser):
    SKIP = {"script", "style", "noscript", "nav", "header", "footer",
            "aside", "form", "svg", "iframe", "template"}
    BLOCK = {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5",
             "h6", "section", "article", "table", "ul", "ol", "blockquote",
             "pre"}
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.skip, self.title, self._t = [], 0, "", False
    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        elif tag == "title":
            self._t = True
        if tag in self.BLOCK:
            self.parts.append("\n")
    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        elif tag == "title":
            self._t = False
        if tag in self.BLOCK:
            self.parts.append("\n")
    def handle_data(self, data):
        if self._t:
            self.title += data
        elif not self.skip:
            self.parts.append(data)

def html_to_text(raw):
    p = _Text()
    p.feed(raw)
    text = re.sub(r"\[[^\]]{1,20}\]", "", "".join(p.parts))
    lines = [re.sub(r"[ \t\u00a0]+", " ", l).strip() for l in text.split("\n")]
    return p.title, "\n\n".join(l for l in lines if len(l) >= 40)

def fetch(url, max_age_days=1, refresh=False):
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / (hashlib.sha1(url.encode()).hexdigest()[:16] + ".json")
    old = json.loads(f.read_text()) if f.exists() else None
    if old and not refresh and time.time() - old["fetched"] < max_age_days * 86400:
        return {**old, "cached": True}
    try:
        if not _robots_ok(url):
            raise PermissionError("blocked by robots.txt")
        data, ctype, cs, trunc = _get(url)
        if not re.search(r"text/(html|plain)|xhtml", ctype, re.I):
            raise ValueError("unsupported content type: " + ctype)
        raw = data.decode(cs, "replace")
        if "html" in ctype.lower():
            title, text = html_to_text(raw)
        else:
            title, text = url, raw
        if len(text) < 200:
            raise ValueError("page has too little readable text")
        page = {"url": url, "title": (title.strip() or url)[:120],
                "text": text[:MAX_CHARS], "chars": min(len(text), MAX_CHARS),
                "truncated": trunc or len(text) > MAX_CHARS,
                "fetched": time.time(), "cached": False}
        f.write_text(json.dumps(page))
        return page
    except Exception as e:
        if old:
            return {**old, "cached": True, "stale": True, "error": str(e)}
        raise

@tool("fetch_url", "network.read", "read")
def fetch_url(url):
    return fetch(url)

def read_url(agent, request, url, private=False):
    from engine.planner import solve
    r = call_tool(agent, "fetch_url", url=url)
    if not r["ok"]:
        return {"ok": False, "reason": r["error"]}
    page = r["result"]
    out = solve(agent, request, page["text"], private=private, source=url[:90])
    out["page"] = {k: page.get(k) for k in
                   ("title", "chars", "cached", "stale", "truncated")}
    return out

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print('usage: python -m engine.web "request" url')
        sys.exit()
    o = read_url("research-agent", sys.argv[1], sys.argv[2])
    if not o["ok"]:
        print("FAILED:", o["reason"])
    else:
        print("page:", o["page"])
        print("planned by:", o["tier"])
        print(o["report"])
