# OwnClaw — Production Deployment

## Overview

OwnClaw deploys as a single Spring Boot JAR behind a systemd service on Linux.
Auto-updates are handled by a cron job that polls GitHub for new commits.

```
/opt/ownclaw/
├── ownclaw.jar          # Application
├── jdk/                 # Adoptium Temurin 21
├── .env                 # Secrets (not in git)
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
- Install Docker or Podman (for sandboxed skill execution with system packages)
- Optionally install Ollama and pull the default model (qwen2.5:14b)
- Clone the repository
- Build the JAR
- Install the systemd service (`ownclaw.service`)
- Create an `.env` template

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

```bash
sudo crontab -u ownclaw -e
```

Add:
```cron
*/15 * * * * /opt/ownclaw/repo/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
```

This checks for new commits on `main` every 15 minutes. If changes are found, it builds, deploys, restarts, and runs a health check. On failure it automatically rolls back.

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
