import json
import sys
from typing import Any


def _read_json_line() -> dict[str, Any]:
    line = sys.stdin.readline()
    if not line:
        return {}
    line = line.strip()
    if not line:
        return {}
    try:
        return json.loads(line)
    except Exception:
        return {}


def _write(obj: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> None:
    params = _read_json_line()
    prompt = str(params.get("prompt") or "")
    options = params.get("options")

    if not prompt:
        _write({
            "type": "result",
            "status": "error",
            "output": {"error": "Missing required param: prompt"},
        })
        return

    need_input: dict[str, Any] = {"type": "need_input", "prompt": prompt}
    if isinstance(options, list) and options:
        need_input["options"] = options

    _write(need_input)

    # Wait for user_input
    response = _read_json_line()
    value = ""
    if isinstance(response, dict) and response.get("type") == "user_input":
        value = response.get("value")
    if value is None:
        value = ""

    _write({
        "type": "result",
        "status": "success",
        "output": {"value": value},
    })


if __name__ == "__main__":
    main()
