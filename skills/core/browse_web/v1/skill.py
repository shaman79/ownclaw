"""
OwnClaw Core Skill: browse_web
Fetches a web page, extracts text and links, and optionally follows internal links.

This is a lightweight web crawler — no browser engine, just HTTP + HTML parsing.
Uses only Python stdlib (html.parser, urllib) so no external dependencies.

Parameters:
  url              - the URL to fetch (required)
  extract_links    - if "true", return a list of links found on the page (default true)
  follow_links     - if "true", also fetch linked pages matching link_pattern (default false)
  link_pattern     - regex pattern to filter which links to follow (optional)
  max_pages        - maximum number of pages to fetch in total (default 5)
  extract_text     - if "true", extract visible text from HTML (default true)
  max_text_length  - max characters of text to return per page (default 5000)
"""
import json
import sys
import re
import ssl
from html.parser import HTMLParser
from urllib.request import urlopen, Request
from urllib.parse import urljoin, urlparse
from urllib.error import HTTPError, URLError


def emit(type_, **kwargs):
    print(json.dumps({"type": type_, **kwargs}, ensure_ascii=False), flush=True)


class TextExtractor(HTMLParser):
    """Extract visible text from HTML, stripping scripts/style/tags."""

    SKIP_TAGS = {"script", "style", "noscript", "head", "meta", "link"}

    def __init__(self):
        super().__init__()
        self.chunks = []
        self.links = []
        self._skip_depth = 0
        self._base_url = ""

    def set_base_url(self, url):
        self._base_url = url

    def handle_starttag(self, tag, attrs):
        tag_lower = tag.lower()
        if tag_lower in self.SKIP_TAGS:
            self._skip_depth += 1
        attrs_dict = dict(attrs)
        if tag_lower == "a" and "href" in attrs_dict:
            href = attrs_dict["href"].strip()
            if href and not href.startswith(("#", "javascript:", "mailto:", "tel:")):
                full_url = urljoin(self._base_url, href)
                self.links.append(full_url)

    def handle_endtag(self, tag):
        if tag.lower() in self.SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)

    def handle_data(self, data):
        if self._skip_depth == 0:
            text = data.strip()
            if text:
                self.chunks.append(text)

    def get_text(self):
        return "\n".join(self.chunks)

    def get_links(self):
        return list(dict.fromkeys(self.links))  # dedupe preserving order


def fetch_page(url, timeout=30):
    """Fetch a URL and return (status_code, html_text, final_url)."""
    headers = {
        "User-Agent": "Mozilla/5.0 (compatible; OwnClaw/1.0; +https://github.com/ownclaw)",
        "Accept": "text/html,application/xhtml+xml,*/*",
        "Accept-Language": "en-US,en;q=0.9,cs;q=0.8"
    }
    req = Request(url, headers=headers)

    ctx = ssl.create_default_context()
    try:
        resp = urlopen(req, timeout=timeout, context=ctx)
    except (ssl.SSLError, URLError):
        ctx = ssl._create_unverified_context()
        resp = urlopen(req, timeout=timeout, context=ctx)

    status = resp.status
    charset = resp.headers.get_content_charset() or "utf-8"
    body = resp.read().decode(charset, errors="replace")
    final_url = resp.url
    return status, body, final_url


def main():
    params = json.load(sys.stdin)

    url = params.get("url", "").strip()
    if not url:
        emit("result", status="error", output={"error": "Missing required parameter: url"})
        return

    extract_links = str(params.get("extract_links", "true")).lower() == "true"
    follow_links = str(params.get("follow_links", "false")).lower() == "true"
    link_pattern = params.get("link_pattern", "")
    max_pages = int(params.get("max_pages", 5))
    extract_text = str(params.get("extract_text", "true")).lower() == "true"
    max_text_length = int(params.get("max_text_length", 5000))

    results = []
    visited = set()
    to_visit = [url]

    while to_visit and len(results) < max_pages:
        current_url = to_visit.pop(0)
        if current_url in visited:
            continue
        visited.add(current_url)

        emit("progress", message=f"Fetching: {current_url}")

        try:
            status, html, final_url = fetch_page(current_url)
        except HTTPError as e:
            results.append({
                "url": current_url,
                "status": e.code,
                "error": str(e),
                "text": None,
                "links": []
            })
            continue
        except Exception as e:
            results.append({
                "url": current_url,
                "status": -1,
                "error": str(e),
                "text": None,
                "links": []
            })
            continue

        # Parse HTML
        parser = TextExtractor()
        parser.set_base_url(final_url)
        try:
            parser.feed(html)
        except Exception:
            pass

        text = parser.get_text() if extract_text else None
        if text and len(text) > max_text_length:
            text = text[:max_text_length] + "...(truncated)"

        links = parser.get_links() if extract_links else []

        page_result = {
            "url": final_url,
            "status": status,
            "text": text,
            "links_count": len(links)
        }
        if extract_links:
            # Return at most 50 links to keep output manageable
            page_result["links"] = links[:50]

        results.append(page_result)

        # Queue links for following if enabled
        if follow_links and links:
            base_domain = urlparse(final_url).netloc
            for link in links:
                if link in visited:
                    continue
                # Only follow same-domain links
                if urlparse(link).netloc != base_domain:
                    continue
                # Apply link pattern filter
                if link_pattern:
                    try:
                        if not re.search(link_pattern, link, re.IGNORECASE):
                            continue
                    except re.error:
                        pass
                to_visit.append(link)

    emit("result", status="success", output={
        "pages_fetched": len(results),
        "pages": results
    })


if __name__ == "__main__":
    main()
