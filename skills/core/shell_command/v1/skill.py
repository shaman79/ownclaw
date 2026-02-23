#!/usr/bin/env python3
"""
shell_command skill — Execute a shell command and return output.

Protocol: JSON on stdin → JSON-lines on stdout.
"""
import json
import subprocess
import sys
import os


def main():
    raw = sys.stdin.read()
    params = json.loads(raw)

    command = params.get("command")
    if not command:
        emit_result("error", {"error": "Missing required parameter: command"})
        return

    working_dir = params.get("working_dir", None)
    timeout = int(params.get("timeout", 30))

    if working_dir and not os.path.isdir(working_dir):
        emit_result("error", {"error": f"Working directory does not exist: {working_dir}"})
        return

    emit_progress(f"Executing: {command}")

    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=working_dir,
        )
        emit_result("success", {
            "stdout": result.stdout,
            "stderr": result.stderr,
            "exit_code": result.returncode,
        })
    except subprocess.TimeoutExpired:
        emit_result("error", {
            "error": f"Command timed out after {timeout}s",
            "exit_code": -1,
        })
    except Exception as e:
        emit_result("error", {"error": str(e), "exit_code": -1})


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


if __name__ == "__main__":
    main()
