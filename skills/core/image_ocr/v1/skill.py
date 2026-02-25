#!/usr/bin/env python3
"""
image_ocr skill — Extract text from an image using the ocr.space OCR API.

No system binaries required. Uses only stdlib (urllib) + the free ocr.space API.
Free tier: up to 25,000 requests/month, max 1 MB per image.
Demo API key 'helloworld' works for testing; register at https://ocr.space/ocrapi
for a personal free key with higher limits.

Parameters:
  image_url   (required) URL of the image to process
  api_key     (optional) ocr.space API key — defaults to 'helloworld' demo key
  language    (optional) language code, e.g. 'eng', 'ger', 'fra' (default: 'eng')
  ocr_engine  (optional) 1 = standard, 2 = enhanced for low-res images (default: 1)
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

OCR_SPACE_URL = "https://api.ocr.space/parse/image"


def extract_text(image_url: str, api_key: str = "helloworld",
                 language: str = "eng", ocr_engine: int = 1) -> dict:
    payload = urllib.parse.urlencode({
        "url": image_url,
        "apikey": api_key,
        "language": language,
        "isOverlayRequired": "false",
        "OCREngine": str(ocr_engine),
    }).encode("utf-8")

    req = urllib.request.Request(OCR_SPACE_URL, data=payload, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("User-Agent", "OwnClaw/1.0")

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return {"error": f"HTTP {e.code}: {body[:200]}"}
    except urllib.error.URLError as e:
        return {"error": f"Network error: {e.reason}"}

    try:
        result = json.loads(raw)
    except json.JSONDecodeError:
        return {"error": f"Invalid JSON from OCR API: {raw[:200]}"}

    if result.get("IsErroredOnProcessing"):
        messages = result.get("ErrorMessage", ["Unknown OCR error"])
        msg = messages[0] if isinstance(messages, list) else str(messages)
        return {"error": msg}

    parsed = result.get("ParsedResults", [])
    if not parsed:
        return {"error": "OCR API returned no results for this image"}

    text = "\n".join(r.get("ParsedText", "").strip() for r in parsed).strip()

    if not text:
        return {"error": "No text found in image (image may be blank or unreadable)"}

    return {
        "text": text,
        "pages": len(parsed),
        "source": image_url,
    }


def main():
    raw = sys.stdin.read().strip()
    params = json.loads(raw) if raw else {}

    image_url = str(params.get("image_url", "")).strip()
    if not image_url:
        print(json.dumps({"error": "Missing required parameter: image_url"}))
        sys.exit(0)

    api_key = str(params.get("api_key", params.get("ocr_api_key", "helloworld")))
    language = str(params.get("language", "eng"))
    ocr_engine = int(params.get("ocr_engine", 1))

    result = extract_text(image_url, api_key=api_key, language=language, ocr_engine=ocr_engine)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
