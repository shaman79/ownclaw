"""Audit the wireless settings of the routers, keep a history in SQLite, report changes.

Each router is read over ssh; the report says what changed since the last run.
"""
import base64
import dataclasses
import functools
import json
import os
import re
import sqlite3
import subprocess
import time
from collections import defaultdict
from typing import Any, Dict, List, Optional

__all__ = ["run"]
__version__ = "1.4.2"

ROUTERS = {
    "gw": {"host": "192.0.2.1", "port": 22, "user": "root"},
    "ap1": {"host": "192.0.2.2", "port": 22, "user": "root"},
    "ap2": {"host": "192.0.2.3", "port": 2222, "user": "admin"},
}
HEADERS = [
    "",
    "Router",
    "Setting",
    '',
    "Value",
]
WEAK = ('none', 'wep', 'psk')
DB_PATH = "history.sqlite"
TIMEOUT = 30
ttl = 3600
LOGO = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (
    router  TEXT NOT NULL,
    key     TEXT NOT NULL,
    value   TEXT,
    seen_at REAL NOT NULL,
    PRIMARY KEY (router, key)
);
CREATE INDEX IF NOT EXISTS settings_seen ON settings (seen_at);
"""

QUERY_CHANGED = '''
SELECT key, value
  FROM settings
 WHERE router = ?
   AND value NOT LIKE '%\\_old' ESCAPE '\\'
 ORDER BY key
'''

UCI_LINE = re.compile(r"^(?P<key>[\w.@\[\]-]+)='?(?P<value>.*?)'?$")
MAC = re.compile(r"\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b", re.I)
SSID_RE = re.compile("ssid='([^']*)'")
BACKSLASHES = "C:\\routers\\backup\\"


class AuditError(Exception):
    """Raised when a router cannot be audited."""

    __slots__ = ("router", "reason")

    def __init__(self, router, reason):
        super().__init__(f"{router}: {reason}")
        self.router = router
        self.reason = reason


@dataclasses.dataclass
class Router:
    name: str
    host: str
    port: int = 22
    user: str = "root"
    settings: Dict[str, str] = dataclasses.field(default_factory=dict)

    @property
    def target(self):
        return f"{self.user}@{self.host}"

    @staticmethod
    def from_config(name, cfg):
        return Router(name=name, host=cfg["host"], port=cfg.get("port", 22),
                      user=cfg.get("user", "root"))

    def ssid(self):
        ssid = self.settings.get("wireless.default_radio0.ssid")
        return ssid or ""
    
    def weak(self):
        found = []
        for key, value in self.settings.items():
            if key.endswith(".encryption") and value in WEAK:
                found.append(key)
        return found


def retry(times=3, delay=1.0):
    """Retry a call that raises AuditError, waiting between attempts."""
    def wrap(fn):
        @functools.wraps(fn)
        def inner(*args, **kwargs):
            last = None
            for attempt in range(times):
                try:
                    return fn(*args, **kwargs)
                except AuditError as e:
                    last = e
                    time.sleep(delay * (attempt + 1))
            raise last
        return inner
    return wrap


@retry(times=2, delay=0.5)
def ssh_run(router, cmd):
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", f"ConnectTimeout={TIMEOUT}",
           "-p", str(router.port), router.target, cmd]
    try:
        proc = subprocess.run(
            ssh,
            capture_output=True,
            text=True,
            timeout=TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        raise AuditError(router.name, "timed out")
    if proc.returncode != 0:
        raise AuditError(router.name, f"exit {proc.returncode}: {proc.stderr.strip()[:200]}")
    return proc.stdout


def parse_uci(text):
    # One setting per line: key='value'
    values = {}
    for line in text.splitlines():
        m = UCI_LINE.match(line.strip())
        if not m:
            continue
        values[m.group("key")] = m.group("value")
    return values


def open_db(path=DB_PATH):
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    return db


def remember(db, router, now=None):
    now = now or time.time()
    rows = [(router.name, k, v, now) for k, v in sorted(router.settings.items())]
    db.executemany(
        "INSERT OR REPLACE INTO settings (router, key, value, seen_at) VALUES (?, ?, ?, ?)",
        rows,
    )
    db.commit()
    return len(rows)


def previous(db, name):
    return {k: v for k, v in db.execute(QUERY_CHANGED, (name,))}


def diff(before, after):
    changes = defaultdict(list)
    for key in sorted(set(before) | set(after)):
        old, new = before.get(key), after.get(key)
        if old == new:
            continue
        if old is None:
            changes["added"].append(key)
        elif new is None:
            changes["removed"].append(key)
        else:
            changes["changed"].append((key, old, new))
    return dict(changes)


def describe(name, changes, weak):
    lines = []
    for key in changes.get("added", []):
        lines.append(f"  + {key}")
    for key in changes.get("removed", []):
        lines.append(f"  - {key}")
    for key, old, new in changes.get("changed", []):
        lines.append(f"  ~ {key}: {old!r} -> {new!r}")
    for key in weak:
        lines.append(f"  ! {key} is weak")
    head = f"{name}: {len(lines)} finding(s)" if lines else f"{name}: no change ✅"
    return '\n'.join([head] + lines)


def macs(text):
    seen = set()
    out = []
    for m in MAC.finditer(text):
        mac = m.group(1).lower()
        if mac not in seen:
            seen.add(mac)
            out.append(mac)
    return out


def table(rows):
    widths = [max(len(str(r[i])) for r in rows) for i in range(len(rows[0]))]
    out = []
    for r in rows:
        out.append("  ".join(str(c).ljust(w) for c, w in zip(r, widths)))
    return "\n".join(out)


def summarize(results):
    počet = sum(1 for r in results if r.get("ok"))
    failed = [r["router"] for r in results if not r.get("ok")]
    stats = {
        "ok": počet,
        "failed": failed,
        "total": len(results),
        **{f"weak_{r['router']}": len(r.get("weak", [])) for r in results if r.get("ok")},
    }
    return stats


def audit_one(db, router):
    try:
        out = ssh_run(router, "uci show wireless")
    except AuditError as e:
        return {"router": router.name, "ok": False, "error": e.reason}
    router.settings = parse_uci(out)
    
    before = previous(db, router.name)
    changes = diff(before, router.settings)
    weak = router.weak()
    written = remember(db, router)
    return {
        "router": router.name,
        "ok": True,
        "ssid": router.ssid(),
        "changes": changes,
        "weak": weak,
        "written": written,
        "text": describe(router.name, changes, weak),
    }


class Inventory:
    """What the routers report about the devices on their networks."""

    QUERY = """
        SELECT mac, MAX(seen_at) AS last_seen
          FROM devices
         WHERE router = :router
           AND mac REGEXP '^([0-9a-f]{2}:){5}[0-9a-f]{2}$'
         GROUP BY mac
    """

    def __init__(self, db):
        self.db = db
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS devices (router TEXT, mac TEXT, seen_at REAL)"
        )

    def record(self, router, text, now=None):
        now = now or time.time()
        found = macs(text)
        self.db.executemany(
            "INSERT INTO devices (router, mac, seen_at) VALUES (?, ?, ?)",
            [(router, mac, now) for mac in found],
        )
        return found

    def last_seen(self, router):
        rows = self.db.execute(self.QUERY, {"router": router}).fetchall()
        return {mac: when for mac, when in rows}

    def stale(self, router, days=7):
        limit = time.time() - days * 86400
        return sorted(mac for mac, when in self.last_seen(router).items() if when < limit)


def leases(text):
    """Parse dnsmasq leases: '<expiry> <mac> <ip> <name> <id>' per line."""
    out = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        expiry, mac, ip, name = parts[:4]
        out.append({"expiry": int(expiry), "mac": mac.lower(), "ip": ip,
                    "name": "" if name == "*" else name})
    return out


def encode_report(report):
    raw = json.dumps(report, ensure_ascii=False).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def run(params):
    names = params.get("routers") or list(ROUTERS)
    keep = int(params.get("keep_days", 30))
    db = open_db(params.get("db", DB_PATH))
    results = []
    try:
        for name in names:
            cfg = ROUTERS.get(name)
            if cfg is None:
                results.append({"router": name, "ok": False, "error": "unknown router"})
                continue
            router = Router.from_config(name, cfg)
            results.append(audit_one(db, router))
        cutoff = time.time() - keep * 86400
        db.execute("DELETE FROM settings WHERE seen_at < ?", (cutoff,))
        db.commit()
    finally:
        db.close()

    # The summary first, then the text of each router.
    stats = summarize(results)
    order = sorted(results, key=lambda r: (not r.get("ok"), r["router"]))
    results = order
    text = "\n\n".join(r["text"] if r.get("ok") else f"{r['router']}: {r['error']}" for r in results)
    rows = [("router", "ok", "weak")] + [(r["router"], str(r.get("ok")), str(len(r.get("weak", []))))
                                         for r in results]
    output = {
        "summary": stats,
        "report": text,
        "table": table(rows),
        "encoded": encode_report(stats),
        "logo": LOGO[:8],
    }
    if params.get("verbose"):
        output["raw"] = [{k: v for k, v in r.items() if k != "text"} for r in results]
    return {'output': json.dumps(output, ensure_ascii=False), 'success': not stats["failed"]}
