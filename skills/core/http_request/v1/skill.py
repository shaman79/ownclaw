#!/usr/bin/env python3
"""
http_request skill — Make HTTP requests and return response.

Protocol: JSON on stdin → JSON-lines on stdout.
Uses only stdlib (urllib, html.parser) — no external dependencies.
"""
import json
import re
import ssl
import sys
import urllib.request
import urllib.error
from html.parser import HTMLParser


def main():
    raw = sys.stdin.read()
    params = json.loads(raw)

    method = params.get("method", "GET").upper()
    url = params.get("url")
    headers = params.get("headers", {})
    body = params.get("body")
    timeout = int(params.get("timeout", 30))
    verify_ssl = params.get("verify_ssl", True)

    # Set a sensible default User-Agent if none provided — many sites
    # block or return limited content for the default Python-urllib agent
    if "User-Agent" not in headers and "user-agent" not in headers:
        headers["User-Agent"] = (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        )

    if not url:
        emit_result("error", {"error": "Missing required parameter: url"})
        return

    # Normalize URL — add scheme if missing
    if not url.startswith(("http://", "https://")):
        url = "https://" + url

    valid_methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"}
    if method not in valid_methods:
        emit_result("error", {"error": f"Invalid method: {method}. Valid: {', '.join(valid_methods)}"})
        return

    emit_progress(f"{method} {url}")

    try:
        _do_request(url, method, headers, body, timeout, verify_ssl)
    except urllib.error.URLError as e:
        reason = str(e.reason) if hasattr(e, 'reason') else str(e)
        if "CERTIFICATE_VERIFY_FAILED" in reason or "SSL" in reason:
            emit_progress("SSL verification failed — retrying without verification...")
            try:
                _do_request(url, method, headers, body, timeout, verify_ssl=False)
            except Exception as e2:
                emit_result("error", {"error": f"SSL fallback also failed: {e2}"})
        else:
            emit_result("error", {"error": f"URL error: {e.reason}"})
    except TimeoutError:
        emit_result("error", {"error": f"Request timed out after {timeout}s"})
    except Exception as e:
        emit_result("error", {"error": str(e)})


def _do_request(url, method, headers, body, timeout, verify_ssl=True):
    """Perform an HTTP request, optionally skipping SSL verification."""
    data = body.encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)

    # Build SSL context
    ctx = None
    if url.startswith("https://"):
        if verify_ssl:
            ctx = ssl.create_default_context()
        else:
            ctx = ssl._create_unverified_context()

    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            resp_headers = dict(resp.getheaders())
            content_type = resp.headers.get("Content-Type", "").lower()

            # Only read text-based content — skip images, video, audio, binaries
            readable_prefixes = (
                "text/",              # html, plain, csv, xml, css, etc.
                "application/json",
                "application/xml",
                "application/xhtml",
                "application/pdf",
                "application/csv",
                "application/rss",
                "application/atom",
                "application/soap",
                "application/javascript",
                "application/x-yaml",
                "application/yaml",
            )
            if not any(content_type.startswith(p) for p in readable_prefixes):
                emit_result("success", {
                    "status_code": resp.status,
                    "headers": resp_headers,
                    "body": f"[Binary content: {content_type}; not read]",
                    "body_length": 0,
                    "content_type": content_type,
                })
                return

            raw_body = resp.read()
            resp_body = raw_body.decode("utf-8", errors="replace")

            # For HTML responses, extract clean text — raw HTML is mostly noise
            # (scripts, styles, nav, footers) that overwhelms downstream consumers.
            if content_type.startswith("text/html"):
                text_content = strip_html(resp_body)
                emit_result("success", {
                    "status_code": resp.status,
                    "headers": resp_headers,
                    "body": text_content,
                    "raw_html_length": len(resp_body),
                    "body_length": len(text_content),
                    "content_type": content_type,
                })
                return

            emit_result("success", {
                "status_code": resp.status,
                "headers": resp_headers,
                "body": resp_body,
                "body_length": len(resp_body),
                "content_type": content_type,
            })

    except urllib.error.HTTPError as e:
        resp_body = ""
        try:
            resp_body = e.read().decode("utf-8", errors="replace")[:10000]
        except Exception:
            pass
        emit_result("error", {
            "status_code": e.code,
            "error": str(e.reason),
            "body": resp_body,
        })


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


# ---------------------------------------------------------------------------
# HTML → clean text  (stdlib only, no BeautifulSoup)
# ---------------------------------------------------------------------------

class _HTMLTextExtractor(HTMLParser):
    """Extract visible text from HTML, removing scripts, styles, and markup."""

    _SKIP_TAGS = frozenset({"script", "style", "noscript", "svg", "head", "iframe"})
    _BLOCK_TAGS = frozenset({
        "p", "div", "li", "tr", "br", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "section", "article", "header", "footer", "nav", "td", "th",
    })

    def __init__(self):
        super().__init__()
        self._parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag, attrs):
        t = tag.lower()
        if t in self._SKIP_TAGS:
            self._skip_depth += 1
        elif t in self._BLOCK_TAGS:
            self._parts.append("\n")

    def handle_endtag(self, tag):
        if tag.lower() in self._SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)

    def handle_data(self, data):
        if self._skip_depth == 0:
            text = data.strip()
            if text:
                self._parts.append(text)

    def get_text(self) -> str:
        raw = "\n".join(self._parts)
        # Collapse multiple blank lines into one
        return re.sub(r"\n{3,}", "\n\n", raw).strip()


def strip_html(html_body: str) -> str:
    """Convert HTML to clean readable text. Falls back to raw on error."""
    extractor = _HTMLTextExtractor()
    try:
        extractor.feed(html_body)
        return extractor.get_text()
    except Exception:
        return html_body


if __name__ == "__main__":
    main()
