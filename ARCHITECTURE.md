# OwnClaw — Architecture Design Document

**Version**: 0.3  
**Date**: 2025-07-12  
**Status**: Active development — reactive agent loop implemented

---

## 1. Vision

OwnClaw is an autonomous, self-learning AI agent system built around a **dual-LLM architecture**:

- **Cloud LLM (Orchestrator)** — a large cloud LLM (OpenAI or Anthropic, configurable) that **always** drives the main agent loop: reasoning, planning, deciding which tools to call, and evaluating results. The cloud LLM is the "brain" — it sees every message, makes every decision, and owns the Think→Act→Observe loop.
- **Local LLM (Executor)** — a small local LLM (7-14B via Ollama, e.g. qwen2.5:14b) that executes **delegated multi-step plans**. The cloud LLM can issue a `delegate` action with a structured plan (goal, ordered steps, checkpoints), and the local LLM executes it autonomously in a mini agent loop, returning consolidated results.
- **SkillRunner** — the execution engine that runs Python skill scripts in sandboxed environments (Podman containers on Linux, ProcessBuilder on Windows).

The core innovation is the **cloud-as-orchestrator, local-as-executor** pattern: the cloud LLM makes all high-level decisions and can offload routine multi-step work to the local LLM via structured delegation plans. This gives the system cloud-grade reasoning while leveraging local compute for bulk execution. The local LLM is a fallback — if the cloud is unavailable, the system degrades to local-only mode.

The system supports **multiple users**, each with isolated profiles, credentials, skill libraries, and conversation state.

> **Architecture evolution note**: The original design (v0.1–v0.2) described a "Mentor/Executor" model where the local LLM classified tasks, matched skills, and decided when to escalate to the cloud "Mentor" for planning. This has been replaced by a **reactive agent loop** where the cloud LLM always orchestrates, using a Think→Act→Observe cycle. The `delegate` action replaces the old `local_llm` action, providing structured plan-based delegation instead of free-form local LLM conversations. Some sections of this document still reference the original design terminology.

### Design Principles

1. **Cloud tokens are precious** — every cloud LLM call must be justified and compressed
2. **Local compute is free** — offload everything possible to the local Executor
3. **Skills compound** — every new skill learned makes the system more capable over time
4. **Security by isolation** — credentials and execution happen in sandboxed environments
5. **Multi-user from day one** — no single-user assumptions in the architecture
6. **Observe everything** — every decision, token spend, and skill execution is logged and user-visible
7. **Chat-centric UX** — the user sees concise status messages in the conversation, not a separate dashboard
8. **Skills are generic** — the system must handle any arbitrary task without foreknowledge of it.
   Skills are the execution primitive; the planning loop figures out how to accomplish goals
   dynamically. Never hardcode task-specific knowledge into prompts.

### Prompt Design Rules (CRITICAL — read before touching any prompt)

These rules exist to keep OwnClaw generic and prevent regressions where a "fix" for one
scenario breaks the system's ability to handle all others.

- **Prompts describe the mechanism, not the task.** System prompts tell the LLM what it
  *is* and what *tools* it has. They do NOT contain knowledge about specific technologies,
  APIs, or how to solve particular classes of problem. That knowledge belongs in skills.

- **Nothing is pre-injected.** Not config values, not service URLs, not platform hints beyond
  what is universally true at plan time (OS name, shell, current datetime). If a task requires
  knowing the deployment environment (e.g. what URL is the local LLM running at), the plan
  uses `get_system_info` as a first step — a Python skill that reads `application.yaml` from
  disk and returns the facts. Every piece of context is earned by executing a skill, never
  handed to the planner for free.

- **The planning loop is the intelligence, not the prompt.** When a step fails, the
  completeness evaluator + follow-up planner figure out what to try next. If they can't
  figure it out alone, the Mentor requests a new skill be auto-generated. Correct failures
  at the loop level, not by pre-programming the answer into the system prompt.

- **PromptStrategies is the right place for domain guidance.** If guidance is truly
  needed for a class of tasks (web scraping, email, etc.), it goes into `PromptStrategies`
  as a named strategy, injected only when that domain is detected — never always.

---

## 2. System Overview

### 2.1 Component Roles

| Component | What it is | What it does | Runs on |
|-----------|-----------|-------------|---------|
| **AgentLoop** | Main agent loop | Drives the Think→Act→Observe cycle: cloud LLM reasons, picks an action, system executes it, result feeds back | Java (Spring Boot) |
| **ThinkingEngine** | Prompt builder + LLM caller | Builds system/user prompts, calls the selected LLM provider, parses structured JSON responses | Java |
| **LlmRouter** | Provider selector | Always selects cloud LLM; falls back to local only if cloud is unavailable | Java |
| **LocalExecutor** | Delegation executor | Receives structured `DelegationPlan` from cloud, runs a mini Think→Act→Observe loop using local LLM to execute the steps | Java + Ollama |
| **ToolRegistry** | Tool dispatcher | Registers all available tools (skills, shell, file ops, etc.), dispatches tool calls from the agent | Java |
| **SkillRunner** | Skill execution engine | Runs Python skill scripts in sandboxed containers (Podman/ProcessBuilder), manages I/O | Java + Python |
| **Cloud LLM** | Orchestrator | Reasons about tasks, decides actions, creates delegation plans, evaluates results | Cloud API (OpenAI, Anthropic) |
| **Local LLM** | Executor | Executes delegated multi-step plans autonomously, returns consolidated results | Ollama (qwen2.5:14b) |

**Key architectural rule**: The cloud LLM **always** orchestrates the main loop. The local LLM is only used when the cloud issues a `delegate` action with a structured plan, or as a degraded fallback when the cloud is unavailable.

### 2.2 Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                       OwnClaw Core (Java / Spring Boot)                │
│                                                                        │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────────┐           │
│  │  Telegram Bot │  │ WebUI (WS+HTTP)│  │ REST API         │           │
│  │  (per-user    │  │ (per-user       │  │ (future/         │           │
│  │   sessions)   │  │  sessions)      │  │  integrations)   │           │
│  └──────┬───────┘  └───────┬────────┘  └────────┬─────────┘           │
│         │                  │                     │                     │
│         └──────────────┬───┴─────────────────────┘                     │
│                        ▼                                               │
│  ┌──────────────────────────────────────────────────┐                  │
│  │               User Session Manager                │                  │
│  │  (auth, profile lookup, rate limiting, routing)   │                  │
│  └──────────────────────┬───────────────────────────┘                  │
│                         ▼                                              │
│  ┌──────────────────────────────────────────────────┐                  │
│  │               AgentLoop (Main Agent Loop)         │                  │
│  │                                                   │                  │
│  │  Reactive Think→Act→Observe cycle:                │                  │
│  │  1. ThinkingEngine builds prompt + calls Cloud LLM│                  │
│  │  2. Cloud LLM responds with a JSON action         │                  │
│  │  3. AgentLoop dispatches action to ToolRegistry   │                  │
│  │  4. Observation (result) feeds back into next turn│                  │
│  │  5. Repeat until Cloud LLM emits "answer" action  │                  │
│  │                                                   │                  │
│  │  Special actions:                                 │                  │
│  │  • "delegate" → LocalExecutor (structured plan)   │                  │
│  │  • "skill_create" → generates new Python skill    │                  │
│  │  • "answer" → final response to user              │                  │
│  └────────┬─────────────┬───────────────────────────┘                  │
│           │             │                                              │
│   ┌───────▼───────┐  ┌─▼────────────────────────────────────────┐     │
│   │  Cloud LLM     │  │  LocalExecutor (Ollama)                   │     │
│   │  (Orchestrator) │  │                                          │     │
│   │                │  │  Receives DelegationPlan from cloud:      │     │
│   │ Providers:     │  │  • goal: what to accomplish               │     │
│   │ • OpenAI       │  │  • steps: ordered [{description,          │     │
│   │ • Anthropic    │  │           tool, params}, ...]             │     │
│   │                │  │  • checkpoints: verification points       │     │
│   │ Always drives  │  │  • max_steps: execution limit             │     │
│   │ main loop.     │  │                                          │     │
│   │ Decides ALL    │  │  Runs mini Think→Act→Observe loop         │     │
│   │ actions.       │  │  using local LLM (qwen2.5:14b).          │     │
│   │                │  │  Returns consolidated results to cloud.   │     │
│   └───────────────┘  └──────────────────────────────────────────┘     │
│                                                                        │
│  ┌──────────────────────────────────────────────────┐                  │
│  │              ToolRegistry + Skills                │                  │
│  │                                                   │                  │
│  │  ┌─────────────┐ ┌────────────┐ ┌─────────────┐  │                  │
│  │  │  Built-in    │ │  Python    │ │  MCP Bridge │  │                  │
│  │  │  tools       │ │  Skills    │ │  tools      │  │                  │
│  │  │  (shell,     │ │  (sandbox) │ │             │  │                  │
│  │  │   file,      │ │            │ │             │  │                  │
│  │  │   http...)   │ │            │ │             │  │                  │
│  │  └─────────────┘ └────────────┘ └─────────────┘  │                  │
│  └──────────────────────┬───────────────────────────┘                  │
│                         ▼                                              │
│  ┌──────────────────────────────────────────────────┐                  │
│  │              Container Sandbox (Podman/Process)    │                  │
│  │                                                   │                  │
│  │  Linux:   Podman container with python:3.11-slim  │                  │
│  │  Windows: ProcessBuilder (dev mode)               │                  │
│  │                                                   │                  │
│  │  • Credential injection via env vars              │                  │
│  │  • Per-skill venv with pip dependencies           │                  │
│  │  • Network: configurable (allow/deny per skill)   │                  │
│  │  • Timeout: hard kill after configurable limit    │                  │
│  │  • I/O: JSON params → stdout result + progress    │                  │
│  └──────────────────────────────────────────────────┘                  │
│                                                                        │
│  ┌───────────────┐ ┌────────────────┐ ┌──────────────────────┐        │
│  │ User Profiles  │ │ Credential     │ │ Conversation Store   │        │
│  │ (H2/SQLite)    │ │ Vault          │ │ (H2, compressed)     │        │
│  │                │ │ (encrypted)    │ │                      │        │
│  └───────────────┘ └────────────────┘ └──────────────────────┘        │
│                                                                        │
│  ┌──────────────────────────────────────────────────┐                  │
│  │              Event Log (Observability)             │                  │
│  │  • Task traces (decisions, durations, outcomes)   │                  │
│  │  • Token usage per task/user                      │                  │
│  │  • Skill execution logs                           │                  │
│  │  • Streamed to user chat as concise status msgs   │                  │
│  └──────────────────────────────────────────────────┘                  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Task Queue & Concurrency

### 3.1 The Ollama Bottleneck

A single Ollama instance processes one inference request at a time (GPU-bound). Multiple users and tasks compete for this resource. The Task Queue is the central scheduler.

### 3.2 Queue Design

```
┌──────────────────────────────────────────────┐
│                 Task Queue                    │
│                                              │
│  Priority Levels:                            │
│  P0 — Interactive user waiting (classify,    │
│        compress, skill-match)                │
│  P1 — Plan execution (Executor following     │
│        Mentor's plan)                        │
│  P2 — Background (scheduled cron tasks,      │
│        conversation compression, cleanup)    │
│                                              │
│  Fairness:                                   │
│  • Round-robin across users at same priority │
│  • No user can hold >N consecutive slots     │
│  • Starvation timeout: P2 promoted after Xs  │
│                                              │
│  Queue Item:                                 │
│  {                                           │
│    task_id, user_id, priority,               │
│    type: "inference" | "skill_exec",         │
│    payload, callback,                        │
│    enqueued_at, deadline                     │
│  }                                           │
│                                              │
│  Key rule: skill_exec (SkillRunner) does NOT │
│  need Ollama — runs concurrently with infer. │
│  Only "inference" items are serialized.      │
└──────────────────────────────────────────────┘
```

### 3.3 Concurrency Rules

| Operation | Needs Ollama? | Can run in parallel? |
|-----------|:---:|:---:|
| Executor: classify task | Yes | No (queued) |
| Executor: match skills | Yes | No (queued) |
| Executor: compress context | Yes | No (queued) |
| Mentor: plan/review | No (cloud API) | Yes (independent) |
| SkillRunner: execute script | No (Python process) | Yes (multiple sandboxes) |
| Executor: compress results | Yes | No (queued) |

**This means**: While Executor is doing inference for User A, SkillRunner can simultaneously execute scripts for User B, and a Mentor API call for User C can be in flight. Only Ollama inference is serialized.

### 3.4 Async & Long-Running Tasks

Not all tasks complete within a single request-response cycle. The queue supports:

| Task Type | Example | Behavior |
|-----------|---------|----------|
| **Immediate** | "What time is it in Tokyo?" | Executor handles, responds inline |
| **Short task** | "Email John the notes" | Queue → plan → execute → respond (seconds) |
| **Long-running** | "Download this 2GB dataset" | SkillRunner starts, user gets "⏳ Started download...", completion notification later |
| **Deferred** | "Remind me in 2 hours" | Stored in scheduler, fires later |
| **Scheduled** | "Backup my notes every night at 3am" | Cron entry, executes on schedule, results posted to chat |
| **Continuous** | "Monitor email and forward from John" | Persistent skill with webhook/polling, runs until stopped |

**Task state machine**:
```
QUEUED → PLANNING → EXECUTING → REVIEWING → COMPLETED
                  ↘ WAITING_USER (needs input)    ↗
                  ↘ FAILED → RETRYING ────────────┘
                  ↘ DEFERRED (scheduled for later)
```

Long-running tasks emit periodic progress messages to the user's chat:
```
⏳ Starting backup...
📁 Compressing files (3/7)...
📁 Compressing files (7/7)...
☁️ Uploading to S3 (45%)...
✅ Backup complete. 2.3GB uploaded in 4m12s.
```

---

## 4. Dual-LLM Communication Protocol

> **Architecture change (v0.3)**: This section has been rewritten to reflect the current **cloud-as-orchestrator** architecture. The original v0.2 design used a confidence-based routing model where the local "Executor" LLM classified tasks and decided when to escalate to the cloud "Mentor." That model has been replaced with a simpler, more reliable design: the cloud LLM **always** drives the main agent loop, and the local LLM only executes structured delegation plans.

### 4.1 Core Principle: Cloud Orchestrates, Local Executes

```
                    User message
                         │
                         ▼
              ┌─────────────────────┐
              │  ThinkingEngine      │
              │  builds prompt +     │
              │  calls Cloud LLM    │
              └──────────┬──────────┘
                         │
              Cloud LLM responds with
              a JSON action:
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    tool call        delegate         answer
    (direct)     (to local LLM)    (final response)
         │               │               │
         ▼               ▼               ▼
    ToolRegistry    LocalExecutor    Return to user
    executes it     runs plan with
                    local LLM
         │               │
         └───────┬───────┘
                 ▼
           Observation
           feeds back
           into next
           Think cycle
```

**Key rules**:
- The cloud LLM sees **every** message and makes **every** decision in the main loop
- The local LLM is **never** called directly in the main loop — only via `delegate`
- `LlmRouter.selectProvider()` always returns the cloud provider; local is only a fallback if cloud is unavailable
- All tools except `skill_create` are available to the local LLM during delegation

### 4.2 The Agent Loop (Think → Act → Observe)

The main agent loop (`AgentLoop.java`) runs a reactive cycle:

```
┌──────────────────────────────────────────────────────────┐
│                    AgentLoop                              │
│                                                          │
│  1. Build messages: system prompt + conversation history │
│     + last observation                                   │
│  2. Call Cloud LLM via ThinkingEngine                    │
│  3. Parse JSON response → action + params               │
│  4. If action == "answer" → return response to user      │
│  5. If action == "delegate" → LocalExecutor.execute()    │
│  6. If action == tool call → ToolRegistry.execute()      │
│  7. Record observation (tool result or delegation result) │
│  8. Go to step 1                                         │
│                                                          │
│  Max iterations: configurable (default 25)               │
│  Conversation compression after N messages               │
│  Prompt caching for Anthropic (CACHE_BOUNDARY_MARKER)    │
└──────────────────────────────────────────────────────────┘
```

### 4.3 The `delegate` Action

When the cloud LLM determines that a task involves multiple routine steps that don't require cloud-grade reasoning, it can delegate work to the local LLM via a **structured delegation plan**.

**Action format** (as emitted by cloud LLM):
```json
{
  "action": "delegate",
  "params": {
    "goal": "Find all Python files in the project and count lines of code",
    "steps": [
      {
        "description": "List all .py files recursively",
        "tool": "shell",
        "params": {"command": "find . -name '*.py' -type f"}
      },
      {
        "description": "Count lines in each file",
        "tool": "shell",
        "params": {"command": "wc -l $(find . -name '*.py' -type f)"}
      }
    ],
    "checkpoints": ["Verify files were found before counting"],
    "max_steps": 10
  }
}
```

**DelegationPlan record** (`DelegationPlan.java`):
```java
public record DelegationPlan(
    String goal,
    List<Step> steps,
    List<String> checkpoints,
    int maxSteps
) {
    public record Step(
        String description,
        String tool,
        Map<String, Object> params
    ) {}
}
```

### 4.4 LocalExecutor — Mini Agent Loop

`LocalExecutor.java` receives a `DelegationPlan` and executes it using the local LLM (Ollama qwen2.5:14b) in a self-contained mini agent loop:

```
┌──────────────────────────────────────────────────────────┐
│                    LocalExecutor                          │
│                                                          │
│  Input:  DelegationPlan + AgentContext                   │
│                                                          │
│  1. Build executor system prompt:                        │
│     - Plan goal + ordered steps                          │
│     - Available tools (all except skill_create)          │
│     - Rules: follow the plan, use checkpoints,           │
│       emit "done" when finished                          │
│                                                          │
│  2. Mini Think→Act→Observe loop (local LLM):             │
│     a. Call local LLM with plan context + history        │
│     b. Parse response → tool call or "done" signal       │
│     c. If tool call → executeToolDirect() via ToolRegistry│
│     d. Record result as observation                      │
│     e. Repeat until "done" or maxSteps reached           │
│                                                          │
│  3. Return consolidated results to AgentLoop             │
│     (which feeds them back to cloud LLM as observation)  │
│                                                          │
│  Constraints:                                            │
│  • skill_create is BLOCKED (only cloud can create skills)│
│  • maxSteps hard limit prevents runaway execution        │
│  • Partial results returned on timeout/error             │
│  • All tool execution goes through same ToolRegistry     │
└──────────────────────────────────────────────────────────┘
```

### 4.5 LLM Provider Routing

`LlmRouter.java` implements a simple routing policy:

```
selectProvider():
  if cloudProvider is available:
    return cloudProvider          ← ALWAYS cloud for main loop
  else:
    return localProvider          ← degraded mode only
```

The old architecture had complex routing logic (confidence thresholds, circuit breakers, blocked-tool escalation, thinking-failure tracking). All of that has been removed. The cloud LLM is always the orchestrator; if it's unavailable, the system degrades to local-only mode where the local LLM drives the main loop directly (reduced capability but functional).

### 4.6 Prompt Caching (Anthropic)

When using Anthropic as the cloud provider, the system supports **prompt caching** to reduce costs:

- The system prompt is split into two parts by a `CACHE_BOUNDARY_MARKER`
- **Before the marker**: Static content (system identity, tool descriptions, rules) — cached across requests
- **After the marker**: Dynamic content (teaching log, user preferences, conversation context) — not cached
- Anthropic's cache breakpoints are set on the static portion, so repeated calls within the cache TTL (~5 minutes) pay reduced input token costs

### 4.7 Graceful Degradation

| Capability | Cloud Available | Cloud Unavailable (Degraded) |
|-----------|----------------|------------------------------|
| Main agent loop | Cloud LLM orchestrates | Local LLM orchestrates (reduced quality) |
| Tool execution | All tools available | All tools available |
| Delegation | Cloud delegates to local | N/A (local is already the main loop) |
| Skill creation | Available | Available (local LLM generates, lower quality) |
| Conversational chat | Cloud quality | Local quality |

### 4.8 Teaching & Skill Improvement

The teaching system remains conceptually similar to v0.2, but is now driven by the cloud LLM's decisions in the main loop rather than a separate Mentor/Executor feedback pipeline:

1. **Teaching log**: Bounded append-only log (max 30 entries), injected into the cloud LLM's system prompt after `CACHE_BOUNDARY_MARKER`
2. **Skill repair**: When a skill fails, the cloud LLM in the main loop decides whether to retry, repair, or create a new skill — no separate "Mentor review" step
3. **Distillation**: Periodic compression of teaching log entries to prevent prompt bloat
4. **Skills absorb knowledge**: Teaching notes are turned into code changes in skill scripts wherever possible, removing them from the prompt

---

## 5. Skill System

### 5.1 Skill Structure

```
skills/
├── manifest.json                 # Lightweight index (always loadable)
├── core/                         # Built-in skills (ship with OwnClaw)
│   ├── send_email/
│   │   ├── SKILL.yaml            # Metadata
│   │   ├── skill.py              # Implementation
│   │   └── requirements.txt      # Python deps
│   ├── browse_web/
│   ├── file_operations/
│   ├── shell_command/
│   ├── text_summarize/
│   ├── http_request/
│   └── schedule_task/
├── generated/                    # Mentor-created skills (system-wide)
│   └── {skill_name}/
│       ├── v1/                   # Versioned directories
│       ├── v2/
│       └── current -> v2         # Symlink to active version
└── users/                        # Per-user custom skills
    └── {user_id}/
        └── {skill_name}/
```

### 5.2 SKILL.yaml Format

```yaml
name: send_email
version: 2
description: "Send an email via SMTP with auto-detection of SSL/STARTTLS"
summary: "Sends email with subject and body to a recipient via SMTP"  # For manifest

# When should this skill be selected
triggers:
  keywords: [email, mail, send, message, notify]
  intents: [send_communication, notify_person]

# What the skill needs
parameters:
  - name: to
    type: string
    required: true
    description: "Recipient email address"
  - name: subject
    type: string
    required: true
  - name: body
    type: string
    required: true
  - name: attachments
    type: list
    required: false

# What credentials are needed (injected as env vars)
credentials:
  - SMTP_HOST
  - SMTP_PORT
  - SMTP_USER
  - SMTP_PASS

# Sandbox permissions
permissions:
  network: true          # Needs outbound network
  filesystem: read-only  # No fs writes needed
  timeout: 30            # Max execution seconds

# Skill metadata
complexity: low          # low | medium | high
created_by: system       # system | mentor | user
tested: true
reversible: false        # Can this action be undone?

# Interaction capabilities
interactive: false       # Can this skill request user input mid-execution?

# Version history
changelog:
  - version: 2
    date: "2026-02-20"
    change: "Auto-detect SSL vs STARTTLS based on port"
  - version: 1
    date: "2026-02-15"
    change: "Initial version"
```

### 5.3 manifest.json (Lightweight Index)

This file is what the Executor scans to preselect skills. It must stay small.

```json
{
  "skills": [
    {
      "name": "send_email",
      "version": 2,
      "summary": "Send email via SMTP with auto SSL detection",
      "keywords": ["email", "mail", "send", "notify"],
      "complexity": "low",
      "params": ["to", "subject", "body", "attachments?"],
      "credentials": ["SMTP_*"],
      "reversible": false,
      "interactive": false
    },
    {
      "name": "browse_web",
      "version": 1,
      "summary": "Fetch and extract content from a URL",
      "keywords": ["browse", "web", "url", "fetch", "scrape"],
      "complexity": "medium",
      "params": ["url", "selector?", "wait_for?"],
      "credentials": [],
      "reversible": true,
      "interactive": false
    }
  ]
}
```

**Size**: ~150-250 bytes per skill. Even with 200 skills, the manifest is ~50KB — easily fits in local LLM context. No need to send to Mentor.

### 5.4 Skill Matching

Skill matching is the most frequently-executed Executor operation. It must be efficient.

#### LLM-Based Matching

The Executor receives the user's task + manifest.json and uses a **structured prompt** to select skills:

```
Given this task and available skills, select the most relevant skills.

TASK: "{user_message}"

AVAILABLE SKILLS:
{manifest_json_content}

Respond with JSON: {"matches": [{"name": str, "confidence": 0.0-1.0, "reason": str}], "overall_confidence": 0.0-1.0}
```

This is a single local LLM call. The manifest is small enough to include in full for <100 skills. For larger manifests, a pre-filter by keyword overlap narrows to ~20 candidates before sending to the LLM.

#### Plan Cache (Skip Matching Entirely)

For **recurring tasks** (crons, repeated actions), the system maintains a plan cache:

```json
{
  "cache_key": "hash(normalized_task_description)",
  "plan": { ... },
  "hit_count": 47,
  "last_used": "2026-02-22T10:00:00Z",
  "skill_versions_at_creation": {"send_email": 2, "file_read": 1}
}
```

Cache invalidation triggers:
- A skill used in the cached plan is updated to a new version
- Plan has failed 2+ times since caching
- User explicitly requests re-planning (`/replan`)
- Cache entry age exceeds configurable TTL (default: 30 days)

When a cache hit occurs: **zero Ollama inference, zero cloud tokens**. SkillRunner just executes the cached plan directly.

### 5.5 Skill Lifecycle

```
                    ┌──────────────────┐
                    │   Task arrives    │
                    └────────┬─────────┘
                             ▼
                    ┌──────────────────┐
                    │ Check plan cache  │
                    └────────┬─────────┘
                        ┌────┴────┐
                        │         │
                   Cache hit  Cache miss
                        │         │
                        │         ▼
                        │  ┌──────────────────┐
                        │  │ Executor: LLM     │
                        │  │ skill matching    │
                        │  └────────┬─────────┘
                        │           ▼
                        │  ┌────────┴────────────┐
                        │  │                     │
                        │  Skills found    No skills match
                        │  │                     │
                        │  ▼                     ▼
                        │  Mentor plans    Mentor creates:
                        │  (normal flow)   • SKILL.yaml
                        │  │               • skill.py v1
                        │  │                     │
                        │  │                     ▼
                        │  │              SkillRunner tests
                        │  │              in sandbox (dry run)
                        │  │                     │
                        │  │                ┌────┴────┐
                        │  │                │         │
                        │  │              Pass      Fail
                        │  │                │         │
                        │  │                ▼         ▼
                        │  │           Add to      Mentor refines
                        │  │           manifest    (feedback loop)
                        │  │                │
                        │  └────────┬───────┘
                        │           ▼
                        └──→ Execute plan
                                    │
                                    ▼
                            Cache plan for
                            future reuse
```

### 5.6 Skill Versioning

Skills are versioned to allow safe updates without breaking cached plans.

```
skills/generated/weather_check/
├── v1/
│   ├── SKILL.yaml
│   ├── skill.py
│   └── requirements.txt
├── v2/
│   ├── SKILL.yaml       # Updated by Mentor
│   ├── skill.py          # Updated by Mentor
│   └── requirements.txt
└── current -> v2          # Symlink/pointer to active version
```

**Version lifecycle**:
1. Mentor generates v1 → tested → activated → added to manifest.
2. Teaching note triggers skill update → Mentor generates v2 → tested → activated.
3. `manifest.json` records current version number.
4. Plan cache entries record which skill versions they were built with. If version changes, cache is invalidated for plans using that skill.
5. Old versions are retained (configurable retention, default: keep last 3 versions) for rollback.
6. If v2 fails repeatedly, automatic rollback to v1 + alert to user.

**Manifest auto-rebuild**: The manifest is regenerated whenever a skill is added, updated, or removed. The skill engine watches the skills directories for changes.

### 5.7 Skill Generalization

When Mentor notices similar skills being created, it can:
1. Merge them into a more general skill
2. Create a skill "family" with shared base logic
3. Parameterize differences

Example: "send_slack_message" + "send_discord_message" → generalized "send_chat_message" with a `platform` parameter.

The Executor can propose generalization: "I have 3 skills that all send messages to different platforms. Should I ask Mentor to unify them?"

### 5.8 Skill Interaction (Mid-Execution User Input)

Some skills need to communicate with the user during execution. The I/O protocol supports this:

**Standard flow** (non-interactive skill):
```
Java → [JSON params via stdin] → skill.py → [JSON result via stdout] → Java
```

**Interactive flow** (skill with `interactive: true`):
```
Java → [JSON params via stdin] → skill.py
                                      │
                                skill writes to stdout:
                                {"type": "need_input", 
                                 "prompt": "Found 3 files matching 'meeting*'. Which one?",
                                 "options": ["meeting_feb20.md", "meeting_feb21.md", "meeting_feb22.md"]}
                                      │
                                      ▼
                                Java reads this, forwards to user chat:
                                "🔍 Found 3 files. Which one?
                                 1. meeting_feb20.md
                                 2. meeting_feb21.md
                                 3. meeting_feb22.md"
                                      │
                                User responds: "3"
                                      │
                                      ▼
                                Java writes to skill's stdin:
                                {"type": "user_input", "value": "meeting_feb22.md"}
                                      │
                                skill.py continues execution...
                                      │
                                {"type": "result", "status": "success", "output": {...}}
```

**Progress reporting** (any skill):
```
skill.py can emit multiple JSON lines to stdout:
  {"type": "progress", "message": "Downloading... 45%"}
  {"type": "progress", "message": "Downloading... 90%"}
  {"type": "result", "status": "success", "output": {...}}
```

Each `progress` message is forwarded to the user's chat. The final `result` message completes the execution.

### 5.9 Skill Dependency Management

Python dependencies are managed per skill group to balance isolation vs disk usage:

```
skills/
├── _envs/                        # Shared virtual environments
│   ├── base/                     # Common deps (requests, beautifulsoup4, etc.)
│   └── {skill_group}/            # Group-specific deps
├── core/
│   └── browse_web/
│       └── requirements.txt      # "requests>=2.31" → resolved in base env
└── generated/
    └── complex_scraper/
        └── requirements.txt      # "playwright>=1.40" → gets own env
```

**Rules**:
- Skills with only common dependencies use the shared `base` env.
- Skills with unique/heavy dependencies get their own venv (created on first use).
- `requirements.txt` specifies pinned versions. SkillRunner resolves which env to use.
- Env rebuild is triggered when requirements change (tracked by hash).

### 5.10 Skill Validation

Every skill — core, Mentor-generated, or user-provided — must pass validation before activation. `SkillValidator.java` enforces:

#### Static Checks (no execution)

1. **SKILL.yaml schema compliance**: All required fields present, correct types, version is integer
2. **Entry point exists**: `skill.py` file present and non-empty
3. **Function signature**: `skill.py` must be directly executable (`if __name__ == "__main__"` block)
4. **Stdout protocol**: Static scan for at least one `json.dumps({"type": "result", ...})` output path
5. **Banned imports**: Flag `os.system`, `subprocess.Popen` without sandboxed flag, `ctypes`, `socket` when `network: false` in SKILL.yaml
6. **Requirements parseable**: `requirements.txt` (if present) is pip-parseable, no git/URL sources (security risk)
7. **Size limits**: `skill.py` < 50KB, `requirements.txt` < 10 entries

#### Sandbox Dry Run

1. Inject test parameters (from `test_params` in skill creation request, or auto-generated stubs)
2. Execute in sandbox with **network disabled** and short timeout (10 seconds)
3. **Pass criteria**:
   - Exit code 0
   - stdout contains at least one valid JSON line with `"type": "result"`
   - stderr does not contain `Traceback` (Python exception indicator)
   - Execution completes within timeout
4. **On failure**: Skill is rejected with detailed error. If Mentor-generated, the error is fed back to Mentor for a retry (up to 2 attempts).

#### Post-Activation Monitoring

- First 3 real executions of a new skill are flagged in the event log as `skill.probationary`
- If 2+ of the first 3 executions fail: skill is auto-deactivated, previous version restored (if exists), user notified
- Monitoring resets when a skill is updated to a new version

---

## 6. Multi-User Model

### 6.1 User Profile

```
users/{user_id}/
├── profile.json          # Name, preferences, timezone, language
├── credentials.enc       # Encrypted credential vault
├── preferences.json      # Learned preferences (maintained by Executor)
├── credential_grants.json# Skill→credential approval records
├── skills/               # User-specific skills
│   └── {skill_name}/
├── conversations/        # Compressed conversation history
│   └── {session_id}.db
└── settings.json         # LLM preferences, token budgets, UI prefs
```

### 6.2 Profile Schema

```json
{
  "user_id": "uuid",
  "display_name": "John",
  "telegram_id": 123456789,
  "timezone": "Europe/Prague",
  "language": "en",
  "mentor": {
    "provider": "anthropic",
    "model": "claude-sonnet-4-20250514",
    "max_tokens_per_task": 10000,
    "daily_budget_tokens": 500000
  },
  "executor": {
    "model": "qwen2.5:14b",
    "temperature": 0.3
  },
  "sandbox": {
    "network_default": "deny",
    "max_execution_time": 60
  }
}
```

### 6.3 User Preferences (Local, Not Mentor)

User preferences are tracked **entirely by the Executor** (local LLM). The Mentor never sees or manages preferences — this avoids prompt bloat on cloud calls.

The Executor maintains a `preferences.json` per user:

```json
{
  "communication": {
    "preferred_email_style": "bullet points, concise",
    "default_recipients": {"notes": "john@example.com", "reports": "team@example.com"}
  },
  "behavior": {
    "confirmation_before_send": true,
    "verbose_progress": false,
    "default_summary_length": "short"
  },
  "learned_patterns": [
    {"pattern": "Every Monday 9am: summarize weekend emails", "confidence": 0.9, "occurrences": 12},
    {"pattern": "Meeting notes always go to john@", "confidence": 0.8, "occurrences": 5}
  ],
  "last_distilled": "2026-02-20T10:00:00Z"
}
```

**How preferences accumulate**:
1. Executor observes user corrections ("No, send bullets not paragraphs") and logs the preference.
2. After N interactions (configurable), Executor **self-distills**: reviews accumulated observations and produces a clean, non-redundant preferences.json using its own LLM inference.
3. Preferences are injected into Executor's prompt as a compact header (~100-200 tokens).
4. Preferences are **never** sent to Mentor. Mentor operates on task structure only.

### 6.4 Authentication

- **Telegram**: User identified by Telegram user ID. First message triggers registration flow.
- **WebUI**: Login via username/password or OAuth (configurable). Session token via JWT.
- **Admin**: First registered user becomes admin. Admin can manage other users.

### 6.5 User Isolation

| Resource | Isolation Level |
|----------|----------------|
| Conversations | Fully isolated per user |
| Credentials | Encrypted per user, separate keys |
| Credential grants | Per user — approval decisions are personal |
| Skills | Core shared + per-user custom |
| Preferences | Fully isolated, maintained by Executor locally |
| Sandbox | Separate sandbox per execution |
| Token budget | Per-user tracking and limits |

---

## 7. Conversation Management

### 7.1 Context Window Strategy

The local Executor LLM has a limited context window (typically 8K-32K tokens for 7-14B models). Strategy:

1. **Active context**: Last N messages (configurable, default 10) in full
2. **Compressed history**: Executor periodically summarizes older messages into a compact summary
3. **Skill results**: Only keep structured output, not raw execution logs
4. **User profile + preferences**: Injected as a compact header (~300 tokens total)
5. **System status messages**: Excluded from LLM context (UI-only)

### 7.2 Conversation Storage

SQLite table per user:

```sql
CREATE TABLE conversations (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,          -- user | executor | mentor | system | status
    content TEXT NOT NULL,
    compressed_content TEXT,     -- Executor-generated summary
    tokens_used INTEGER,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    metadata JSON               -- skill used, plan reference, etc.
);

CREATE TABLE session_summaries (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    summary TEXT NOT NULL,       -- Rolling compressed summary
    last_updated DATETIME,
    total_messages INTEGER,
    total_tokens_cloud INTEGER,
    total_tokens_local INTEGER
);
```

### 7.3 Prompt Compression

Executor handles all compression locally (zero cloud cost):

- **Message compression**: Reduce verbose messages to key information
- **History rolling summary**: Periodically compress N old messages into 1 summary paragraph
- **Result compression**: Before sending to Mentor, compress execution results to structured summaries
- **Skill output trimming**: Extract only relevant fields from skill outputs

---

## 8. Security Model

### 8.1 Sandbox Architecture

> **Note (v0.3)**: Production sandbox now uses **Podman containers** (python:3.11-slim image) instead of bubblewrap. The container-based approach provides better dependency isolation (per-skill venvs built inside containers) and more consistent cross-platform behavior.

```
┌─────────────────────────────────────────────┐
│              OwnClaw Process (Java)          │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │          ContainerSandbox               │  │
│  │                                         │  │
│  │  Linux: Podman (rootless containers)    │  │
│  │  • Image: python:3.11-slim             │  │
│  │  • Mount: skill dir (read-only)        │  │
│  │  • Mount: venv dir (per-skill)         │  │
│  │  • Network: configurable per skill     │  │
│  │  • Timeout: hard kill                  │  │
│  │  • Pip install on first run            │  │
│  │                                         │  │
│  │  Windows (dev): ProcessBuilder           │  │
│  │  • Working dir isolation                │  │
│  │  • Timeout via Future.get()             │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  Credential Injection:                       │
│  • Credentials decrypted in Java memory      │
│  • Passed to sandbox via env vars            │
│  • Env vars cleared after execution          │
│  • Never written to disk unencrypted         │
│  • ONLY approved credentials injected (§8.3) │
│                                              │
│  I/O Protocol:                               │
│  • Java writes JSON params to skill's stdin  │
│  • Skill writes JSON lines to stdout:        │
│    - {"type":"progress","message":"..."}     │
│    - {"type":"need_input","prompt":"..."}    │
│    - {"type":"result","status":"...","output"}│
│  • Stderr captured for debugging             │
│  • Exit code: 0 = success, non-zero = error  │
└─────────────────────────────────────────────┘
```

### 8.2 Credential Vault

- **Encryption**: AES-256-GCM, per-user encryption key
- **Key derivation**: PBKDF2 from user's master password (or generated key stored in OS keychain)
- **Storage**: Encrypted blob in SQLite
- **Access**: Skills declare required credentials in SKILL.yaml; only **approved** credentials are injected
- **Audit**: Every credential access is logged (skill, timestamp, user, grant type)

### 8.3 Credential Grant Approval

Skills must not silently access credentials. Every credential access requires explicit user approval.

**Grant types**:
- **`permanent`**: User approves once, skill can always use this credential. Stored in `credential_grants.json`.
- **`one-time`**: User approves for this execution only. Not stored.
- **`declined`**: User refuses. Stored so the system doesn't ask again (until user resets).

**Flow**:
```
SkillRunner about to execute "send_email"
  → needs SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS
        │
        ▼
Check credential_grants.json for this user:
  SMTP_HOST: permanent ✓ (approved 2026-02-01)
  SMTP_PORT: permanent ✓ (approved 2026-02-01)
  SMTP_USER: permanent ✓ (approved 2026-02-01)
  SMTP_PASS: permanent ✓ (approved 2026-02-01)
        │
  All approved → inject and execute
```

**First-time use (no grant exists)**:
```
SkillRunner about to execute "send_email"
  → needs SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS
        │
        ▼
No grant found for SMTP_* with skill "send_email"
        │
        ▼
Chat message to user:
  "🔑 Skill 'send_email' requests access to your SMTP credentials.
   (SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS)
   
   Allow? [Always] [Once] [Deny]"
        │
User responds: "Always"
        │
        ▼
credential_grants.json updated:
  {"skill":"send_email", "credentials":["SMTP_*"], 
   "grant":"permanent", "granted_at":"2026-02-22T14:30:00Z"}
        │
        ▼
Credentials injected → skill executes
```

**New skill requesting credentials the user hasn't seen before**:
```
Mentor-generated skill "api_check_stocks" requests BROKERAGE_API_KEY
        │
        ▼
Chat: "⚠️ New skill 'api_check_stocks' (generated by Mentor) 
       requests access to BROKERAGE_API_KEY.
       This skill was just created and has not been used before.
       Allow? [Always] [Once] [Deny]"
```

**Grants stored in** `credential_grants.json`:
```json
{
  "grants": [
    {"skill": "send_email", "credentials": ["SMTP_*"], "type": "permanent", "granted_at": "2026-02-01"},
    {"skill": "browse_web", "credentials": [], "type": "permanent", "granted_at": "2026-02-01"},
    {"skill": "api_check_stocks", "credentials": ["BROKERAGE_API_KEY"], "type": "declined", "declined_at": "2026-02-22"}
  ]
}
```

### 8.4 Threat Model

| Threat | Mitigation |
|--------|-----------|
| Malicious skill code | Sandbox (bwrap/chroot), no host filesystem access |
| Credential exfiltration | Network deny-by-default, only allow declared endpoints |
| Unauthorized credential access | Explicit user approval required (permanent/one-time/decline) |
| Mentor generating credential-grabbing skills | New skills requesting sensitive credentials trigger extra warning in chat |
| Prompt injection via user input | Executor uses structured I/O, not raw string interpolation |
| Mentor generating harmful skills | Skill validation: static analysis + sandbox test run before activation |
| Token budget exhaustion | Per-user hard limits, automatic cutoff |
| Multi-user data leakage | SQLite per-user isolation, separate encryption keys |
| Skill regression after update | Version rollback, old versions retained |

---

## 9. Error Handling & Recovery

### 9.1 System-Level Failures

All system-level failures (as opposed to skill/task-level failures) are handled with retry, circuit breaking, and graceful degradation. Every failure is logged to the event log and, when user-impacting, surfaced as a chat status message.

| Failure | Detection | Recovery | User Message |
|---------|-----------|----------|-------------|
| Ollama unreachable | Health check ping (periodic) | Retry with backoff (3×: 5s/15s/30s). If still down: degrade to cached plans only, queue inference tasks. | "⚠️ Local AI temporarily unavailable. Cached tasks still work." |
| Ollama OOM / crash | Process exit or inference timeout | Restart attempt (if managed). Circuit breaker: 3 failures in 5min → stop retrying for 2min. | "⚠️ Local AI crashed. Attempting restart..." |
| Cloud API error (rate limit, 500, timeout) | HTTP status / timeout | Retry with exponential backoff. If persistent: try fallback provider (if configured). Queue Mentor tasks. | "⚠️ Cloud AI temporarily unavailable. Planning tasks queued." |
| Cloud API key invalid / quota exhausted | 401/403 response | No retry. Alert immediately. Block Mentor calls. | "❌ Cloud API key invalid or quota exhausted. Check settings." |
| SQLite locked (contention) | SQLITE_BUSY | Retry with short backoff (100ms × 3). WAL mode enabled on startup. | Transparent to user |
| SQLite corrupted | Integrity check on startup | Backup corrupt file, attempt `.recover`. If unrecoverable: fresh DB, log data loss. | "⚠️ Database issue detected. Some history may be lost." |
| Sandbox process crash | Non-zero exit + no result JSON | Treat as skill failure (step-level `on_fail` applies). Log stderr. | Normal skill failure flow |
| Sandbox timeout | Process killed after deadline | Same as crash. Log timeout duration. | "⏱️ Skill timed out after {timeout}s" |
| WebSocket disconnect mid-task | Connection close event | Task continues running. Pending approvals saved to DB. On reconnect: replay pending prompts + deliver results. | Results delivered on reconnect |
| Telegram API down | HTTP error | Retry with backoff. Buffer outgoing messages (up to 100). | Messages delivered when Telegram recovers |
| Disk full | IOException on write | Alert immediately. Stop accepting new tasks. In-memory tasks continue. | "❌ Disk full. Cannot save data." |

### 9.2 Circuit Breaker

> **Note (v0.3)**: The old per-LLM circuit breaker in LlmRouter has been removed. The cloud LLM always orchestrates; if it's unavailable, the system degrades to local-only mode. Circuit breaker logic may still be useful at the HTTP client level for retries.

For Ollama and cloud LLM connections:

```
CLOSED (normal) ──failure threshold──→ OPEN (blocking)
     ↑                                       │
     │                                  cooldown timer
     │                                       │
     └────── success ────── HALF-OPEN ──────┘
                          (try one request)
```

- **Failure threshold**: 3 failures in 5 minutes → open
- **Cooldown**: 2 minutes → half-open (try one request)
- **Success in half-open**: close circuit, resume normal
- **Failure in half-open**: reopen, wait another cooldown
- Separate circuit breakers for Ollama and each cloud provider

### 9.3 Graceful Degradation (Offline Mode)

When cloud LLM is unavailable:

| Capability | Normal | Degraded |
|-----------|--------|----------|
| Execute cached plans | ✅ | ✅ |
| Executor classify + match | ✅ | ✅ (local Ollama) |
| Mentor planning | ✅ | ❌ Queued |
| Skill generation | ✅ | ❌ Queued |
| Mentor review | ✅ | ⚠️ Skipped, marked unreviewed |
| Teaching / feedback | ✅ | ❌ Queued |
| Conversational chat | ✅ | ✅ (Executor local) |

Tasks requiring Mentor are queued with `pending_mentor` flag and processed when connectivity returns:
  "🔌 Cloud AI offline. This task requires planning — queued for when connectivity returns."

### 9.4 Partial Plan Recovery

If a multi-step plan is interrupted (system crash, restart):

1. **Task state persisted** after each step completes (written to `task_state` table in SQLite)
2. On restart, Task Orchestrator scans for interrupted tasks:
   - Completed steps → skip
   - Current step → re-execute (skills should be idempotent when possible)
   - Remaining steps → continue normally
3. User notified: "🔄 Recovered interrupted task: 'send report'. Resuming from step 3/5."
4. If task is too old (> 1 hour) or recovery fails → mark failed, notify user

### 9.5 Logging Strategy

All system-level events are logged to three destinations:

| Destination | Purpose | Audience |
|-------------|---------|----------|
| Event log (SQLite `events` table) | Structured audit trail, queryable via `/log` | User |
| Application log (SLF4J + Logback) | Ops/debugging, stack traces, system metrics | Developer/Admin |
| Chat status message | Real-time notification of critical issues | User |

Severity mapping: user-impacting failures → `ERROR`, transient retries → `WARN`, recovery actions → `INFO`.

---

## 10. Technology Stack

### 10.1 Core

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Core framework | Java 21 + Spring Boot | Mature, rich ecosystem, familiar |
| Build tool | Gradle (Kotlin DSL) | Standard for modern Java |
| HTTP/WS server | Spring WebFlux + WebSocket | Built into Spring Boot |
| Telegram integration | TelegramBots library | Most mature Java Telegram SDK |
| Database | SQLite (via sqlite-jdbc) | Embedded, zero-config |
| JSON handling | Jackson | Standard, fast |
| HTTP client (LLM APIs) | Spring WebClient or OkHttp | Async, streaming support |
| Encryption | javax.crypto (AES-256-GCM) | JDK built-in, no extra deps |
| Scheduling | Spring Scheduler | For periodic tasks, cleanup |
| Concurrency | java.util.concurrent (PriorityBlockingQueue, ExecutorService) | JDK built-in, well-tested |
| Schema migration | Liquibase | Industry standard, SQL + YAML changelogs, Spring Boot integration |

### 10.2 Skills Runtime

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Skill scripts | Python 3.11+ | Rich library ecosystem |
| Sandbox (Linux) | bubblewrap (bwrap) | Lightweight, widely available |
| Sandbox (Windows) | ProcessBuilder + restrictions | Dev-mode, weaker isolation |
| Shared base env | Single venv with common libs (requests, beautifulsoup4, etc.) | Avoid re-installing common deps |
| Per-skill env | venv per skill (when skill declares custom deps) | Dependency isolation |

### 10.3 LLM Integration

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Cloud LLM (Orchestrator) | Direct API calls (OpenAI, Anthropic) | Cloud always drives main agent loop; provider configurable via settings |
| Local LLM (Executor) | Ollama (qwen2.5:14b) | Executes delegated plans via LocalExecutor mini-loop |
| Unified interface | Internal adapter pattern (`LlmProvider`) | Java interfaces per provider (OpenAI, Anthropic, Ollama) |
| Provider routing | `LlmRouter` — always cloud, local fallback | Simplified from old confidence-based routing |
| Prompt caching | Anthropic `CACHE_BOUNDARY_MARKER` | Static prompt content cached to reduce costs |
| Ollama serialization | Semaphore (single concurrent request) | 7-14B models need exclusive GPU |

### 10.4 Frontend (WebUI)

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Framework | React or plain HTML+JS (TBD) | WebSocket chat UI |
| Communication | WebSocket | Real-time streaming response |
| Auth | JWT tokens | Standard, stateless |
| Event stream | WebSocket channel for status/progress messages | Chat-centric UX |

---

## 11. Project Structure

```
ownclaw/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── java/com/ownclaw/
│       │   ├── OwnClawApplication.java          # Spring Boot entry point
│       │   │
│       │   ├── core/
│       │   │   ├── TaskOrchestrator.java         # Main task flow coordinator
│       │   │   ├── TaskQueue.java                # Priority queue + task state machine
│       │   │   ├── TaskPlan.java                 # DAG plan data model (steps, deps, conditions)
│       │   │   ├── TaskStep.java                 # Single step in a plan (skill, params, on_fail)
│       │   │   ├── FeedbackLoopManager.java      # Manages Mentor↔Executor rounds
│       │   │   ├── TokenBudgetTracker.java       # Per-user token accounting
│       │   │   ├── PlanCache.java                # LRU cache for recurring task plans
│       │   │   ├── EventLog.java                 # Structured event log, streams to user chat
│       │   │   ├── ConditionEvaluator.java       # Evaluates plan step conditions (Java parser)
│       │   │   └── CircuitBreaker.java           # Circuit breaker for Ollama + cloud providers
│       │   │
│       │   ├── mentor/
│       │   │   ├── MentorService.java            # Plans, reviews, teaches
│       │   │   ├── MentorPromptBuilder.java      # Builds compressed prompts + system prompt
│       │   │   ├── SkillGenerator.java           # Creates new skills via Mentor
│       │   │   └── TeachingPipeline.java         # Handles teaching entries, distillation
│       │   │
│       │   ├── executor/
│       │   │   ├── ExecutorService.java           # Local LLM interaction (classify, compress)
│       │   │   ├── SkillMatcher.java              # LLM-based skill matching + cache lookup
│       │   │   ├── TaskPreprocessor.java          # Compression, context preparation
│       │   │   ├── ResultCompressor.java          # Compresses outputs for Mentor
│       │   │   ├── ConversationCompressor.java    # Rolling history summarization
│       │   │   └── PreferencesManager.java        # Manages per-user preferences (local LLM)
│       │   │
│       │   ├── skillrunner/
│       │   │   ├── SkillRunnerService.java        # Executes skills in sandbox
│       │   │   ├── SkillInteractionHandler.java   # Handles need_input/progress from skills
│       │   │   ├── SkillRollbackHandler.java      # Handles versioned rollback on failure
│       │   │   └── SkillOutputParser.java         # Parses JSON-lines from skill stdout
│       │   │
│       │   ├── llm/
│       │   │   ├── LlmProvider.java               # Interface
│       │   │   ├── OllamaProvider.java            # Local (Executor + SkillRunner)
│       │   │   ├── OllamaSemaphore.java           # Serializes Ollama requests
│       │   │   ├── AnthropicProvider.java          # Cloud (Mentor)
│       │   │   ├── OpenAiProvider.java             # Cloud (Mentor)
│       │   │   ├── DeepSeekProvider.java           # Cloud (Mentor)
│       │   │   ├── MistralProvider.java            # Cloud (Mentor)
│       │   │   ├── LlmMessage.java                # Unified message model
│       │   │   └── LlmResponse.java               # Unified response model
│       │   │
│       │   ├── skills/
│       │   │   ├── SkillManifest.java             # Loads/queries manifest.json
│       │   │   ├── SkillLoader.java               # Resolves skill directory → script
│       │   │   ├── SkillValidator.java            # Validates generated skills
│       │   │   ├── SkillVersionManager.java       # Manages numbered versions + symlinks
│       │   │   ├── SkillDependencyResolver.java   # Manages shared/per-skill Python envs
│       │   │   └── SkillModel.java                # SKILL.yaml data model
│       │   │
│       │   ├── sandbox/
│       │   │   ├── SandboxManager.java            # Interface
│       │   │   ├── BubblewrapSandbox.java         # Linux production sandbox
│       │   │   ├── ProcessSandbox.java            # Windows/dev sandbox
│       │   │   └── SandboxResult.java             # Execution result model
│       │   │
│       │   ├── users/
│       │   │   ├── UserProfile.java               # User data model
│       │   │   ├── UserRepository.java            # SQLite persistence
│       │   │   ├── CredentialVault.java           # Encrypted credential store
│       │   │   ├── CredentialGrantService.java    # Manages permanent/one-time/declined grants
│       │   │   └── TokenBudget.java               # Budget tracking model
│       │   │
│       │   ├── conversation/
│       │   │   ├── ConversationService.java       # Manages chat sessions
│       │   │   ├── ConversationRepository.java    # SQLite persistence
│       │   │   ├── ContextWindowManager.java      # Active context management
│       │   │   └── SessionSummary.java            # Rolling summary model
│       │   │
│       │   ├── observability/
│       │   │   ├── EventLogService.java           # Writes + queries structured events
│       │   │   ├── ChatStatusEmitter.java         # Streams status messages to user chat
│       │   │   └── EventRepository.java           # SQLite persistence for events
│       │   │
│       │   ├── interfaces/
│       │   │   ├── telegram/
│       │   │   │   ├── TelegramBotService.java    # Bot lifecycle
│       │   │   │   ├── TelegramMessageHandler.java# Message routing
│       │   │   │   └── TelegramUserResolver.java  # Telegram ID → OwnClaw user
│       │   │   │
│       │   │   └── web/
│       │   │       ├── WebController.java         # HTTP endpoints
│       │   │       ├── WebSocketHandler.java      # Chat WebSocket (messages + status)
│       │   │       └── AuthController.java        # Login/JWT
│       │   │
│       │   └── config/
│       │       ├── OwnClawConfig.java             # Main config model
│       │       ├── SecurityConfig.java            # Spring Security
│       │       └── WebSocketConfig.java           # WS configuration
│       │
│       └── resources/
│           ├── application.yaml                   # Spring Boot config
│           ├── db/
│           │   └── changelog/
│           │       ├── db.changelog-master.yaml   # Liquibase master changelog
│           │       ├── 001-initial-schema.sql     # Users, conversations, events, task_state
│           │       └── ...                        # Incremental migrations
│           └── static/                            # WebUI files
│
├── skills/
│   ├── manifest.json
│   └── core/
│       ├── send_email/
│       │   ├── v1/
│       │   │   ├── SKILL.yaml
│       │   │   ├── skill.py
│       │   │   └── requirements.txt
│       │   └── current -> v1                      # Symlink to active version
│       ├── browse_web/
│       ├── file_operations/
│       ├── shell_command/
│       ├── text_summarize/
│       ├── http_request/
│       └── schedule_task/
│
├── config/
│   └── ownclaw.yaml                              # Main configuration file
│
├── data/                                          # Runtime data (gitignored)
│   ├── ownclaw.db                                 # Main SQLite database
│   ├── plan_cache/                                # Cached plans (JSON files)
│   ├── event_log/                                 # Event log overflow (if SQLite too large)
│   └── users/                                     # Per-user data directories
│       └── {user_id}/
│           ├── profile.json
│           ├── preferences.json
│           ├── credentials.enc
│           ├── credential_grants.json
│           └── skills/
│
├── ARCHITECTURE.md                                # This file
└── README.md
```

---

## 12. Configuration

### 12.1 ownclaw.yaml

```yaml
ownclaw:
  # Server
  server:
    port: 8080
    host: 0.0.0.0

  # Local LLM (Executor — runs delegated plans via LocalExecutor)
  executor:
    provider: ollama
    url: http://localhost:11434
    model: qwen2.5:14b
    temperature: 0.3
    context_window: 16384       # tokens

  # Cloud LLM (Orchestrator — always drives main agent loop)
  mentor:
    provider: openai              # openai | anthropic
    model: gpt-4.1
    api_key: ${OPENAI_API_KEY}
    max_tokens_per_task: 10000
    temperature: 0.4

  # Token budgets (defaults, users can have custom)
  budgets:
    daily_cloud_tokens: 500000
    per_task_cloud_tokens: 10000
    warning_threshold: 0.8       # Warn at 80% usage

  # Task queue
  queue:
    max_concurrent_tasks: 5          # Total tasks running simultaneously
    ollama_concurrency: 1            # Serialize Ollama requests (GPU bottleneck)
    cloud_concurrency: 3             # Parallel cloud LLM requests
    sandbox_concurrency: 3           # Parallel sandbox executions
    max_queued_tasks: 50             # Reject if queue exceeds this
    priority_preemption: false       # P0 does not preempt running P1+ tasks (v1)

  # Task execution
  tasks:
    default_timeout: 300             # seconds, for whole task (not per step)
    step_timeout: 60                 # seconds, per plan step
    long_running_threshold: 120      # seconds; tasks exceeding this become "long-running"
    max_plan_steps: 20               # Maximum steps in a DAG plan

  # Telegram
  telegram:
    enabled: true
    bot_token: ${TELEGRAM_BOT_TOKEN}
    registration: open           # open | invite-only | closed

  # WebUI
  webui:
    enabled: true

  # Sandbox
  sandbox:
    type: auto                   # auto | bubblewrap | process
    default_timeout: 60          # seconds
    default_network: deny        # allow | deny
    python_path: /usr/bin/python3

  # Skills
  skills:
    core_path: ./skills/core
    generated_path: ./skills/generated
    user_path: ./data/users/{user_id}/skills
    manifest_path: ./skills/manifest.json
    max_versions_kept: 5         # Old versions to retain per skill
    shared_venv_path: ./data/shared_venv
    per_skill_venv_path: ./data/skill_venvs

  # Plan cache
  plan_cache:
    enabled: true
    max_entries: 500             # LRU eviction
    ttl_hours: 168               # 7 days
    invalidate_on_skill_change: true

  # Database
  database:
    path: ./data/ownclaw.db
    # Schema migrations managed by Liquibase (changelogs in resources/db/changelog/)

  # Feedback loop
  feedback:
    max_rounds: 3
    auto_review: true
    auto_review_threshold: medium
    teaching_log_max_entries: 30      # Per-user limit before distillation
    distillation_interval_days: 7     # Periodic teaching log cleanup

  # Observability
  observability:
    event_log_retention_days: 30     # How long to keep events
    chat_status_messages: true       # Send status messages to user chat
    status_verbosity: concise        # concise | verbose
    log_level: INFO                  # DEBUG | INFO | WARN | ERROR

  # Confidence threshold (no longer used — cloud always orchestrates)
  # Retained for reference; was used in v0.2 Executor-routes-to-Mentor model
  # confidence:
  #   mentor_threshold: 0.3
  #   cache_skip_threshold: 0.7
```

---

## 13. Observability & UX Model

### 13.1 Design Principle: Chat-Centric

All system interaction happens through the chat interface. There is no separate admin panel, no log files to tail, no dashboards to check. The user sees everything they need in the same conversation where they issued the task.

### 13.2 Chat Status Messages

During task execution, the system sends concise status messages to the user's chat. These are **not** LLM-generated prose — they are structured template messages.

**Status message types**:

| Type | Example | When |
|------|---------|------|
| `queued` | "📋 Task queued (position 3, priority P1)" | Task enters queue |
| `started` | "⚙️ Starting: summarize emails" | Task begins execution |
| `step` | "→ Step 2/4: filtering unread emails" | Each plan step begins |
| `progress` | "⏳ Step 2/4: found 23 unread emails" | Skill sends progress update |
| `need_input` | "❓ Skill needs input: Which folder? [Inbox/All]" | Skill requests user input |
| `credential` | "🔑 Skill requests access to SMTP credentials. [Always/Once/Deny]" | Credential approval needed |
| `mentor` | "🧠 Escalating to Mentor for planning..." | Cloud LLM invoked |
| `completed` | "✅ Done: sent summary to john@example.com" | Task finished successfully |
| `failed` | "❌ Failed: SMTP connection refused. Shall I retry?" | Task failed |
| `rollback` | "⏪ Rolling back: deleted draft email" | Reversible step undone |

**Rules**:
- Status messages are excluded from LLM context (they don't consume tokens)
- Status messages use the `status` role in conversation storage
- In verbose mode (`status_verbosity: verbose`), additional detail is shown
- In concise mode (default), only key transitions are shown

### 13.3 Event Log

Every significant system action is recorded in a structured event log (SQLite):

```sql
CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    user_id TEXT NOT NULL,
    task_id TEXT,
    event_type TEXT NOT NULL,      -- task.queued, task.started, step.started, 
                                   -- skill.executed, mentor.called, credential.granted,
                                   -- error.occurred, plan.cached, skill.version_changed
    severity TEXT NOT NULL,        -- info | warn | error
    summary TEXT NOT NULL,         -- Human-readable one-liner
    details JSON,                  -- Full structured data
    tokens_used INTEGER DEFAULT 0  -- Cloud tokens consumed by this event
);

CREATE INDEX idx_events_user_task ON events(user_id, task_id);
CREATE INDEX idx_events_type ON events(event_type);
```

### 13.4 User-Accessible Log Viewer

Users can query their own event log through chat commands:

- `/log` — Last 10 events
- `/log task <id>` — All events for a specific task
- `/log errors` — Recent errors
- `/log tokens` — Token usage summary (today/week/month)
- `/log skills` — Recent skill executions

The WebUI may also render a simple event timeline alongside the chat, but the chat interface is the primary access method.

### 13.5 UX Flow Examples

**Simple task (cache hit, no Mentor)**:
```
User:  "Summarize my unread emails"
Bot:   ⚙️ Starting: summarize emails
Bot:   → Running skill: fetch_emails
Bot:   → Running skill: text_summarize  
Bot:   ✅ Done. You have 5 unread emails:
       1. Meeting tomorrow (from: boss@...)
       2. Invoice #4521 (from: vendor@...)
       ...
```

**Complex task (Mentor involved)**:
```
User:  "Set up a weekly report that emails me every Monday"
Bot:   📋 Task queued (P1)
Bot:   🧠 Escalating to Mentor for planning...
Bot:   ⚙️ Mentor created a 3-step plan:
       1. Create report_generator skill
       2. Schedule weekly trigger (Mon 9am)
       3. Test with a dry run
Bot:   → Step 1/3: generating skill...
Bot:   → Step 2/3: scheduling...
Bot:   → Step 3/3: dry run...
Bot:   🔑 Skill 'report_generator' requests SMTP credentials. [Always/Once/Deny]
User:  "Always"
Bot:   ✅ Done. Weekly email report scheduled for Monday 9:00 AM.
```

**Failed task with rollback**:
```
User:  "Send the quarterly report to the team"
Bot:   ⚙️ Starting: send quarterly report
Bot:   → Step 1/2: generating report...
Bot:   → Step 2/2: sending email...
Bot:   ❌ SMTP connection refused (step 2/2 failed)
Bot:   ⏪ Step 1 was reversible — cleaned up temp files
Bot:   Shall I retry, or would you like to check your SMTP settings?
```

---

## 14. MVP Scope

### Phase 1: Foundation (MVP)

- [x] Java/Spring Boot project scaffold
- [x] Ollama integration (Executor)
- [x] Single cloud provider (OpenAI) for Mentor
- [x] Task orchestrator: Executor classifies → Mentor plans → SkillRunner executes
- [x] Task queue (priority-based, with Ollama serialization)
- [x] DAG plan format (steps with depends_on, on_fail)
- [x] Skill manifest + 3 core skills (shell_command, file_operations, http_request)
- [x] ProcessBuilder sandbox (Windows dev-friendly)
- [x] SQLite persistence (users, conversations, events)
- [x] Telegram bot (single user)
- [x] Basic conversation management (last N messages)
- [x] Simple WebUI (chat only, WebSocket)
- [x] **Event log + chat status messages (observability from day one)**
- [x] **Credential grant approval flow (permanent/one-time/declined)**
- [x] `/log` chat commands for event inspection
- [x] Confidence threshold bypass (skip Mentor for cached plans)

### Phase 2: Multi-User & Skills

- [x] Multi-user support with profiles + isolation
- [x] Credential vault (AES-256-GCM encrypted, per-user keys)
- [x] Token budget tracking + alerts
- [x] Skill generation (Mentor creates new skills)
- [x] Skill validation (static analysis + sandbox test)
- [x] Skill versioning (numbered dirs, regeneration/repair on failure; no automatic quarantine)
- [x] More core skills (send_email, browse_web, text_summarize)
- [x] Conversation compression (Executor summarizes history)
- [x] Feedback loop (Mentor reviews, max N rounds)
- [x] Plan cache (LRU, invalidation on skill changes)
- [x] User preferences (maintained by Executor, local LLM)
- [x] Skill interaction (need_input protocol)

### Phase 3: Polish & Hardening

- [x] MCP Bridge implementation (should work with our skills system alongside, but give us access to existing tools)
- [ ] Bubblewrap sandbox (Linux production)
- [ ] Multi-provider cloud LLM support (OpenAI, DeepSeek, Mistral)
- [ ] Skill generalization (merge similar skills)
- [ ] Teaching pipeline (Mentor → Executor knowledge transfer, distillation)
- [x] Long-running / deferred / scheduled tasks
- [ ] WebUI: event timeline, skill management, token usage dashboard
- [ ] Registration flow (invite codes, admin approval)
- [x] Prompt anti-bloat (bounded teaching log, periodic distillation)

### Phase 4: Advanced

- [ ] Per-user LoRA personality fine-tuning (Ollama supports custom models)
- [ ] Proactive suggestions (Executor notices patterns)
- [ ] Skill marketplace (share skills between OwnClaw instances)
- [ ] MCP (Model Context Protocol) integration for external tools
- [ ] Continuous monitoring skills (watch + alert patterns)

### Phase 5: Future Extensions

- [ ] Skill dependency graph (skills declare pairings, Executor uses for plan suggestions without Mentor)
- [ ] Conversation bookmarks (user marks key context points, survive compression as high-priority items)
- [ ] Dry-run mode ("plan this but don't execute" — user reviews/modifies plan before execution)
- [ ] Skill templates (Python code templates for common patterns: API caller, file processor, message sender — Mentor fills in, reduces generation failures)
- [ ] Multi-agent plans (two Executor personas for research tasks: one finds sources, one critiques)
- [ ] Voice interface (Telegram voice messages → Whisper transcription → same pipeline)
- [ ] Federated skill sharing (pull skills from a shared registry across OwnClaw instances)
- [ ] Rollback chains (automatic reverse-execution of completed reversible steps when later step fails)
- [ ] Cost analytics (`/cost` command — daily/weekly/monthly cloud LLM costs by task category)
- [ ] Confidence self-calibration (automatic threshold tuning based on accumulated success data, see §4.9)

### Testing Strategy

Testing will be **manual + observability-driven** (no automated test suite initially):

- Every action is logged in the event log — failures are visible immediately
- Chat status messages give the user real-time feedback on what's happening
- `/log errors` surfaces issues quickly
- The structured event log serves as a post-mortem audit trail
- When the system matures, automated tests may be added for regression, but the priority is a tight manual feedback loop with good observability

---

## 15. Open Questions

1. **Executor model selection**: Qwen 2.5 14B vs Llama 3.x vs Mistral — need benchmarking for structured output reliability at this size. The model must reliably output JSON plans and skill matching decisions.

2. **Streaming**: Should task execution stream partial results to the user, or only deliver final results? Current design: status messages stream progress, but final result is delivered as a single message.

3. **WebUI framework**: React (richer UX) vs HTMX/Alpine (simpler, less build tooling) vs plain JS? The chat-centric design minimizes WebUI complexity — even a basic WebSocket chat could suffice for MVP.

4. **Plan cache sharing**: Should plan cache be per-user or global? Similar tasks from different users could share plans, but user-specific preferences might make plans non-transferable.

5. **Skill trust levels**: Should user-created skills have different trust levels than Mentor-generated ones? Currently both go through the same credential approval flow, but Mentor-generated skills get an extra warning on first credential request.

---

## 16. Key Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| 7-14B model unreliable at structured output | Plans fail, skills mismatch | Constrained output (JSON mode), retry with examples, fallback to Mentor |
| Skill generation produces buggy code | Failed tasks, security issues | Mandatory sandbox test, static analysis, Mentor review, auto-rollback |
| Token budget exceeded unexpectedly | Unexpected costs | Hard limits per-task and per-day, automatic cutoff, alerts via chat |
| User data leakage between profiles | Privacy violation | Per-user encryption, isolated SQLite queries, audit logs |
| Ollama not available / model too large | System non-functional | Health check on startup, graceful degradation, model recommendations |
| Ollama serialization bottleneck | Slow task throughput | Queue priority ensures important tasks go first; cloud-only tasks bypass Ollama |
| Skill regression after update | Tasks break silently | Versioned rollback, old versions retained, event log tracks version changes |
| Credential escalation via Mentor-generated skill | Unauthorized access | Explicit user approval per credential per skill, extra warning for new skills |
| Teaching log / prompt bloat over time | Executor performance degrades | Bounded teaching log (30 entries), periodic distillation, skills absorb knowledge into code |
| Plan cache stale after skill update | Wrong plan executed | Cache invalidation on skill manifest change, TTL expiry |
| Misclassification (Executor routes wrong) | Task fails or is suboptimal | Feedback loop — Mentor reviews failures, teaches Executor, improves confidence over time |
| System crash mid-task | Partial execution, data loss | Task state persisted per step, automatic recovery on restart (§9.4) |
| Ollama/cloud intermittent failures | Degraded availability | Circuit breaker pattern, graceful degradation, offline mode (§9) |
