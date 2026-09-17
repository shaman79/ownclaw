# OwnClaw — Production Deployment

## Overview

OwnClaw deploys as a single Spring Boot JAR behind a systemd service on Linux.
Auto-updates are handled by a cron job that polls GitHub for new commits.

```
/opt/ownclaw/
├── ownclaw.jar          # Application
├── jdk/                 # Adoptium Temurin 21
├── .env                 # Secrets (not in git)
├── .deployed-commit     # Commit the installed JAR was built from (written by deploy.sh)
├── data/                # SQLite database
├── skills/              # Dynamic skills (agent-created)
│   ├── generated/       # Skill directories (SKILL.yaml + skill.py)
│   └── _envs/           # Python virtual environments
├── logs/                # Deploy log
├── backups/             # Last 5 JARs for rollback
├── repo/                # Git checkout (build workspace)
└── deploy/              # (symlink or copy from repo)
```

---

## Quick Start

### 1. First-time setup (as root)

```bash
# Upload deploy.sh to the server, then:
chmod +x deploy.sh
sudo ./deploy.sh --setup
```

The setup wizard will interactively prompt you for:
1. **GitHub token** — fine-grained PAT with Contents: Read-only ([create one here](https://github.com/settings/tokens?type=beta))
2. **OpenAI API key** — for the Mentor LLM
3. **Telegram bot token** — optional

All secrets are saved to `/opt/ownclaw/.env` (chmod 600) and loaded automatically
by the systemd service and cron-based auto-updates.

This will:
- Create `ownclaw` system user
- Install JDK 21 (Adoptium Temurin)
- Install git, python3, python3-venv, python3-pip if missing
- Clone the repository
- Build the JAR
- Install the systemd service (`ownclaw.service`)
- Create an `.env` template

Then an **optional features wizard** lets you choose:

| Feature | Default | Description |
|---|---|---|
| Ollama + default model | **Yes** | Installs Ollama, pulls `qwen2.5:14b` |
| Container runtime | **Yes** | Installs Podman (or Docker fallback) for sandboxed skill execution |
| Sudoers rule | **Yes** | Allows the `ownclaw` user to restart the service without a password (needed for cron updates) |
| Cron job | **Yes** | Auto-update every 15 minutes via `deploy.sh --update` |
| MCP server packages | **Yes** | Pre-installs npm packages for MCP servers + Chromium for Puppeteer |

All defaults are **Yes** — press Enter through everything for a fully automated setup.

### 2. Configure secrets

The setup copies [.env.template](.env.template) to `/opt/ownclaw/.env`. Edit it:

```bash
sudo nano /opt/ownclaw/.env
```

| Variable | Required | Description |
|---|---|---|
| `GITHUB_TOKEN` | **Yes** | Fine-grained PAT with Contents:read. Generate at [github.com/settings/tokens](https://github.com/settings/tokens?type=beta) |
| `OPENAI_API_KEY` | **Yes** | OpenAI API key for the Mentor LLM (GPT-4o). Get one at [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |
| `TELEGRAM_BOT_TOKEN` | No | Telegram bot token from @BotFather. Set `OWNCLAW_TELEGRAM_ENABLED=true` to activate |
| `OWNCLAW_SERVER_PORT` | No | HTTP port (default: `8080`) |
| `OWNCLAW_EXECUTOR_URL` | No | Ollama URL (default: `http://localhost:11434`) |
| `OWNCLAW_EXECUTOR_MODEL` | No | Ollama model (default: `qwen2.5:14b`) |
| `JAVA_OPTS` | No | JVM tuning, e.g. `-Xmx512m` |
| `BRAVE_SEARCH_API_KEY` | No | Brave Search API key for the MCP `brave-search` server. Get one at [brave.com/search/api](https://brave.com/search/api/) |

**Ollama** is offered during `--setup`. If you skipped it or need to install later:
```bash
sudo /opt/ownclaw/repo/deploy/deploy.sh --install-ollama
```
This installs Ollama, starts the service, and pulls the configured model.

### 3. Start the service

```bash
sudo systemctl start ownclaw
sudo systemctl status ownclaw
```

### 4. Enable auto-updates

If you chose "Yes" for the cron job during setup, auto-updates are already active.
Otherwise, add it manually:

```bash
sudo crontab -u ownclaw -e
```

Add:
```cron
*/15 * * * * /opt/ownclaw/repo/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
```

This checks for new commits on `main` every 15 minutes. If changes are found, it builds, deploys, restarts, and runs a health check. On failure it automatically rolls back.

"Changes" means `main` differs from the commit the installed JAR was built from, which the script records in `/opt/ownclaw/.deployed-commit` when it installs a JAR. It deliberately does not compare against the git checkout, which already points at the new commit while it is still being built:

- **Build failed or the run was interrupted** — retried on the next cycle, up to 3 attempts per commit (tracked in `.deploy-attempts`). After that it logs an error every cycle and waits for a new commit or a manual force deploy, so a commit that cannot compile does not run Gradle every 15 minutes.
- **Restart failed** (e.g. missing sudoers rule) — the new JAR stays installed and only the restart is retried each cycle.
- **Health check failed and the JAR was rolled back** — that commit is marked `rolled-back` and is not deployed again automatically; the next commit is.
- **No `.deployed-commit` yet** (server set up before this record existed) — the first `--update` rebuilds and deploys once to establish it.

`deploy.sh --test-cron` prints the recorded commit and the attempt counter.

---

## Manual Operations

### Force deploy (skip change check)

```bash
sudo -u ownclaw /opt/ownclaw/repo/deploy/deploy.sh
```

Avoid running deploy as root (plain `sudo /opt/ownclaw/repo/deploy/deploy.sh`). If files under `/opt/ownclaw/skills/` become root-owned, the `ownclaw` service user cannot create or update skills at runtime.

### Rollback to previous version

```bash
sudo -u ownclaw /opt/ownclaw/repo/deploy/deploy.sh --rollback
```

### View application logs

```bash
# Live tail
sudo journalctl -u ownclaw -f

# Last hour
sudo journalctl -u ownclaw --since '1 hour ago'
```

### View deploy logs

```bash
tail -f /opt/ownclaw/logs/deploy.log
```

---

## Service Management

```bash
sudo systemctl start ownclaw
sudo systemctl stop ownclaw
sudo systemctl restart ownclaw
sudo systemctl status ownclaw
```

---

## Health Check

The application exposes a health endpoint:

```
GET http://localhost:8080/api/health → "ok"
```

The deploy script waits up to 60 seconds for this endpoint to respond after a restart.

---

## Ops API

`/api/ops/*` gives an operator — or an AI assistant driving a test → inspect → fix loop — read-only
insight into a running instance, plus a few safe actions. It is **off by default**.

### Enabling it

```bash
# in /opt/ownclaw/.env
OWNCLAW_OPS_TOKEN=$(openssl rand -hex 32)
OWNCLAW_LOG_FILE=/opt/ownclaw/logs/ownclaw.log
```

Then `sudo systemctl restart ownclaw`. The startup log says which state it is in:

```
Ops API enabled at /api/ops/* (token 64 chars)
Ops API disabled — set OWNCLAW_OPS_TOKEN (at least 32 chars) to enable it
```

Unset, or shorter than 32 characters, and **every** ops request returns 503 — the endpoints do not
exist as far as a caller is concerned. The token is read from the environment only: it is never
written to the database, so a database dump cannot disclose it and no stored setting can enable the
API.

### Using it

```bash
T=$(sudo grep '^OWNCLAW_OPS_TOKEN=' /opt/ownclaw/.env | cut -d= -f2)
curl -s -H "X-Ops-Token: $T" http://localhost:8080/api/ops | jq      # lists every endpoint
```

`Authorization: Bearer <token>` works too.

| Endpoint | What it gives you |
|---|---|
| `GET /api/ops/health` | database, cloud provider, local model, queue, JVM, log file, owner |
| `GET /api/ops/config` | effective configuration with secrets redacted, plus `system_settings` key names and their `updated_at` (useful for spotting tampering) |
| `GET /api/ops/logs?lines=200&grep=&level=` | tail of the log file, filtered; newest matches first |
| `GET /api/ops/db/tables` | every table with its row count |
| `POST /api/ops/db/query` | one read-only `SELECT` — `{"sql":"SELECT ...","limit":200}` |
| `GET /api/ops/users` | accounts, who the owner is, who is disabled |
| `GET /api/ops/forensics/{userId}` | everything recorded for one account: messages, tasks, tool calls, memory, scheduled tasks, uploads, spend |
| `GET /api/ops/skills` | each generated skill with size, mtime and SHA-256, so an unexpected change is visible |
| `GET /api/ops/ollama` | installed and loaded models, capabilities, and a live chat round-trip test |
| `GET /api/ops/tasks`, `GET /api/ops/tasks/{taskId}` | recent tasks; one task correlated across events, tool calls and memory |
| `POST /api/ops/selftest` | pass/fail across database, tools, cloud key, local model, skills dir, log file |
| `POST /api/ops/agent/run` | run one agent task and get the outcome plus the full step trajectory |
| `POST /api/ops/agent/cancel/{userId}` | request cancellation (observed between steps) |
| `POST /api/ops/skills/reload` | re-read the generated skills directory |

### What it will not do

No endpoint returns a credential value, runs arbitrary shell, triggers a deploy or restarts the
service. Those are what made the old `/api/debug` surface dangerous; `GET /api/debug/credentials`,
which returned the whole vault in plaintext, was deleted on 2026-09-17.

Secrets are withheld in two independent ways: configuration values whose key looks secret are
replaced with a placeholder, and SQL results are redacted by column name — so even
`SELECT * FROM users` comes back without password hashes. `db/query` additionally refuses any
statement that is not a single `SELECT`, and any statement that so much as names a secret column.

### Diagnosing the local model

The single most useful call when the local tier misbehaves:

```bash
curl -s -H "X-Ops-Token: $T" http://localhost:8080/api/ops/ollama | jq '.modelInfo, .chatRoundTrip'
```

`configuredModelInstalled: false` means Ollama does not have the model the app is asking for, and
every local call is failing with a 404. `supportsChat: false` or `templateLooksUnusable: true`
means the model has no chat template, so `/api/chat` silently discards the system prompt and the
role structure — `promptEvalCount` comes back far smaller than the prompt, and every local job
returns nonsense. Either pull a chat-capable model, or wrap the one you have in a `Modelfile` that
supplies a proper template.

### Reading a task

Every task has an id. Given one, these three views line up:

```bash
curl -s -H "X-Ops-Token: $T" ".../api/ops/tasks/$ID"            # events, tool calls, memory
curl -s -H "X-Ops-Token: $T" ".../api/ops/logs?grep=$ID"        # the think/act/observe trail
curl -s -H "X-Ops-Token: $T" -X POST ".../api/ops/db/query" \
     -d "{\"sql\":\"SELECT * FROM skill_usage WHERE task_id='$ID'\"}"
```

Note when reading outcomes: in this build `success` is `true` for every ending except a cancel or a
crash, including the step cap and a reasoning-failure abort. Check `terminationReason` and the
response text before believing a task delivered anything. `POST /api/ops/agent/run` repeats that
caveat in its own response.

### Exposure

The ops API is reachable wherever the app is. If the instance is on a public hostname, restrict
`/api/ops/` at the reverse proxy to addresses you control, or reach it over a VPN or an SSH tunnel:

```bash
ssh -L 8080:localhost:8080 you@host    # then use http://localhost:8080
```

Every accepted call is logged with method, path, query and source address; rejected calls are logged
without the presented token. `db/query` logs the SQL text.

---

## Security Notes

The systemd service runs with these hardening options:
- `NoNewPrivileges=true`
- `ProtectSystem=strict` (filesystem is read-only except allowed paths)
- `ProtectHome=true`
- `PrivateTmp=true`
- Only `/opt/ownclaw/data`, `/opt/ownclaw/skills/generated`, `/opt/ownclaw/skills/_envs`, and `/opt/ownclaw/logs` are writable.

The `.env` file has `chmod 600` and is readable only by the ownclaw user.

---

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and PR to `main`:
- Builds the project with JDK 21
- Uploads the JAR as an artifact on main-branch pushes

The production server pulls from GitHub independently via the cron-based deploy script.

---

## Architecture Decision: Why Cron Polling?

For a single-server deployment, cron-based polling is chosen over webhook-triggered deploys because:
1. **No inbound port required** — the server only makes outbound `git fetch` calls
2. **No webhook infrastructure** — no web server or GitHub App setup needed
3. **Resilient** — missed polls are harmless; next run catches up
4. **Simple rollback** — just restore the previous JAR
5. **Auditable** — deploy.log captures every check and deployment

For multi-server or zero-downtime needs, consider GitHub Actions with SSH deploy or a container-based approach.
