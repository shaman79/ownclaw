#!/usr/bin/env python3
"""
pdf_parser skill v2 — Download and extract text from PDF files.

Uses PyMuPDF (fitz) for high-quality, layout-aware text extraction.
Strict URL validation prevents bad-parameter errors from propagating.

Protocol: JSON on stdin -> JSON-lines on stdout.
Requires: pymupdf (installed via requirements.txt)
"""
import json
import sys
import tempfile
import os
import re
import urllib.request
import urllib.error

try:
    import fitz  # PyMuPDF
except ImportError:
    def main():
        emit_result("error", {
            "error": "pymupdf library not installed. Run: pip install pymupdf"
        })
    if __name__ == "__main__":
        main()
    sys.exit(0)


# Strict URL pattern — must start with http:// or https://
_URL_RE = re.compile(r"^https?://\S+", re.IGNORECASE)


def main():
    raw = sys.stdin.read()
    params = json.loads(raw)

    url = params.get("url")
    path = params.get("path")
    max_pages = int(params.get("max_pages", 50))

    # Validate 'url' is actually a URL string, not a serialized object or other garbage
    if url is not None:
        if not isinstance(url, str):
            emit_result("error", {
                "error": (
                    f"Parameter 'url' must be a plain URL string, but got {type(url).__name__}. "
                    "Pass the literal URL, e.g. url=\"https://example.com/menu.pdf\"."
                )
            })
            return
        url = url.strip()
        if not _URL_RE.match(url):
            emit_result("error", {
                "error": (
                    f"Parameter 'url' is not a valid URL (must start with http:// or https://). "
                    f"Got: {url[:300]}"
                )
            })
            return

    if not url and not path:
        emit_result("error", {"error": "Missing required parameter: 'url' or 'path'"})
        return

    try:
        if url:
            emit_progress(f"Downloading PDF from {url}")
            pdf_path = download_pdf(url)
            cleanup = True
        else:
            pdf_path = path
            cleanup = False
            if not os.path.isfile(pdf_path):
                emit_result("error", {"error": f"File not found: {pdf_path}"})
                return

        emit_progress("Extracting text from PDF...")
        text, page_count = extract_text(pdf_path, max_pages)

        if cleanup:
            try:
                os.unlink(pdf_path)
            except OSError:
                pass

        if not text.strip():
            emit_result("success", {
                "text": "[PDF contains no extractable text — may be scanned/image-based. Try image_ocr skill.]",
                "page_count": page_count,
                "pages_read": min(page_count, max_pages),
            })
        else:
            emit_result("success", {
                "text": text,
                "page_count": page_count,
                "pages_read": min(page_count, max_pages),
            })

    except urllib.error.HTTPError as e:
        emit_result("error", {"error": f"HTTP {e.code}: {e.reason}"})
    except urllib.error.URLError as e:
        emit_result("error", {"error": f"Download failed: {e.reason}"})
    except Exception as e:
        emit_result("error", {"error": str(e)})


def download_pdf(url: str) -> str:
    """Download a PDF from a URL to a temp file. Returns local path."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (compatible; OwnClaw/1.0)"
    })
    fd, tmp_path = tempfile.mkstemp(suffix=".pdf")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            with os.fdopen(fd, "wb") as f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise
    return tmp_path


def extract_text(pdf_path: str, max_pages: int) -> tuple:
    """
    Extract text from a PDF using PyMuPDF's layout-aware block extraction.

    Strategy:
    - Use get_text("blocks", sort=True) to obtain text blocks sorted in
      natural reading order (top-to-bottom, left-to-right per row).
    - Concatenate blocks within a page with blank lines so sections/rows
      are clearly separated.
    - Fall back to plain get_text("text") if no blocks yield content.

    Returns (text: str, total_page_count: int).
    """
    doc = fitz.open(pdf_path)
    page_count = len(doc)
    pages_to_read = min(page_count, max_pages)

    all_pages = []
    for i in range(pages_to_read):
        page = doc[i]

        # Primary: block-based extraction in reading order
        # Each block: (x0, y0, x1, y1, text, block_no, block_type)
        # block_type 0 = text, 1 = image
        blocks = page.get_text("blocks", sort=True)
        text_blocks = [
            b[4].strip()
            for b in blocks
            if b[6] == 0 and b[4].strip()
        ]

        if text_blocks:
            page_text = "\n\n".join(text_blocks)
        else:
            # Fallback: plain text (still much better than pypdf)
            page_text = page.get_text("text").strip()

        if page_text:
            header = f"--- Page {i + 1} ---\n" if pages_to_read > 1 else ""
            all_pages.append(header + page_text)

    doc.close()
    return "\n\n".join(all_pages), page_count


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


if __name__ == "__main__":
    main()
