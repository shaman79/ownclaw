#!/usr/bin/env python3
"""websearch skill — thin alias that delegates to web_search/v1/skill.py.

Why this exists:
- Users/LLMs often write "websearch" instead of "web_search".
- Keeping a core alias prevents unnecessary generated skills.

Protocol: JSON on stdin -> JSON-lines on stdout.
"""

import importlib.util
import os
import sys

# Locate web_search/v1/skill.py robustly regardless of where this alias lives.
# Directory layout: skills/{core|generated}/{skillname}/{vN}/skill.py
# Go up 3 levels from this file to reach the skills/ root, then descend into core/web_search.
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))  # .../vN
_SKILL_DIR = os.path.dirname(_THIS_DIR)                 # .../websearch
_TYPE_DIR  = os.path.dirname(_SKILL_DIR)                # .../core (or generated)
_SKILLS_ROOT = os.path.dirname(_TYPE_DIR)               # .../skills/
_WS_SCRIPT = os.path.join(_SKILLS_ROOT, "core", "web_search", "v1", "skill.py")


def _load_web_search():
    if not os.path.exists(_WS_SCRIPT):
        raise FileNotFoundError(f"web_search skill not found at: {_WS_SCRIPT}")
    spec = importlib.util.spec_from_file_location("web_search_skill", _WS_SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


if __name__ == "__main__":
    try:
        mod = _load_web_search()
        mod.main()
    except Exception as e:
        import json
        print(json.dumps({"type": "result", "status": "error",
                          "output": {"error": f"websearch alias failed to load web_search: {e}"}}))
