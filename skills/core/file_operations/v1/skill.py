#!/usr/bin/env python3
"""
file_operations skill — Read, write, list, delete, move, copy files.

Protocol: JSON on stdin → JSON-lines on stdout.
"""
import json
import os
import shutil
import sys


def main():
    raw = sys.stdin.read()
    params = json.loads(raw)

    operation = params.get("operation", "").lower()
    path = params.get("path")

    if not operation:
        emit_result("error", {"error": "Missing required parameter: operation"})
        return
    if not path:
        emit_result("error", {"error": "Missing required parameter: path"})
        return

    ops = {
        "read": op_read,
        "write": op_write,
        "append": op_append,
        "list": op_list,
        "delete": op_delete,
        "move": op_move,
        "copy": op_copy,
        "mkdir": op_mkdir,
        "exists": op_exists,
    }

    handler = ops.get(operation)
    if not handler:
        emit_result("error", {"error": f"Unknown operation: {operation}. Valid: {', '.join(ops.keys())}"})
        return

    try:
        handler(params, path)
    except Exception as e:
        emit_result("error", {"error": str(e)})


def op_read(params, path):
    if not os.path.isfile(path):
        emit_result("error", {"error": f"File not found: {path}"})
        return
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()
    emit_result("success", {"content": content, "size": len(content)})


def op_write(params, path):
    content = params.get("content", "")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    # Use errors="replace" to handle surrogates from web content
    with open(path, "w", encoding="utf-8", errors="replace") as f:
        f.write(content)
    emit_result("success", {"path": path, "bytes_written": len(content)})


def op_append(params, path):
    content = params.get("content", "")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    # Use errors="replace" to handle surrogates from web content
    with open(path, "a", encoding="utf-8", errors="replace") as f:
        f.write(content)
    emit_result("success", {"path": path, "bytes_appended": len(content)})


def op_list(params, path):
    if not os.path.isdir(path):
        emit_result("error", {"error": f"Directory not found: {path}"})
        return
    recursive = params.get("recursive", False)
    entries = []
    if recursive:
        for root, dirs, files in os.walk(path):
            for name in dirs:
                entries.append({"name": os.path.relpath(os.path.join(root, name), path), "type": "dir"})
            for name in files:
                fp = os.path.join(root, name)
                entries.append({"name": os.path.relpath(fp, path), "type": "file", "size": os.path.getsize(fp)})
    else:
        for name in sorted(os.listdir(path)):
            fp = os.path.join(path, name)
            entry = {"name": name, "type": "dir" if os.path.isdir(fp) else "file"}
            if os.path.isfile(fp):
                entry["size"] = os.path.getsize(fp)
            entries.append(entry)
    emit_result("success", {"entries": entries, "count": len(entries)})


def op_delete(params, path):
    recursive = params.get("recursive", False)
    if os.path.isfile(path):
        os.remove(path)
        emit_result("success", {"deleted": path, "type": "file"})
    elif os.path.isdir(path):
        if recursive:
            shutil.rmtree(path)
        else:
            os.rmdir(path)
        emit_result("success", {"deleted": path, "type": "dir"})
    else:
        emit_result("error", {"error": f"Path not found: {path}"})


def op_move(params, path):
    dest = params.get("destination")
    if not dest:
        emit_result("error", {"error": "Missing parameter: destination"})
        return
    shutil.move(path, dest)
    emit_result("success", {"from": path, "to": dest})


def op_copy(params, path):
    dest = params.get("destination")
    if not dest:
        emit_result("error", {"error": "Missing parameter: destination"})
        return
    if os.path.isdir(path):
        shutil.copytree(path, dest)
    else:
        os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
        shutil.copy2(path, dest)
    emit_result("success", {"from": path, "to": dest})


def op_mkdir(params, path):
    os.makedirs(path, exist_ok=True)
    emit_result("success", {"created": path})


def op_exists(params, path):
    exists = os.path.exists(path)
    ftype = "file" if os.path.isfile(path) else "dir" if os.path.isdir(path) else "none"
    emit_result("success", {"exists": exists, "type": ftype, "path": path})


def emit_progress(message: str):
    print(json.dumps({"type": "progress", "message": message}), flush=True)


def emit_result(status: str, output: dict):
    print(json.dumps({"type": "result", "status": status, "output": output}), flush=True)


if __name__ == "__main__":
    main()
