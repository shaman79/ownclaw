#!/usr/bin/env python3
"""websearch skill — alias of web_search (common naming variant).

Protocol: JSON on stdin -> JSON-lines on stdout.
Requires: requests, beautifulsoup4 (installed via requirements.txt)

Why this exists:
- Users/LLMs often write "websearch" instead of "web_search".
- Keeping a core alias prevents unnecessary generated skills and makes deps deterministic.
"""

import json
import sys
import urllib.parse

import requests
from bs4 import BeautifulSoup


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


def perform_web_search(query: str) -> str:
    q = urllib.parse.quote_plus(query)
    search_url = f"https://duckduckgo.com/html/?q={q}"
    headers = {"User-Agent": "Mozilla/5.0 (compatible; OwnClaw/1.0)"}
    resp = requests.get(search_url, headers=headers, timeout=20)
    resp.raise_for_status()

    soup = BeautifulSoup(resp.text, "html.parser")
    a = soup.select_one("a.result__a")
    if not a or not a.get("href"):
        return "No results found."
    return a.get("href")


def main():
    try:
        params = json.loads(sys.stdin.read() or "{}")
        query = params.get("query")
        if not query or not str(query).strip():
            emit_result("error", {"error": "Missing required parameter: query"})
            return

        emit_progress("Performing web search...")
        top = perform_web_search(str(query).strip())
        emit_result("success", {"result": top})
    except Exception as e:
        emit_result("error", {"error": str(e)})


if __name__ == "__main__":
    main()
