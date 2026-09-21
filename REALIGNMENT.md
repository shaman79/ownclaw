# OwnClaw — gap analysis and realignment proposal

**Date:** 2026-09-17 · **Status:** proposal, not yet started · **Supersedes:** `REDESIGN-PROPOSAL.md` (whose
diagnosis of generation 1 was accurate, and whose unbuilt half is still the right target)

Security items are deliberately not in this document. They are in `SECURITY-TODO.local.md`, which is gitignored
because this repository is public.

---

## How this was produced

Thirteen parallel read-only reviews of the codebase, one per subsystem, producing 157 findings with `file:line`
evidence. An adversarial pass then re-read the code for the 37 claims this proposal leans on and tried to refute
each one: **25 confirmed, 12 narrowed, none refuted.** Six architects then wrote competing designs from
deliberately different convictions, and a completeness critic resolved nine places where reviewers contradicted
each other.

Nothing here was measured against the running system. Everything is read from the code. Where a number is an
estimate, it says so.

---

## 1. The short version

Your three complaints are accurate, and each has a mechanical cause:

| What you see | What it actually is |
|---|---|
| "clumsy, often fails to deliver" | The action protocol is JSON typed into a text box, not native tool calling; on your provider the tool parameter schemas vanish after step 0; everything older than two steps is cut to 300 characters |
| "limited insight" | One database row is persisted per task. The live think/act/observe stream is never stored, so nothing survives the browser tab |
| "doesn't achieve the token or privacy goals" | Correct, by construction. ~92–97% of tokens and **100% of decisions** are cloud. There is no privacy mechanism of any kind — not a flag, not a column, not a line of code |

**The two-tier idea is sound. Its current implementation is not two-tier.** The single delegation mechanism
hands the local model a list of tool calls whose parameters the cloud model already wrote, and asks it to type
them out again. A `for` loop would do the same job without a 14B model in the path.

The encouraging part: the seams are in the right places. One `LlmProvider` interface, only four cloud call
sites, immutable context records, a live event stream, skills as inspectable files, FTS5 already in the schema.
Most of this work is harness engineering inside a structure that can take it — not a rewrite.

---

## 2. What is actually broken

Seven root causes account for nearly all 157 findings.

### 2.1 The action protocol is a prompt convention

The model is asked to reply with `{"reasoning", "tool", "params"}` as plain text. No native tool use on any of
the three providers, no strict schemas, no parallel tool calls, and `stop_reason` is never read anywhere in the
codebase.

Consequences, all verified: an answer truncated at the 8192-token cap is indistinguishable from malformed JSON;
both are retried with a byte-identical prompt up to three times, then the task aborts with "3 consecutive
reasoning failures" and the model's actual prose answer is thrown away. Only HTTP 429 is retried, so a 500, an
expired key or an over-long prompt all surface as the same generic message. Every tool call costs a full cloud
round trip against a 20-step budget.

### 2.2 The prompt starves the model it is paying for

**You run Anthropic** — your original error was `[anthropic] HTTP 400` — and the Anthropic path has defects the
OpenAI path does not:

- The tool manifest with parameter schemas is sent **only while the trajectory is empty**. From step 1 the model
  gets a comma-separated list of tool *names*. It must guess the parameters of any skill it has not already
  called this task. The code comment claiming the schemas are "cached in prior turns" is false: the request does
  not contain them, so no cache can supply them.
- If the deterministic `skill_create` shortcut fires at step 0, the schemas are never sent at all.
- Parse-error feedback is filtered out of the Anthropic prompt before rendering, so the retry carries no
  correction.

Independently of provider: only the last two observations are kept in full and uncapped; everything older is
rewritten to 150 characters of head plus 150 of tail. A task like "read three pages and compare them" cannot
work — by the time the third arrives the first is a stub. Meanwhile a single 200 KB page is sent to the cloud
in full, twice.

Because older turns are **rewritten** each step, the message prefix changes, so prompt caching mostly writes
entries at a 1.25× premium that are never read. The expensive content (fresh tool output) is exactly what never
hits cache.

### 2.3 Failure is indistinguishable from success

Every ending except cancel and an uncaught exception returns `success = true`: the step cap, the reasoning-
failure abort, `ask_user`, and an empty answer. The unused `AgentResult.maxSteps/timeout/failureLimit`
factories already describe the right taxonomy; nothing calls them.

This poisons everything downstream. Failed runs are written to memory as `[SUCCESS]` and recalled later as
positive examples. Scheduled runs are recorded as `completed` even when the agent produced nothing — the
success signal is destroyed in `AgentLoop.execute()`, which returns a bare `String`. Tool success is
self-reported by LLM-written Python, so a tool can return an error string with `success = true` and every
downstream guard stays silent.

### 2.4 There are no built-in tools

`agent/tools/impl` is empty. `DynamicSkill` is the only `Tool` implementation. On a fresh install the agent
cannot fetch a URL, read a PDF, search, or run a shell command until the cloud model has written and debugged
Python to do it. Generated skills are validated by `py_compile` and a `def run` check only — no smoke test, no
versioning, no rollback, and `skill_create` with an existing name overwrites the file in place. A skill that
worked last week can be silently broken by an unrelated task.

### 2.5 Nothing is durable

Tasks live in RAM on one worker thread. A restart drops running and queued work with no record and no
notification. `ask_user` ends the task, so answering a clarifying question starts over from an empty
trajectory — every tool result already fetched is gone. So does "continue" after the step cap. The answer is
sent to the WebSocket session captured at submit time, so a reconnect loses it. Scheduled results are never
written to the chat at all.

One mitigation exists: `deploy.sh` waits for `/api/health/busy` before restarting, so planned deploys rarely
kill a task. Crashes and `/api/debug/restart` are unguarded.

### 2.6 There is no privacy mechanism

Not partially implemented — absent. A search for sensitivity, privacy, redaction or local-only across the Java,
the config, all 14 migrations and the UI returns nothing relevant.

Every cloud think call, **on every step**, carries: your message, the recent session history in full with text
attachments inlined up to 100 KB each, the rolling summary, up to 3 recalled episodes, **all** stored facts with
no cap, vault key names, and raw tool output. Ask it to summarise a bank statement and the whole statement goes
to the provider at least twice. `delegate` does not help: the consolidated result is fed straight back to the
cloud.

There is one bright spot: the secret-entry flow the agent steers you into (`ask_user` → `credential_manage`)
leaks passwords through the cloud model and into plaintext history — but `/cred set` already exists, writes
straight to the vault, and never touches an LLM. The agent is simply never told about it.

### 2.7 Nothing is measured

Routing strategy changed six times in five weeks. The only production number on record is a commit message:
**771K cloud tokens, 0 local tokens, 80 steps.** Cost is always recorded as `0.0`. Anthropic cache tokens are
parsed and then dropped, so the displayed token count cannot be reconciled with the bill. Budgets exist in
config; `hasBudget()` and `canAfford()` have no callers. There are zero tests, no CI, and `main` auto-deploys to
production every 15 minutes.

---

## 2.8 The local tier is misconfigured and cannot currently work at all

This was found by querying the Ollama host in your workspace database, `http://192.168.1.100:11434`, and it is
the most immediately actionable thing in this document.

That host has exactly two models installed:

| Model | Size | Params |
|---|---|---|
| `hf.co/wangzhang/Qwen3.6-35B-A3B-abliterated-GGUF:Q4_K_M` (loaded, 23.7 GB resident) | 21.2 GB | 34.7B |
| `nemotron-cascade-2:latest` | 24.3 GB | 31.6B |

**Neither `qwen2.5:14b` (the `application.yaml` default) nor `qwen3:1.7b` (the value in the database) is
installed.** Asking for the default returns, verbatim:

```
{"error":"model 'qwen2.5:14b' not found"}
```

And the model that *is* loaded cannot be used through the chat API the way OwnClaw calls it. Its Ollama
metadata says `capabilities: ["completion"]` — not `chat`, not `tools` — and its template is the literal
13-character string `{{ .Prompt }}`, with no roles and no message loop. Calling `/api/chat` exactly as
`OllamaProvider` does, with a system message, a user message and `format: "json"`:

```
prompt_eval_count: 10          ← the whole system prompt + task was ~50+ tokens
content: {"prompt": "Fetch https://example.com and return the title."}
```

Ten tokens of prompt were evaluated. The system instruction never reached the model; it echoed the user text
back inside a JSON wrapper. **Every local job — delegation, conversation compression, tool pre-selection,
scheduled summarisation — would return this kind of nonsense**, and a blank compression summary still deletes
your chat rows (§5.6).

So "the two tiers don't cooperate" has a more basic cause than the architecture: on this host the second tier
is either a hard error or a model that cannot see its own prompt. It also explains the one production number on
record — 771K cloud tokens, **0 local**.

Two caveats, stated honestly:

- This is the Ollama host configured in the **workspace** database and reachable on your LAN. Production is on
  a public host and cannot reach a private address, so it must have its own Ollama. **That one has not been
  checked** — it is the first item in Phase 0.
- No safety comment is intended about running an abliterated model; the point is purely mechanical — it ships
  without a chat template, so the chat endpoint cannot work with it.

**The fix is minutes, not weeks:** either `ollama pull` a chat-capable model and point the config at it, or
wrap the existing one in a `Modelfile` that supplies the proper Qwen3 chat template, then verify that
`prompt_eval_count` reflects the real prompt size. Add a startup check that the configured model exists and
advertises the `chat` capability, and fail loudly instead of silently.

**And it is good news for the redesign.** A 35B-A3B mixture-of-experts activates ~3B parameters per token: the
test above answered in 3.5 seconds. That is materially more capable *and* faster than the `qwen2.5:14b` this
plan was sized around. If that is the hardware, the local tier can be given more than the design assumed — once
it is wired up correctly.

## 3. Why the two tiers don't cooperate

`delegate` is the flagship two-tier mechanism, and it is a transcription layer:

- The cloud writes the plan **including each step's tool and parameters**.
- `LocalExecutor` never executes those steps. It renders the plan into a prompt and asks the 14B model to
  re-emit each call as JSON, one inference per call, with the manifest of *all* tools loaded into a 16K context.
- Nothing checks the emitted call against the plan. The local model may call any registered tool, in any order.
- On success only its **free-text summary** is returned; the per-step results, which already exist as structured
  data, are discarded. Success is `!result.startsWith("ERROR")`.
- The summary is capped at 2048 output tokens while being told to preserve "ALL collected data", so on
  data-heavy work it truncates, fails to parse, and ends as "Delegation incomplete" — after which the cloud
  redoes the work, paying twice.

Generation 1 failed the other way: the small model planned, routed and judged, and the measured results were
bad (50% skill failure rate, 0 of 6 persisted answers verifiably correct, 25–107 s just to classify). The
lesson from both generations is the same: **the local model must not plan, route, judge completeness, or write
free-form prose over raw content — but it is fine at narrow jobs whose output can be checked mechanically.**

---

## 4. The realignment

All six architects converged on one structural idea, which is the important result of this exercise:

> **One mechanism serves both the token goal and the privacy goal: tool output never enters a model context raw.**

### 4.1 Artifacts, labels, handles

Every tool result is stored locally as an **artifact** with an id, a size, a type and a **label**
(`PUBLIC` / `PRIVATE`), and is shaped **once, at record time, and never rewritten**. That single invariant fixes
three findings at once: the history stops being re-rendered (so caching works), observations stop collapsing to
300 characters, and the cloud stops receiving raw content.

What the cloud planner sees instead is a **handle plus a bounded descriptor**: type, size, row count, field
names, validator results, and — for `PUBLIC` content under a size threshold — the content itself. It can ask for
a specific slice by handle.

### 4.2 What each tier does

**Cloud (planner):** everything requiring judgement — planning, recovery, writing extraction schemas, writing
skill code, composing the final answer from verified facts. Via **native tool calling with strict schemas**, on
an **append-only** transcript.

**Local (reader/extractor):** narrow jobs, each with a mechanical check the harness runs:

| Job | Mechanical check |
|---|---|
| Extract to a cloud-written JSON schema | Schema validity, plus every emitted value must appear **verbatim** in the source chunk after normalisation, plus domain invariants (statement rows sum to closing − opening; IBAN mod-97; due ≥ issue date) |
| Extractive reduction of an oversized page | Output lines must be a verbatim subset of the source |
| Closed-label classification | Label from a fixed enum, plus an evidence quote that must occur in the source |
| Embeddings for retrieval (Czech + English) | precision@3 on a labelled query set |
| Idle-time consolidation | Nothing is promoted without passing the same checks |

Never: planning, routing, completeness judgement, free-form synthesis over raw pages.

Ollama's `format` accepts a **JSON Schema**, which is the mechanism that makes this reliable — it is not used
anywhere today.

**Deterministic harness:** everything that does not need a model. Parallel tool execution (replacing the
delegate hop), bounding output, validators, the recipe replay engine, the egress gateway.

### 4.3 One egress gateway

A single class becomes the only code that can reach a cloud provider. It applies the privacy policy, builds the
payload from an allow-list of typed parts, scrubs known vault values, prices the call, checks the budget, and
writes a ledger row. Privacy then becomes a property of the code path rather than of a model's carefulness — and
you can *verify* it, because there is a log of everything that left the machine.

Today this is cheap to insert: there are only four cloud call sites.

### 4.4 Recipes

A task that succeeded and was confirmed becomes a parameterised recipe: the tool sequence with parameter
templates and postconditions. On recurrence the harness replays it deterministically. A recurring scheduled job
then costs **zero cloud tokens and sends zero bytes** — and gets more reliable rather than re-rolling the dice
every morning.

---

## 5. Decisions already resolved

The completeness critic found nine places where reviewers contradicted each other and resolved each against the
code. The consequential ones:

1. **Do not put a local router in front of every message.** That is generation 1, and it failed. Use
   deterministic rules for trivial turns (slash commands, replies to a pending question) and let the cloud
   answer in one call.
2. **Do not keep `LocalExecutor` as it is.** Replace judgement-free delegation with native parallel tool calls
   executed by the harness in a single cloud round trip. Keep a local model only for slot-filling where a
   parameter depends on an earlier result.
3. **Neither "send full raw history" nor "digest everything".** Shape each observation once at record time;
   reduce deterministically first (readability, caps); send public content under the threshold as-is; only then
   involve the local model. Never put a slow 14B model on the hot path of every step.
4. **Serialise the GPU, not whole tasks.** Today one worker thread runs an entire task including cloud waits and
   429 back-offs of up to 7.5 minutes, so a background job blocks you.
5. **Delete `CapabilityResolver`'s shortcut, keep its content.** Its 11 hard-coded capability specs are a list
   of what you actually need — they become the built-in tool library. Once those tools exist the resolver is
   dead code.

   Those 11 are `network_scanner`, `traceroute_tool`, `dns_lookup`, `packet_capture`, `media_processor`,
   `image_processor`, `document_converter`, `system_info`, `ssl_checker`, `shell_exec`, `check_email` — which
   says something about what you use OwnClaw for. Note what is *missing* from that list and from the codebase
   entirely: **fetch-a-URL, web search and PDF extraction**, the three things almost every task needs. They are
   generated from scratch, per install, by the cloud model. That is the single highest-value item in Phase 2.
6. **Stop deleting chat messages.** The compressor summarises with the local model and then `DELETE`s the
   originals, with no check that the summary is non-empty. Reports the agent produced vanish from your chat and
   from search. Mark rows compressed instead. This is a few hours' work and pure data-loss prevention.
7. **The ~9,200-token system prompt is a fossil.** It is ~1,000 tokens today; that comment predates the
   "Remove prompt ballast" commit. Any cost model built on 9,200 is wrong.

---

## 6. The judged outcome

Six architects wrote competing designs. Three judges — a skeptical staff engineer, a privacy and security
reviewer, and an advocate for you as the owner — scored all six on vision alignment, reliability, privacy
soundness, token realism, feasibility for one developer, and measurability.

| Proposal | Avg total /60 | Feasibility | Notes |
|---|---|---|---|
| **pragmatist** | **49.0** | **9.0** | Ranked **first by all three judges** |
| reliability | 48.0 | 5.7 | Best architecture in the set; least believable schedule |
| token-economy | 47.0 | 4.7 | Perfect on measurability (10/10), too large |
| trust-boundary | 45.0 | 3.7 | Strongest privacy (9.7); a research project |
| learning | 43.0 | 2.7 | Best vision alignment (9.0); 18–21 weeks of machinery |
| owner-experience | 42.7 | 5.7 | Right about the UI, weaker elsewhere |

The verdict was not that pragmatist has the best architecture — the judges agree reliability does. It won
because **it is the only one whose shape survives one person working alongside AI assistants**, and because it
orders work by certainty rather than by ambition.

**The plan therefore is: the pragmatist roadmap, with reliability's architecture as the destination and
owner-experience's client design for the UI** (because `index.html` is a 4.8k-line file with one 2.3k-line
closure, ~25 shared globals and 63 `innerHTML` writes — adding ten new panels to it is how the insight goal
quietly fails).

### Where the judges overruled the backbone

1. **Sandboxing moves earlier, into Phase 2.** Rootless Podman with `--network=none` becomes mandatory for
   every generated skill, and it **fails closed** — if no rootless runtime works, generated code does not run
   and the agent says why, while the built-in Java tools keep it useful. This is the difference between "any
   generated skill can read `data/ownclaw.db` and POST it anywhere" and not.
2. **The single worker thread gets split**, in stages: task ids on every event and the Ollama semaphore moved
   inside `OllamaProvider` in Phase 1 (cheap, and everything downstream needs the task id); separate
   interactive and background executors later. Only the GPU is scarce — today one worker serialises whole
   tasks, including 429 back-offs of up to 7.5 minutes.
3. **Privacy stops being one thing that arrives in Phase 3.** Four fifths of the real protection ships in
   weeks 1–7: the credential dump and deploy/restart/skill-write endpoints deleted on day 2, the VPN on day 2,
   the egress "tourniquet" on day 3, every skill sandboxed in Phase 2, and the egress log running in
   **observe-and-record** mode from Phase 2 (because on an append-only transcript each call's new content *is*
   the diff, so logging it costs almost nothing). Only enforcement waits for labels.
4. **Analysis of private data uses read-only SQLite, not generated code.** `table_query` over locally
   extracted rows ships first and is the default; cloud-written Python (`local_compute`) is reserved for
   *parsing* a layout, which genuinely needs a program. No code execution, bounded failure, and it works on a
   host where Podman does not.

### What was deliberately dropped

- **Any local router in front of every message.** 6–10 days for ~5–8% of tokens, using the exact mechanism that
  killed generation 1 (8 of 9 conversational tasks misrouted). With a cached prefix, "thanks" costs a fraction
  of a cent.
- **Any local-share target** such as "50% of tokens processed locally". It is a utilisation goal, and the
  arithmetic kills it: digesting a 6K-token public page saves about two cents and can cost 10–60 seconds.
- **A local digester on the hot path for bulky public content.** Generation 1's local synthesis had a median of
  58 seconds and three of four hard failures were 120-second Ollama timeouts. That is what caused the pivot to
  cloud-always.
- **Pseudonymisation.** Pseudonymised bank rows are still identifying. Large surface, weaker guarantee than
  simply not sending the rows.
- **Embeddings, until FTS5 with `remove_diacritics 2` has been measured on Czech queries and found wanting.**
  A second model cannot be loaded beside the hot one without evicting it.
- **MCP, in any form.** 340 lines, zero callers, and its sample config points a SQLite server at the
  application's own database. Delete it in week 2.
- **A one-shot blind planning DAG, and learning's playbook DSL / distiller / shadow replayer.** Two to three
  times what one developer finishes, on a system whose presenting complaint is "often fails to deliver".

---

## 7. Roadmap

Every phase ships, leaves production working, and changes something you can perceive.

### Phase 0 — Close the door, stop deploying blind · 4–6 focused days
Half of this is ops rather than Java, and the credential rotation can lock you out, so do it in one sitting with
console access.

**Hour one, before any code:** establish ground truth on the production host — which cloud provider and model
it really uses, which Ollama URL and model, whether `users` contains accounts you did not create, whether
journald shows `Plaintext credential dump requested`, and which generated skills exist. **If anything shows a
stranger, stop the roadmap, rebuild the host and rotate everything.**

Then: delete the credential dump and the deploy/restart/skill-write endpoints; bind to localhost behind
Tailscale or WireGuard; close the real root path (root-own the repo and unit file, drop the `cp`/`daemon-reload`
sudo rules, move the updater to a root-owned timer); rotate the vault key, JWT secret and API keys; the egress
tourniquet (stop inlining attachment text, stop scheduled runs loading your open chat, remove
`credential_manage`'s `value` parameter); and stop `main` auto-deploying to production.

**You see:** the box is reachable only through your VPN, the endpoints that handed out your passwords are gone,
and you get a written paragraph naming exactly what still leaves your machine.

### Phase 1 — Make it tell the truth · 9–11 focused days
Typed outcomes propagated properly — note the success signal is destroyed in `AgentLoop.execute()`, which
returns a bare `String`, not in `TaskQueue`. A persisted step timeline. A per-call ledger in
**billed-equivalent tokens** (`input + 0.1·cache_read + 1.25·cache_write + 5·output`) with the price table as
data. Answer delivery by task id with an outbox, so reloads and second tabs work. Scheduled results delivered
as real chat messages. Stop deleting compressed chat rows. The UI event envelope and module substrate. The
Ollama semaphore moved inside the provider. **Fix the local model configuration from §2.8 and assert at startup
that the configured model exists and advertises `chat`.**

**You see:** you can open yesterday's task and see every step, which tier ran it, what it cost, and the exact
text sent to the cloud. Failures say how they failed instead of showing a green tick. Reloading no longer loses
the answer.

### Phase 2 — Fix the loop's three self-inflicted wounds, contain the code it writes · 5–6 focused weeks
Native tool calling with strict schemas and parallel calls. Append-only byte-stable transcript, with a CI
assertion that call *n+1*'s prefix starts with call *n*'s bytes. `stop_reason` handled. An error taxonomy.
Artifacts with bounded digests. About 15 tested built-in Java tools — **starting with fetch, search and PDF**.
Mandatory rootless Podman, failing closed. Skill lifecycle with a smoke test and rollback. `delegate` replaced
by harness-executed parallel tool calls. Egress logging in observe mode.

**You see:** it does the thing. The lunch-menu task takes three or four steps instead of twelve and succeeds,
and it stops writing Python to fetch a web page.

### Phase 3 — The privacy gate and the local tier's real jobs · 4–5 focused weeks
Labels on artifacts and attachments, a minimal typed `PromptPart` allow-list so an unclassified field is
*denied* rather than silently shipped, the egress gateway enforcing, the canary check (random substrings of
private artifacts that must never appear in an outbound body), `local_extract` with verbatim-quote verification
and domain reconciliation, `table_query` for analysis, and approval gates for tainted tasks.

**You see:** you hand it a bank statement, get an answer, and the "Sent to cloud" tab for that task shows zero
bytes of the statement.

### Phase 4 — Recipes and memory that compounds · 3–4 focused weeks
Recipes compiled deterministically from the recorded step list of a DELIVERED, owner-confirmed task — no DSL,
no interpreter. Site notes keyed by domain. Because `task_steps` already records tier and outcome per step,
"which tier actually works for this step" becomes a `GROUP BY` rather than a guess.

**You see:** the morning invoice job runs for free and posts a real message, and a Czech search finds the right
past task.

**Total: ~90 focused working days.** On this codebase's history, all six proposals are probably 2–3× optimistic
in calendar time: six to eight months at twenty hours a week, four to five at forty.

---

## 8. What this is expected to achieve

Estimates from the code, not measurements. All six architects landed in the same range independently.

| | Today | After Phase 2 | After Phase 4 |
|---|---|---|---|
| Public web task (the "lunch menu" case) | 80–150K tokens, frequent non-delivery | ~6–14K billed-equivalent | same, or ~0 on replay |
| Private document task | full document to the cloud, twice | — | descriptors and schemas only |
| Recurring scheduled task | full cost every run | full cost | ~0 cloud tokens |

**Honest caveat, stated plainly by several architects and confirmed by the judges:** on *public* tasks most of
the saving comes from the deterministic harness — caching, bounded output, built-in tools, native tool
calling — **not** from the local model. The local tier earns its place on private work and recurring replay.
Anyone claiming the local model is what saves tokens on a web-scraping task is selling something.

---

## 9. Decisions only you can make

**These three gate the work:**

1. **Production ground truth, hour one.** Which provider and model, which Ollama URL and model, and has anyone
   registered an account you did not create? The severity of four "critical" findings and about a week of
   Phase 2 turn on the provider answer.
2. **Has the box been compromised, and if the evidence is ambiguous, do you rebuild anyway?** Open registration
   plus a credential-dump endpoint on a public hostname is a combination where "probably fine" is not an
   engineering judgement. This one is yours, not a developer's.
3. **Will you spend one to two days of your own time building the golden set?** Twelve real tasks in Czech and
   English with the answers you expected, plus anonymised copies of a bank statement, three invoice emails and
   a saved restaurant page. **None of the six proposals costed this, and it is the load-bearing assumption of
   the whole plan** — without it every exit criterion below Phase 1 becomes an opinion.

**And these shape it:**

4. **The two-hour Phase 1 experiment:** one real statement answered twice, once with the cloud reading it and
   once blind from a masked sample. Which answer can you live with? Say so *before* three to four weeks go into
   cloud-blind planning.
5. **One cloud provider or two.** Recommendation: one. The two paths have materially different defects and
   keeping both first-class costs a week now plus permanent double testing.
6. **Exposure:** VPN-only (recommended), or a public hostname behind a proxy with its own access control?
7. **Is this genuinely single-owner?** If family members need accounts, Phase 0 builds owner-only gating
   instead of a closed door (~2 more days). If it is only ever yours, a lot of code gets deleted rather than
   fixed.
8. **Sensitivity defaults:** are attachments private by default? Is all mail private, including senders and
   subjects? Are "remember this" facts visible to the cloud?
9. **When local processing fails its checks, may the agent offer you a one-time "let the cloud read this file",
   or must it fail closed?** Recommendation for scheduled runs: never ask, finish PARTIAL with a review item.
10. **May scrubbed cloud payloads be kept for audit, and how long?** Proposal: 14 days, mode 0600, none for
    private tasks. This is what makes privacy verifiable, and it is also a second copy of everything you care
    about.
11. **Local hardware.** What is the box, and is a 16–24 GB GPU on the table? See §2.8 — the model actually
    loaded is 23.7 GB resident, which matters for whether a second model can ever be co-resident.
12. **Do you accept the official Anthropic Java SDK as a dependency?** It reverses the "no heavy SDK" note in
    `build.gradle.kts` and in exchange deletes the hand-rolled parser, retry logic and error handling.
13. **Do you accept roughly six weeks in which no new features ship?** Phases 0–2 are security, measurement and
    repair. Reliability's proposal made this freeze explicit and all three judges flagged it as a product
    decision usually made silently. It is yours.
14. **Which actions must always require approval**, even in a task that read no outside content: sending email,
    shell commands, creating schedules?

---

## 10. What could still go wrong

- **The golden set is the load-bearing assumption and it is the one piece a developer cannot produce.** Without
  it, this becomes general software improvement with no way to prove it helped.
- **Phase 2 rewires the two most-churned files** (63 and 52 commits) against roughly ten tests. This is the
  phase where a solo developer can lose a month and ship neither loop — and it is also the phase that fixes
  what you are complaining about.
- **The local model is not what the code thinks it is** (§2.8). Until that is fixed and measured, every local
  job in Phase 3 is an assumption.
- **Blind planning costs quality exactly where you will judge it.** "List unusual transactions" becomes a
  cloud-written rule set over statistics rather than a frontier model reading the rows. Decision 4 puts that
  choice in front of you early.
- **Approval fatigue is the most likely way this plan quietly fails.** Four of six proposals name it; none
  solves it. For a single owner with unattended morning jobs, "Always" is the modal answer within a week.
- **A half-built privacy gate is worse than none**, because the page will truthfully report "0 bytes left the
  machine" about a channel nobody uses while a real one stays open. That is why sandboxing moved to Phase 2.
- **The audit store is a second plaintext copy of everything the agent has touched**, on the same host. It is
  what makes privacy verifiable and it is also the largest new sensitive file set on disk.
- **Recipes go stale silently.** A vendor changes an invoice layout and the recipe extracts four rows instead
  of six without failing anything. Counts and invariants reduce this; they do not remove it.
- **Deleting `delegate` leaves about five weeks in which the two tiers are nominal.** You value the two-tier
  idea and may read that as a step backwards. The honest counter: the measured local share today is already
  zero, and §2.8 explains why.
- **A five-to-six-week feature freeze on a system you use daily is the most likely abandonment point.** Every
  phase here is required to change something you can perceive, precisely for that reason.
