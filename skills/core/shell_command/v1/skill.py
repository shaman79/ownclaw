#!/usr/bin/env python3
"""
shell_command skill — Execute a shell command and return output.

Protocol: JSON on stdin → JSON-lines on stdout.
"""
import json
import subprocess
import sys
import os
import shutil
from typing import Optional


def _run_command(command: str, *, working_dir: Optional[str], timeout: int) -> subprocess.CompletedProcess:
    """Run a command through an OS-appropriate shell.

    On POSIX, prefer bash (-lc) if present to support bash-isms.
    Falls back to sh (-c) otherwise.
    """
    if os.name == "nt":
        return subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=working_dir,
        )

    bash = shutil.which("bash")
    if bash:
        return subprocess.run(
            [bash, "-lc", command],
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=working_dir,
        )

    sh = shutil.which("sh") or "/bin/sh"
    return subprocess.run(
        [sh, "-c", command],
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=working_dir,
    )


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
        result = _run_command(command, working_dir=working_dir, timeout=timeout)

        # Self-heal: if we ended up on /bin/sh and hit a common dash syntax error,
        # retry with bash if it exists.
        if os.name != "nt" and result.returncode != 0:
            stderr = (result.stderr or "").lower()
            bash = shutil.which("bash")
            if ("syntax error" in stderr or "unexpected" in stderr) and bash:
                retry = subprocess.run(
                    [bash, "-lc", command],
                    capture_output=True,
                    text=True,
                    timeout=timeout,
                    cwd=working_dir,
                )
                if retry.returncode == 0:
                    result = retry

        payload = {
            "stdout": result.stdout,
            "stderr": result.stderr,
            "exit_code": result.returncode,
        }
        if result.returncode == 0:
            emit_result("success", payload)
        else:
            # Important: emit error so orchestrator can self-heal.
            payload["error"] = "Command failed"
            emit_result("error", payload)
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
