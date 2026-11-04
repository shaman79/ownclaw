# 🦀 OwnClaw

A self-hosted, self-learning AI agent that runs on your own hardware. Give it a task in natural language and it figures out the rest — creating tools, executing code, delegating work, and learning from every interaction.

## What it does

OwnClaw is an autonomous agent system that receives tasks through a web chat or a Telegram bot and executes them through a reactive Think → Act → Observe loop. It can browse the web, run shell commands, parse documents, manage files, interact with APIs, and create new Python tools on the fly when none of its existing skills fit the task.

Unlike chatbots that just produce text, OwnClaw *does things*. Ask it to scan your network, monitor a service, parse your email, or schedule a recurring report — it will build the tools it needs, execute them, and deliver results.

## Architecture

```
User ──► Web chat / Telegram ──► Task queue ──► AgentLoop
                                (chats run side     │
                                 by side)           │
                 ┌──────────────────────────────────┼────────────────────┐
                 │                                  │                    │
          Privacy filter                        Local LLM              Skills
       (secrets removed, identifiers         (Ollama: runs the     (Python, own venv;
        replaced, private data checked)       skills, reads         a container when
                 │                            private data)         OS packages are needed)
             Cloud LLM
       (plans, checks the results)
```

### Dual-LLM design

The system runs two LLMs with distinct roles:

- **Cloud LLM** (Anthropic Claude / OpenAI) plans the task. It reads the request and the chat, decides what has to happen next, and checks the results.
- **Local LLM** (Ollama on your own GPU, e.g. Qwen3.6-35B-A3B) does the work. It runs the skills the plan needs, reads private data the cloud is never shown, and reports back.

How the work is split is one setting, **time vs cost**, chosen on the settings page and per chat next to Send:

| Setting | What happens |
|---|---|
| **Fastest** | The cloud model runs the skills itself. |
| **Cheaper** (default) | The cloud model plans and hands the work to the local model with a `delegate` action; it is not given the skills itself. If a delegation fails, the cloud gets the skills back for that task. |
| **Free** | Local only: no cloud model is called. The local model runs the whole task, best effort (`/local on`). |

Scheduled work runs local-first too. A **thinking effort** — low, medium or high — sets how much the models reason before they act, again per chat if you like.

If the cloud model can't be reached — no internet, a rejected key, no credit, an outage the retries did not outlast — the task carries on with the local model, and the chat says so. The next task tries the cloud again.

### Why this saves money

Cloud LLM APIs charge per token. A typical agent task might take 10-30 tool calls — and every call means a full round-trip through the cloud model with the entire conversation context. That context grows with each step, so later calls are disproportionately expensive.

OwnClaw keeps the cloud model on the decisions. With the default setting, the cloud plans and the local model runs the skills: the long tool outputs and the back-and-forth of execution happen on your own hardware at zero marginal cost, and only the result goes back to the cloud.

The prompt is kept small too. A task does not start with every skill described in it: it starts with the few that best match the request, and the models look up the others with `find_tools` when they need them (Anthropic's deferred tool loading on the cloud side). Prompt caching keeps the fixed part of each request cheap.

By contrast, OpenClaw sends every single action through its cloud LLM — there is no delegation path. Every shell command, every API call, every file read costs cloud tokens. For a 20-step task, that means 20 cloud round-trips with ever-growing context windows.

### Skill system

Skills are Python scripts with a standard contract (`def run(params) → {output, success}`). The agent creates new skills on demand — the LLM generates the code, the system installs pip dependencies into the skill's own venv (so one skill's packages never break another's), and the skill becomes available immediately for current and future tasks.

Skills persist across sessions. Over time, the agent accumulates a growing library of capabilities tailored to the user's actual needs. Skills are shared by all accounts, and each run uses the credentials of the account that runs it. Only a skill's author or the owner can change or delete it.

### Other components

- **Task queue** — tasks of different chats run side by side, one at a time in each chat; scheduled and `/bg` work runs beside them. The local model works on one task at a time and takes the next when the first has finished.
- **Talking to a running task** — a message sent in a chat while its task runs goes to that task, read before its next step. **Queue** (`/queue`) runs it as a task of its own instead. **Stop** ends the chat's work; `/cancel all` ends everything.
- **Questions with choices** — when the agent asks something, the answers are buttons to click (keys in Telegram).
- **Episodic memory** — every finished task is kept whole, and the agent recalls the ones that match when it asks for them
- **Remembered facts** — what you ask it to remember, kept per user
- **Credential vault** — AES-256-GCM encrypted storage for API keys and passwords
- **Scheduled tasks** — one-shot and recurring (cron-style) task execution
- **Whole chat memory** — a task reads the whole chat it came from, every message whole, with a record of what each earlier task did; nothing is summarised or cut to fit
- **Live status** — every model call of a task is shown while it runs (which model, what for, its reasoning as it arrives), with token counters and the cloud's cost; every chat row says when it was saved; a task page shows each step
- **Ops API** — diagnostics, a few switches and agent runs (`/api/ops/*`), for operators and AI assistants; off unless a token is set

## Key differences from OpenClaw

OwnClaw started as a fork of the [OpenClaw](https://github.com/BionicClick/OpenClaw) concept but has diverged significantly in architecture and philosophy:

| | OpenClaw | OwnClaw |
|---|---|---|
| **Execution model** | Plan-first: generates a complete DAG upfront, then executes it blind | Reactive loop: one action at a time, observe result, reason about next step |
| **LLM routing** | Cloud-only, single LLM | Dual-LLM: cloud plans, local runs the skills; local-only mode, and automatic fallback when the cloud is unreachable |
| **Skill creation** | Manual or pre-built skill library | On-the-fly: the LLM generates Python code, auto-installs dependencies |
| **Error recovery** | Skill repair loop (diagnose → patch code → retry) | Trajectory-aware: critic agent, reflection injection, stall detection |
| **Memory** | Plan cache (exact-match only) | Episodic memory, remembered facts, the whole conversation history |
| **Multi-user** | Single-user | Multi-user with isolated profiles, chats and credentials; one shared skill library |
| **Credential handling** | Config files or hardcoded | Encrypted vault with per-user isolation; values removed from everything a cloud model is sent |
| **Privacy** | Everything goes to the cloud | Secrets removed and identifiers replaced before any cloud call; uploaded files and mail read only by the local model |
| **Sandbox** | Basic process execution | A bubblewrap jail for every skill, a hardened systemd service, stall detection |
| **Cost control** | None — every action hits the cloud API | Time-vs-cost setting, local delegation, on-demand tool lookup, prompt caching, rate-limit back-off, optional token budgets |
| **UI** | CLI / basic web | Chat UI with live model calls, per-chat cost and effort, task pages, debug mode, settings panel; Telegram bot |

The fundamental architectural difference: OpenClaw's plan-first model cannot handle tasks that require observing intermediate results before deciding what to do next (which is most real-world tasks). OwnClaw's reactive loop solves this — the agent sees every tool result and adapts its strategy accordingly.

## Security model

### What the cloud model is sent

Every request to a cloud model goes through one door and one filter, applied to every part of it — system prompt, messages, tool descriptions:

- **Secrets are removed** and never come back: every value in the credential vault, and what is recognised as a secret by its name or its shape — passwords, keys, tokens, private keys, password hashes, JWTs, a password in a URL, a WiFi key. A tool call that would carry a removed secret is refused before it runs.
- **Personal identifiers are replaced** by placeholders such as `<email_3>`: e-mail addresses, international phone numbers, IBANs, card numbers, MAC addresses, public IP addresses, SSIDs and hostnames. The placeholders are put back on your side only, so skills, answers and schedules hold the real values, and the mapping is never sent.
- **Private data stays local.** Files you upload and mail a skill reads are **private**: the cloud model is shown a description of them, and only the local model reads them. Before each request leaves, a check (the canary) looks for any private data in it and stops the request if it finds some.
- **Free mode calls no cloud model at all.**

Each call is recorded with the number of secrets removed and identifiers replaced; the task page shows them, and the chat says so when a step's result was changed.

### Credential isolation

- User credentials (API keys, passwords, tokens) are stored in an **AES-256-GCM encrypted vault** backed by SQLite.
- Credentials are **never passed through LLM prompts**. A skill gets the credentials it declares on its input when it starts — never on a command line — and the runner puts them in its environment.
- Vault values are removed from logs and traces as well as from cloud requests.
- Each user's credentials are isolated: no command, page or API gives one account another's vault, and with the skill jail on, no skill can read the database they are kept in.
- The agent can ask the user for a credential in the chat, store it securely, and hand it to skills that declare it.
- The secret that signs chat links is never handed to any skill, and with the skill jail on, no skill can read it from the database.

### Execution sandbox

- **Skill jail**: every Python process OwnClaw starts for a skill — its runs, its checks, its venv and pip — runs in an unprivileged bubblewrap jail:
  - the host is read-only, and the skill sees only its own processes;
  - OwnClaw's own directory is hidden — the database and the vault's key, `.env`, uploads it was not handed, other skills, logs, backups and the code — and so are the system journal and `/run`;
  - it can write only its own directory and its account's `/tmp`, which that account's skills share between runs;
  - the network is the host's, so skills that talk to the LAN, mail servers, Home Assistant or Ollama keep working.

  The jail turns itself on once bubblewrap is installed and passes its self-check (`sudo apt install bubblewrap`, then restart OwnClaw). `/status` and `/api/ops/health` show its state, and `OWNCLAW_SANDBOX_ISOLATION=required` refuses to run any skill without it.

- **Hardened service**: in production OwnClaw runs as a dedicated `ownclaw` system user with no login shell, under systemd with:
  - the whole filesystem read-only, but for OwnClaw's own data, skills, logs and backups
  - home directories hidden, and a private `/tmp`
  - OwnClaw's code, its unit file and `deploy.sh` owned by root, so nothing the service runs can change them
  - no way to gain privileges: `sudo`, `su` and other setuid programs give it nothing (`NoNewPrivileges`)

  Every skill runs inside these limits, with an allow-listed environment: the service's API keys and tokens are not in it.

- **Containers**: where Docker is installed, a skill that needs OS packages (nmap, net-tools, …) runs in an image built for it, with no raw sockets, no way to gain privileges, and its code and `SKILL.yaml` mounted read-only. Without a container runtime it runs like any other skill.

- **Critic agent**: before any tool runs, a rule-based critic checks the call — an unknown tool, a missing parameter, the same call repeated, a new skill that duplicates an existing one — and blocks it, and flags actions with side effects such as `shell_exec`.

- **No runaway work**: a skill that goes silent is stopped (stall detection), and a task ends when it is done, when you press Stop, or when it stops making progress.

### Accounts and access

- Only the owner creates accounts (`/user add`, or Settings → Accounts) and links Telegram IDs to them.
- The ops API is disabled unless a token of at least 32 characters is set in the environment; the token is never stored in the database. It can run a task as any account, so treat it like the owner's password.
- Chat links can be signed, so a message prepared by an alert shows as verified (see below).

### Cloud API security

- Cloud LLM API keys come from the environment (`.env`) or, when entered on the settings page, are kept in the database — never in code or in files checked into source control.
- Token budgets can cap daily and per-task cloud spending.
- Prompt caching (Anthropic) reduces redundant token usage across multi-turn conversations.

## Running

### Production deploy (Linux)

A single script handles everything — JDK, Python, the systemd service and auto-updates:

```bash
# Upload deploy.sh to the server, then:
chmod +x deploy.sh
sudo ./deploy.sh --setup
```

The setup wizard prompts for your GitHub token and API keys, then installs all dependencies, builds the JAR, and starts the service. It also offers to install Ollama and pull the default model. See [deploy/README.md](deploy/README.md) for full details, rollback, and cron configuration.

### Local development

```bash
# Linux / macOS
export ANTHROPIC_API_KEY=sk-ant-...
./run.sh

# Windows
set ANTHROPIC_API_KEY=sk-ant-...
run.bat
```

The run script builds automatically if the JAR doesn't exist yet. Open `http://localhost:8080` in your browser. Type `/help` in the chat for the commands.

### Prerequisites (dev mode)

- Java 21+
- Ollama with a model loaded (e.g. `ollama pull qwen2.5:14b`)
- An Anthropic or OpenAI API key (none is needed in Free mode)
- Python 3.11+

### Configuration

All settings are in `src/main/resources/application.yaml` and can be overridden with environment variables. Time vs cost, thinking effort and the cloud provider can also be changed on the settings page.

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Anthropic API key for cloud LLM |
| `OPENAI_API_KEY` | — | OpenAI API key (alternative provider) |
| `OWNCLAW_MENTOR_PROVIDER` | `openai` | Cloud provider: `openai` or `anthropic` |
| `OWNCLAW_ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | Anthropic model name |
| `OWNCLAW_EXECUTOR_URL` | `http://localhost:11434` | Ollama API endpoint |
| `OWNCLAW_EXECUTOR_MODEL` | `qwen2.5:14b` | Local LLM model name |
| `OWNCLAW_PREFER_COST` | `true` | Cheaper: the cloud plans, the local model runs the skills (`false` = Fastest) |
| `OWNCLAW_LOCAL_ONLY` | `false` | Free: call no cloud model |
| `OWNCLAW_THINKING_EFFORT` | `high` | `low`, `medium` or `high` |
| `OWNCLAW_TELEGRAM_ENABLED` | `false` | Turn on the Telegram bot |
| `TELEGRAM_BOT_TOKEN` | — | The bot's token |
| `OWNCLAW_OPS_TOKEN` | — | Enables the ops API (at least 32 characters) |
| `OWNCLAW_SANDBOX_ISOLATION` | `auto` | `auto`: jail skills once bubblewrap works; `required`: never run a skill without the jail; `off`: no jail (development, Windows) |
| `OWNCLAW_SANDBOX_EXPOSE` | — | Host paths a jailed skill may still read, comma-separated |
| `OWNCLAW_SERVER_PORT` | `8080` | HTTP server port |

### Links that open a message in the chat

An alert or another tool can link to the chat with a message ready in the box:

```
https://<host>/#task=<URL-encoded text>[&new=1][&title=<URL-encoded>][&exp=<unix time>&sig=<hex>]
```

- Everything goes after `#`, so the text never reaches the server, a proxy's logs or a referrer.
- `task` is the message, cut at 1,500 characters. `new=1` starts a new chat, titled `title`.
- These messages are refused:
  - one the chat would read as a command (it starts with `/`);
  - one holding invisible characters, such as Unicode tags, direction controls, zero-width spaces, soft hyphens or variation selectors (the emoji joiners are fine).
- Runs of blank lines are closed up.
- The page puts the message in the box, after the login if one is needed, and takes it out of the address. It never sends it: the user reads it and presses Send.
- Encode with `encodeURIComponent` / `urllib.parse.quote(text, safe='')`. A `+` is read as a space.
- **Signed links** show "verified" above the box. Set `sig = hex(HMAC-SHA256(secret, exp + "\n" + task))`, where `task` is the text before URL encoding and `exp` is a unix time in seconds.
  - The secret is `GUARDIAN_LINK_SECRET` in the vault of the account that opens the link (`/cred set GUARDIAN_LINK_SECRET <secret>`).
  - The page checks the signature with `POST /api/links/verify`, so the secret never reaches the browser.

## Tech stack

- **Backend**: Java 21, Spring Boot, SQLite (Liquibase migrations)
- **Frontend**: Vanilla HTML/CSS/JS, WebSocket for real-time updates
- **Messaging**: web chat, Telegram (Bot HTTP API)
- **LLM providers**: Anthropic (Claude), OpenAI, Ollama
- **Skill runs**: Python processes with per-skill venvs, in a bubblewrap jail; Docker for skills that need OS packages
- **Build**: Gradle

## License

MIT License
