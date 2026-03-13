# 🦀 OwnClaw

A self-hosted, self-learning AI agent that runs on your own hardware. Give it a task in natural language and it figures out the rest — creating tools, executing code, delegating work, and learning from every interaction.

## What it does

OwnClaw is an autonomous agent system that receives tasks via a web chat UI and executes them through a reactive Think → Act → Observe loop. It can browse the web, run shell commands, parse documents, manage files, interact with APIs, and create new Python tools on the fly when none of its existing skills fit the task.

Unlike chatbots that just produce text, OwnClaw *does things*. Ask it to scan your network, monitor a service, parse your email, or schedule a recurring report — it will build the tools it needs, execute them, and deliver results.

## Architecture

```
User ──► Web Chat UI ──► AgentLoop
                            │
                   ┌────────┼────────┐
                   │        │        │
              Cloud LLM  Local LLM  Tools
            (orchestrator) (executor) (skills)
                   │        │        │
                   └────────┼────────┘
                            │
                    Container Sandbox
                      (Podman/Docker)
```

### Dual-LLM design

The system runs two LLMs with distinct roles:

- **Cloud LLM** (Anthropic Claude / OpenAI) drives the main agent loop. It reasons about the task, decides which tool to call, evaluates results, and determines next steps. Every decision flows through the cloud model.

- **Local LLM** (Ollama, e.g. qwen2.5:14b) executes delegated work. When the cloud model identifies routine multi-step work (multiple shell commands, batch API calls), it packages a structured delegation plan and hands it off to the local model. This saves cloud tokens while keeping execution quality high for simple sequential tasks.

The cloud model can also fall back to local-only mode when the cloud API is unavailable.

### Why this saves money

Cloud LLM APIs charge per token. A typical agent task might take 10-30 tool calls — and every call means a full round-trip through the cloud model with the entire conversation context. That context grows with each step, so later calls are disproportionately expensive.

OwnClaw's delegation system short-circuits this: when the cloud model recognizes a sequence of routine steps ("run these 5 shell commands and collect the output"), it emits a single delegation plan instead of 5 separate tool calls. The local LLM executes the plan autonomously, and only the final result goes back to the cloud model. This can cut cloud token usage by 50-80% on tasks with many routine steps, since the local model runs on your own hardware at zero marginal cost.

By contrast, OpenClaw sends every single action through its cloud LLM — there is no delegation path. Every shell command, every API call, every file read costs cloud tokens. For a 20-step task, that means 20 cloud round-trips with ever-growing context windows.

### Skill system

Skills are Python scripts with a standard contract (`def run(params) → {output, success}`). The agent creates new skills on demand — the cloud LLM generates the code, the system installs pip dependencies into an isolated venv, and the skill becomes available immediately for current and future tasks.

Skills persist across sessions. Over time, the agent accumulates a growing library of capabilities tailored to the user's actual needs.

### Other components

- **Task queue** with priority scheduling, multi-user fairness, and stall detection
- **Episodic memory** — the agent remembers past task outcomes and learns from them
- **Credential vault** — AES-256-GCM encrypted storage for API keys and passwords
- **Scheduled tasks** — one-shot and recurring (cron-style) task execution
- **Conversation compression** — long chat histories are compressed to fit context windows
- **Real-time status** — WebSocket-based live updates with token counters during execution

## Key differences from OpenClaw

OwnClaw started as a fork of the [OpenClaw](https://github.com/BionicClick/OpenClaw) concept but has diverged significantly in architecture and philosophy:

| | OpenClaw | OwnClaw |
|---|---|---|
| **Execution model** | Plan-first: generates a complete DAG upfront, then executes it blind | Reactive loop: one action at a time, observe result, reason about next step |
| **LLM routing** | Cloud-only, single LLM | Dual-LLM: cloud orchestrates, local executes delegated plans |
| **Skill creation** | Manual or pre-built skill library | On-the-fly: cloud LLM generates Python code, auto-installs dependencies |
| **Error recovery** | Skill repair loop (diagnose → patch code → retry) | Trajectory-aware: critic agent, reflection injection, retry guards |
| **Memory** | Plan cache (exact-match only) | Episodic memory, agent facts, conversation history with compression |
| **Multi-user** | Single-user | Multi-user with isolated profiles, credentials, and skill libraries |
| **Credential handling** | Config files or hardcoded | Encrypted vault with per-user isolation, injected as env vars at runtime |
| **Sandbox** | Basic process execution | Podman containers with network policies, stall detection, per-skill venvs |
| **Cost control** | None — every action hits the cloud API | Token budgets, local delegation (50-80% cloud savings on routine work), prompt caching, rate-limit back-off |
| **UI** | CLI / basic web | Chat UI with real-time status, token counters, debug mode, settings panel |

The fundamental architectural difference: OpenClaw's plan-first model cannot handle tasks that require observing intermediate results before deciding what to do next (which is most real-world tasks). OwnClaw's reactive loop solves this — the agent sees every tool result and adapts its strategy accordingly.

## Security model

### Nothing leaves your network by default

- The local LLM runs on your hardware via Ollama. All delegation work stays local.
- Skills execute in **Podman containers** (Linux) with `--network=none` by default. A skill cannot reach the internet unless explicitly allowed in its manifest.
- On development machines (Windows), skills run as local processes with the same user permissions as OwnClaw.

### Credential isolation

- User credentials (API keys, passwords, tokens) are stored in an **AES-256-GCM encrypted vault** backed by SQLite.
- Credentials are **never passed through LLM prompts**. They are injected as environment variables directly into the skill's execution environment at runtime.
- Each user's credentials are isolated — users cannot access each other's vault.
- The agent can request credentials from the user via `ask_user`, store them securely, and inject them into skills that declare a dependency on specific credential keys.

### Execution sandbox

- **Container isolation**: Skills run inside `python:3.11-slim` Podman containers with:
  - Configurable network access (default: denied)
  - Hard timeout with stall detection (killed if no output for N seconds)
  - No access to the host filesystem beyond the skill's working directory
  - Per-skill Python venvs with pinned dependencies

- **Critic agent**: Before any tool executes, a critic evaluates the action for safety. It can block dangerous operations and inject warnings for side-effect-producing tools like `shell_exec`.

- **Retry guards**: Circuit breakers prevent runaway loops — skill creation is capped at 2 failures per skill name, 3 total per task.

### Cloud API security

- Cloud LLM API keys are stored as environment variables, never in code or config files checked into source control.
- Token budgets can cap daily and per-task cloud spending.
- Prompt caching (Anthropic) reduces redundant token usage across multi-turn conversations.

## Running

### Production deploy (Linux)

A single script handles everything — JDK, Python, Podman, systemd service, and cron-based auto-updates:

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

The run script builds automatically if the JAR doesn't exist yet. Open `http://localhost:8080` in your browser.

### Prerequisites (dev mode)

- Java 21+
- Ollama with a model loaded (e.g. `ollama pull qwen2.5:14b`)
- An Anthropic or OpenAI API key
- Python 3.11+ (skills run as local processes on Windows; on Linux production they run in Podman containers)

### Configuration

All settings are in `src/main/resources/application.yaml` and can be overridden with environment variables:

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Anthropic API key for cloud LLM |
| `OPENAI_API_KEY` | — | OpenAI API key (alternative provider) |
| `OWNCLAW_EXECUTOR_URL` | `http://localhost:11434` | Ollama API endpoint |
| `OWNCLAW_EXECUTOR_MODEL` | `qwen2.5:14b` | Local LLM model name |
| `OWNCLAW_MENTOR_PROVIDER` | `openai` | Cloud provider: `openai` or `anthropic` |
| `OWNCLAW_SERVER_PORT` | `8080` | HTTP server port |

## Tech stack

- **Backend**: Java 21, Spring Boot, SQLite (Liquibase migrations)
- **Frontend**: Vanilla HTML/CSS/JS, WebSocket for real-time updates
- **LLM providers**: Anthropic (Claude), OpenAI, Ollama
- **Sandbox**: Podman containers (Linux), ProcessBuilder (Windows)
- **Build**: Gradle

## License

MIT License
