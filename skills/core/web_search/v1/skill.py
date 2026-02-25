#!/usr/bin/env python3
"""
web_search skill — perform a web search and return top results.

Strategy (most-reliable first):
  1. DuckDuckGo Instant Answer JSON API (stdlib urllib, fast, no scraping)
  2. DuckDuckGo Lite HTML (requests + bs4, more results for general queries)

Returns a list of up to 5 results: [{title, url, snippet}]
Emits error JSON (not silent success) when zero results found, so self-heal
and follow-up planning can react.

Protocol: JSON on stdin -> JSON-lines on stdout.
"""

import json
import sys
import ssl
import urllib.parse
import urllib.request
import urllib.error


USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/122.0.0.0 Safari/537.36"
)


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


def _ssl_context():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def search_ddg_instant(query: str) -> list:
    """DuckDuckGo Instant Answer JSON API — stdlib only, no scraping needed."""
    url = (
        "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q="
        + urllib.parse.quote_plus(query)
    )
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=15, context=_ssl_context()) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
    except Exception:
        return []

    results = []

    # Direct abstract answer (Wikipedia etc.)
    if data.get("AbstractText") and data.get("AbstractURL"):
        results.append({
            "title": data.get("Heading") or query,
            "url": data["AbstractURL"],
            "snippet": data["AbstractText"][:400],
        })

    # Related topics
    for topic in data.get("RelatedTopics", []):
        if len(results) >= 5:
            break
        # Nested category groups have a sub-list
        if "Topics" in topic:
            for sub in topic["Topics"]:
                if len(results) >= 5:
                    break
                url_ = sub.get("FirstURL", "")
                text = sub.get("Text", "")
                if url_ and text:
                    results.append({"title": text[:80], "url": url_, "snippet": text[:400]})
        else:
            url_ = topic.get("FirstURL", "")
            text = topic.get("Text", "")
            if url_ and text:
                results.append({"title": text[:80], "url": url_, "snippet": text[:400]})

    return results


def search_ddg_lite(query: str) -> list:
    """DuckDuckGo Lite HTML fallback — needs requests + beautifulsoup4."""
    try:
        import requests
        from bs4 import BeautifulSoup
    except ImportError:
        return []

    url = "https://lite.duckduckgo.com/lite/"
    headers = {"User-Agent": USER_AGENT, "Content-Type": "application/x-www-form-urlencoded"}
    post_data = urllib.parse.urlencode({"q": query}).encode()
    try:
        resp = requests.post(url, data=post_data, headers=headers, timeout=20, verify=False)
        resp.raise_for_status()
    except Exception:
        return []

    soup = BeautifulSoup(resp.text, "html.parser")
    results = []
    for a in soup.find_all("a", class_="result-link"):
        if len(results) >= 5:
            break
        href = a.get("href", "")
        title = a.get_text(strip=True)
        snippet = ""
        try:
            row = a.find_parent("tr")
            if row:
                nxt = row.find_next_sibling("tr")
                if nxt:
                    snippet = nxt.get_text(strip=True)[:400]
        except Exception:
            pass
        if href and title:
            results.append({"title": title, "url": href, "snippet": snippet})

    return results


def main():
    try:
        raw = sys.stdin.read().strip()
        params = json.loads(raw) if raw else {}
        query = str(params.get("query", "")).strip()
        if not query:
            emit_result("error", {"error": "Missing required parameter: query"})
            return

        emit_progress(f"Searching: {query}")

        # Strategy 1: DDG Instant Answer JSON (stdlib, fast)
        results = search_ddg_instant(query)

        # Strategy 2: DDG Lite HTML fallback
        if not results:
            emit_progress("Instant answer empty — trying DDG Lite HTML...")
            results = search_ddg_lite(query)

        if not results:
            # Emit error (not silent success) so Mentor/self-heal can react
            emit_result("error", {
                "error": "No results found for: " + query,
                "query": query,
            })
            return

        emit_result("success", {
            "query": query,
            "results": results,
            "count": len(results),
            "top_url": results[0]["url"],
            "top_snippet": results[0]["snippet"],
        })

    except json.JSONDecodeError as e:
        emit_result("error", {"error": f"Invalid JSON input: {e}"})
    except Exception as e:
        emit_result("error", {"error": str(e)})


if __name__ == "__main__":
    main()
