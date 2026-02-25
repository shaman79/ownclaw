#!/usr/bin/env python3
"""
get_system_info skill — Discover runtime facts about this OwnClaw deployment.

Finds and parses the local application.yaml by walking up the filesystem from
this script's location, resolves ${ENV_VAR:default} substitutions against the
actual process environment, and returns deployment facts as structured data.

No information is pre-injected from Java — the skill figures everything out
itself from the filesystem, just like any other data-gathering skill would.

Protocol: JSON on stdin -> JSON-lines on stdout.

Input params (all optional):
  filter  — comma-separated keys to return; omit for all
            keys: executor_url, executor_model, platform, shell, server_port

Output:
  {"type": "result", "status": "success", "output": {"executor_url": "...", ...}}
"""
import json
import os
import platform
import re
import sys
from pathlib import Path


def emit_result(status, output):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


def find_config_file():
    """Walk up from this script's location to find application.yaml."""
    for start in (Path(__file__).resolve().parent, Path(sys.argv[0]).resolve().parent):
        for directory in [start, *start.parents]:
            for name in ("application.yaml", "application.yml"):
                p = directory / name
                if p.is_file():
                    return p
            # Stop at project root markers to avoid wandering into / or C:\
            if any((directory / m).exists() for m in ("build.gradle.kts", "pom.xml", "settings.gradle.kts")):
                break
    return None


def resolve_placeholders(raw: str) -> str:
    """Resolve Spring ${ENV_VAR:default} placeholders using the real environment."""
    def replacer(m):
        inner = m.group(1)
        var, _, default = inner.partition(":")
        # Strip any nested ${...} from the default (simplified)
        default = re.sub(r"\$\{[^}]+\}", "", default).strip()
        return os.environ.get(var.strip(), default)
    return re.sub(r"\$\{([^}]+)\}", replacer, raw)


def load_yaml(path: Path) -> dict:
    """Load YAML, preferring PyYAML; falls back to a regex key:value scan."""
    text = path.read_text(encoding="utf-8")
    try:
        import yaml
        return yaml.safe_load(text) or {}
    except ImportError:
        result = {}
        for line in text.splitlines():
            m = re.match(r"^(\w[\w\-]*):\s*(.+)", line.strip())
            if m:
                result[m.group(1)] = m.group(2).strip()
        return result


def main():
    try:
        params = json.loads(sys.stdin.read().strip() or "{}")
    except Exception:
        params = {}

    wanted = {k.strip() for k in str(params.get("filter", "")).split(",") if k.strip()}

    config_path = find_config_file()
    raw = {}
    if config_path:
        try:
            raw = load_yaml(config_path)
        except Exception:
            pass

    ownclaw  = raw.get("ownclaw", {})  if isinstance(raw, dict) else {}
    executor = ownclaw.get("executor", {}) if isinstance(ownclaw, dict) else {}
    server   = raw.get("server", {})   if isinstance(raw, dict) else {}

    def cfg(val, default=""):
        return resolve_placeholders(str(val)) if val else default

    os_name = platform.system()
    info = {
        "executor_url":   cfg(executor.get("url"),   "http://localhost:11434"),
        "executor_model": cfg(executor.get("model"),  "unknown"),
        "server_port":    cfg(server.get("port"),     "8080"),
        "platform":       os_name,
        "shell":          "powershell" if os_name.lower().startswith("windows") else "bash",
    }

    result = {k: v for k, v in info.items() if not wanted or k in wanted}
    emit_result("success", result)


if __name__ == "__main__":
    main()
