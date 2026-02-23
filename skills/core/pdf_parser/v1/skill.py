#!/usr/bin/env python3
"""
pdf_parser skill — Download and extract text from PDF files.

Protocol: JSON on stdin -> JSON-lines on stdout.
Requires: pypdf (installed via requirements.txt)
"""
import json
import sys
import tempfile
import os
import urllib.request
import urllib.error

try:
    from pypdf import PdfReader
except ImportError:
    # Fallback message if pypdf is not installed (venv provisioning failed)
    def main():
        emit_result("error", {"error": "pypdf library not installed. The pdf_parser skill requires it."})
    if __name__ == "__main__":
        main()
    sys.exit(0)


def main():
    raw = sys.stdin.read()
    params = json.loads(raw)

    url = params.get("url")
    path = params.get("path")
    max_pages = int(params.get("max_pages", 50))

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
                "text": "[PDF contains no extractable text — may be scanned/image-based]",
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
    """Download a PDF from a URL to a temp file."""
    # Normalize URL — add scheme if missing
    if not url.startswith(("http://", "https://")):
        url = "https://" + url

    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (compatible; OwnClaw/1.0)"
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        # Verify it looks like a PDF
        content_type = resp.headers.get("Content-Type", "").lower()
        # Some servers serve PDFs as application/octet-stream, so don't be too strict

        suffix = ".pdf"
        fd, tmp_path = tempfile.mkstemp(suffix=suffix)
        try:
            with os.fdopen(fd, "wb") as f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
        except Exception:
            os.unlink(tmp_path)
            raise

    return tmp_path


def extract_text(pdf_path: str, max_pages: int) -> tuple:
    """Extract text from a PDF file, return (text, total_page_count)."""
    reader = PdfReader(pdf_path)
    page_count = len(reader.pages)
    pages_to_read = min(page_count, max_pages)

    text_parts = []
    for i in range(pages_to_read):
        page = reader.pages[i]
        page_text = page.extract_text() or ""
        if page_text.strip():
            if pages_to_read > 1:
                text_parts.append(f"--- Page {i + 1} ---")
            text_parts.append(page_text.strip())

    return "\n\n".join(text_parts), page_count


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


if __name__ == "__main__":
    main()
