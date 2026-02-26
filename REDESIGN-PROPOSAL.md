# OwnClaw Redesign Proposal: From Compile-Time Planner to Reactive Agent System

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [Diagnosis: Why the System Fails on Simple Tasks](#2-diagnosis)
3. [Catalog of Design Flaws](#3-catalog-of-design-flaws)
4. [Root Cause: Architectural Mismatch](#4-root-cause)
5. [Proposed Architecture: Reactive Agent Loop](#5-proposed-architecture)
6. [Component Design](#6-component-design)
7. [Tool System (Replacing Skills)](#7-tool-system)
8. [Memory and Learning Subsystem](#8-memory-and-learning)
9. [Multi-Agent Topology](#9-multi-agent-topology)
10. [Self-Healing 2.0](#10-self-healing-20)
11. [Migration Strategy](#11-migration-strategy)
12. [Implementation Phases](#12-implementation-phases)

---

## 1. Executive Summary

OwnClaw's current architecture is a **compile-time planner** — it generates a complete DAG plan upfront, then blindly executes it. This fundamentally cannot handle tasks that require observing intermediate results before deciding what to do next (which is *most real-world tasks*).

The proposed redesign replaces the plan-then-execute model with a **Reactive Agent Loop (RAL)** — an observe-think-act cycle where the agent takes one action at a time, observes the result, and reasons about what to do next. This is paired with:
- **Structured tools** (not generated scripts) with defined input/output schemas
- **Episodic memory** that learns from past task executions
- **Multi-agent specialization** where a Planner, Executor, and Critic collaborate
- **Local-first intelligence** that pushes routine reasoning to the free local model

---

## 2. Diagnosis: Why the System Fails on Simple Tasks

### The Canonical Failure: "Find the PDF menu on acme-restaurant.com and tell me the dessert options"

Here is what happens today:

```
1. User: "Find the PDF menu on acme-restaurant.com and tell me dessert options"
2. Executor classifies → needsMentor=true
3. Compress message → send to Mentor
4. Mentor generates plan WITHOUT SEEING THE PAGE:
   Step 1: http_request(url="https://acme-restaurant.com") 
   Step 2: pdf_parser(url="$1.output.body")     ← WRONG: body is HTML, not a PDF URL
5. Step 1 executes → returns HTML page content
6. Step 2 receives raw HTML as "url" → fails ("not a valid URL")
7. Self-heal: Diagnose → "bad_params" → Repair → still wrong (the PLAN is wrong, not the skill)
8. After 2 repair attempts → definitive failure
9. User gets: "I was unable to complete the task"
```

**The plan was dead on arrival.** The Mentor needed to SEE the page to know where the PDF link is, but in the current architecture, planning happens before any execution. 

This is not a skill bug or a prompt bug — it's a **structural impossibility** in the plan-first architecture.

### Other Systematic Failures

| Task | Why it fails |
|------|-------------|
| "What's on this restaurant's menu?" | Mentor guesses skill params without seeing the page structure |
| "Download the latest report from X" | No ability to browse, find link, then download — must predict link upfront |
| "Summarize the findings from these 3 URLs" | Plan hardcodes all 3 URLs into steps, no iterative processing |
| "Search for X and tell me about the first result" | web_search returns limited DDG results, no ability to follow up |
| "Parse this PDF and extract the table on page 3" | Mentor can't know there's a table on page 3 without first reading the PDF |

**Pattern: Every task requiring intermediate observation before next-step decisions fails systematically.**

---

## 3. Catalog of Design Flaws

### 3.1 God Object: TaskOrchestrator (~1400 lines, 20+ dependencies)

`TaskOrchestrator` is responsible for classification, caching, compression, planning, execution, self-healing, completeness evaluation, summarization, user interaction, and status reporting. This violates Single Responsibility so severely that:
- Any change risks cascading failures across unrelated functionality
- Testing requires mocking 20+ dependencies
- The cognitive load of understanding the flow exceeds what any developer can hold in memory
- Error handling is deeply nested (plan → execute → step → heal → diagnose → repair → retry)

**Impact:** Impossible to evolve the system incrementally. Every modification is high-risk.

### 3.2 Plan-First Architecture (Compile vs. Runtime)

The current flow: `classify → compress → plan → execute → evaluate`. The plan is generated as a static DAG *before any execution happens*. Step parameters are hardcoded at planning time.

Step dependencies use `$N.output` string interpolation, but:
- The Mentor doesn't know what `$1.output` will contain (structure is unknown at plan time)
- Parameter substitution is simple string replacement, not semantic mapping
- If step 1's output structure doesn't match what step 2 expects, the plan silently fails

**Impact:** Cannot handle any task requiring runtime information for decision-making.

### 3.3 Skills Are Untyped Black Boxes

Skills have no output schema. The manifest defines `params` (input names) but says nothing about output structure. Consequences:
- **No chaining contract:** There's no way to know if `http_request`'s output can feed into `pdf_parser`'s input
- **No semantic validation:** SkillValidator only checks "does it crash?" not "does it produce correct results?"
- **No composability:** Every multi-step workflow must be re-planned from scratch

Example: `http_request` returns `{status_code, headers, body, body_length, content_type}` but `pdf_parser` expects `{url}`. There is zero type checking between these — the Mentor must remember the output format of every skill and manually wire them.

**Impact:** Skills compound bugs rather than compound capabilities.

### 3.4 Self-Healing Is Circular and Expensive

The self-heal loop: `Fail → SkillDiagnostician → SkillRepairer → Validate → Retry`

Problems:
- **Same context, same LLM:** The Diagnostician gets the same failure context that caused the issue, and reasons with the same model that originally produced the plan/skill
- **Repairs skills, not plans:** If the plan was wrong (wrong skill chosen, wrong parameter wiring), repairing the skill code cannot fix the problem
- **No ground truth:** Validation is "does it still crash?" not "does it produce the right answer?"
- **Token burn:** Each diagnosis + repair costs ~2000 cloud tokens. Two repair attempts on a wrong-plan failure = ~4000 tokens wasted
- **Speculative fixes:** The Diagnostician often guesses `code_bug` when the real category is `bad_params` or `wrong_skill`

**Impact:** Expensive, unreliable recovery that's worse than failing fast.

### 3.5 Keyword-Based Skill Selection

`TaskContext.involvesWeb()` uses `skill.contains("http") || skill.contains("web") || skill.contains("browse")`. The manifest uses static keyword lists.

- "Get me the price list from acme.com" → may not trigger web strategies (no keyword "http" in the task)
- "What time does this restaurant close?" → matches "web" but also matches anything else
- "Download the spreadsheet" → no keyword match at all
- Generated skills get arbitrary keywords from the LLM (often wrong)

**Impact:** Strategy injection is unreliable, leading to wrong skill selection in plans.

### 3.6 Local LLM Is Underutilized

The Ollama model (14B) only does: classify, compress, summarize, evaluateCompleteness. These are the *least critical* decisions. The *most important* decisions (planning, diagnosis, repair, skill generation) all go to the expensive cloud LLM.

Meanwhile, a 14B model is perfectly capable of:
- Deciding the next single action to take (much simpler than generating a full DAG)
- Extracting specific data from tool outputs
- Evaluating whether a tool result is useful
- Simple reasoning about observed data

**Impact:** Massive unnecessary cloud token spend. The cloud LLM does work that a local model could do, while the local model does work that could be done with regex.

### 3.7 No Memory or Learning

The system has:
- **Plan cache** — exact-match only (different wording = cache miss)
- **Skill repair log** — never consulted during planning
- **Teaching log** — disconnected from the planning pipeline
- **Conversation history** — used for context but not for learning

What it lacks:
- "Last time I tried to parse a PDF from this site, it was behind a login — try a different approach"
- "Requests about restaurant menus usually need: search → browse page → find PDF link → parse PDF"
- "DuckDuckGo instant answers don't work for this type of query — skip to DDG Lite"
- "This user prefers detailed output over summaries"

**Impact:** The system makes the same mistakes repeatedly, never building institutional knowledge.

### 3.8 PromptStrategies Is Hardcoded (4 Strategies Only)

Only `WEB_SCRAPING`, `WEB_FOLLOWUP`, `EMAIL`, and `FILE_OPS` exist. No strategies for:
- Multi-step research (search → read → synthesize)
- Data extraction and transformation
- Interactive clarification workflows
- File download and processing pipelines
- System administration tasks

These can't be added dynamically — they're compile-time Java constants.

**Impact:** The system can't specialize its approach for different task categories.

### 3.9 Thread Safety and Concurrency Issues

- `SkillRunnerService.lastFailureContext` — volatile field used in read-modify-write patterns; not safe for concurrent tasks
- `OllamaSemaphore` serializes ALL local LLM calls globally — one slow classification blocks all other users' tasks
- No backpressure on plan execution — if all sandbox slots are full, tasks queue silently

**Impact:** Multi-user scenarios risk data corruption and deadlocks.

### 3.10 Generated Skills Have No Test Harness

`SkillValidator` checks:
1. Does `skill.py` exist and have a `__main__` guard? (structural)
2. Does output contain `"type": "result"`? (syntactic)
3. Does it crash on a dry run with test params? (crash check)

It does NOT check:
- Does it return the correct data for known inputs?
- Does it handle edge cases (empty page, 404, timeout)?
- Does it follow the output schema that downstream consumers expect?
- Does it regress on previously-working inputs?

**Impact:** Generated skills pass validation but fail on real data, triggering endless self-heal loops.

---

## 4. Root Cause: Architectural Mismatch

All flaws trace back to one fundamental design decision:

> **The system is architected as a static plan compiler operating in a dynamic, uncertain environment.**

Real-world tasks are inherently **sequential-decision problems** — you must observe before you can decide what to do next. The current architecture forces all decisions to be made upfront, before any observation.

This is equivalent to writing an entire program without being able to run it, then hoping it works on the first try. When it doesn't, you debug the source code (self-heal) rather than reconsidering your approach.

### The Right Model: Agent Loop

The field of AI agents has converged on a universal pattern: **observe → think → act → observe → think → act → ... → done**. This is variously called:
- ReAct (Reasoning + Acting)
- OODA Loop (Observe, Orient, Decide, Act)  
- Tool-augmented LLM agents

Every successful agent system (AutoGPT, CrewAI, LangChain agents, Claude's tool use, OpenAI's function calling) uses this pattern. OwnClaw's plan-first approach is an outlier.

---

## 5. Proposed Architecture: Reactive Agent Loop

### Core Principle: **One action at a time, maximum information at each decision point**

```
┌──────────────────────────────────────────────────┐
│                   USER MESSAGE                    │
└───────────────────────┬──────────────────────────┘
                        │
                        ▼
              ┌─────────────────┐
              │   ROUTER AGENT  │ (local LLM, fast)
              │   Classify &    │
              │   Route         │
              └────────┬────────┘
                       │
          ┌────────────┼─────────────┐
          ▼            ▼             ▼
    ┌──────────┐ ┌──────────┐ ┌───────────────┐
    │ CONVERSE │ │ RESEARCH │ │ TASK EXECUTOR  │
    │ (local)  │ │ AGENT    │ │ AGENT          │
    └──────────┘ │(cloud/   │ │(cloud→local    │
                 │ local)   │ │ handoff)       │
                 └────┬─────┘ └───────┬────────┘
                      │               │
                      ▼               ▼
              ┌─────────────────────────────────┐
              │        REACTIVE AGENT LOOP       │
              │                                  │
              │  while not done:                 │
              │    context = gather_context()    │
              │    action = think(context)       │
              │    result = execute(action)      │
              │    context.add(result)           │
              │    if critic.satisfied(context): │
              │       done = true                │
              └─────────────────────────────────┘
                              │
                              ▼
              ┌─────────────────────────────────┐
              │      MEMORY SUBSYSTEM            │
              │  ┌─────────┐ ┌───────────────┐  │
              │  │Episodic │ │  Procedural   │  │
              │  │ Memory  │ │   Memory      │  │
              │  └─────────┘ └───────────────┘  │
              │  ┌─────────────────────────────┐ │
              │  │   Semantic Memory (facts)   │ │
              │  └─────────────────────────────┘ │
              └─────────────────────────────────┘
```

### How the Canonical Example Works in the New Architecture

```
User: "Find the PDF menu on acme-restaurant.com and tell me dessert options"

Agent Loop:
  
  Step 1 — THINK: "I need to see the restaurant's website first to find the menu."
           ACT:   browse_web(url="https://acme-restaurant.com")
           OBSERVE: Page has text "Our Menu" and links including "/menu.pdf"
  
  Step 2 — THINK: "Found a PDF link at /menu.pdf. Let me download and parse it."
           ACT:   pdf_parser(url="https://acme-restaurant.com/menu.pdf")
           OBSERVE: PDF text includes "Desserts: Tiramisu, Panna Cotta, Gelato..."
  
  Step 3 — THINK: "I have the dessert options. Task complete."
           RESPOND: "The dessert options are: Tiramisu, Panna Cotta, Gelato..."

Total cloud tokens: ~1500 (3 thinking steps)
vs. current system: ~6000+ (plan + fail + diagnose + repair + retry + fail + summarize)
```

**Key difference:** Each decision is made WITH the information from previous steps. No guessing.

### The Agent Loop in Detail

```java
public record AgentAction(String tool, Map<String, Object> params, String reasoning) {}
public record AgentObservation(String tool, boolean success, String output, Map<String, Object> structured) {}

public class AgentLoop {
    
    private final ToolRegistry tools;
    private final ThinkingEngine thinker;    // LLM-based reasoning
    private final CriticAgent critic;         // Evaluates if task is done
    private final MemorySubsystem memory;
    private final int maxSteps;
    
    public AgentResult execute(String task, String userId, AgentContext ctx) {
        
        List<AgentObservation> trajectory = new ArrayList<>();
        
        // Retrieve relevant memories
        List<Memory> relevantMemories = memory.recall(task, userId);
        
        for (int step = 0; step < maxSteps; step++) {
            
            // THINK: Decide next action based on everything observed so far
            AgentAction action = thinker.decideNextAction(
                task, 
                trajectory,          // what we've done and seen
                tools.available(),   // what tools we can use
                relevantMemories     // what we've learned from past tasks
            );
            
            // Check if agent wants to respond (no more actions needed)
            if (action.tool().equals("respond")) {
                return AgentResult.success(action.params().get("message").toString(), trajectory);
            }
            
            // ACT: Execute the chosen tool
            ToolResult result = tools.execute(action.tool(), action.params());
            
            // OBSERVE: Record the result
            AgentObservation obs = new AgentObservation(
                action.tool(), result.success(), result.output(), result.structured()
            );
            trajectory.add(obs);
            
            // CRITIC: Is the task done?
            CriticVerdict verdict = critic.evaluate(task, trajectory);
            if (verdict.done()) {
                String response = thinker.synthesizeResponse(task, trajectory);
                memory.store(task, trajectory, verdict);  // Learn from this execution
                return AgentResult.success(response, trajectory);
            }
            
            if (verdict.stuck()) {
                // Agent is going in circles — escalate or ask user
                return handleStuck(task, trajectory, verdict);
            }
        }
        
        return AgentResult.maxStepsReached(trajectory);
    }
}
```

---

## 6. Component Design

### 6.1 Router Agent (Replaces: classification + compression in TaskOrchestrator)

Fast, local-LLM-powered routing that classifies intent and selects the right agent.

```java
public class RouterAgent {
    
    enum Route {
        CONVERSATION,     // Pure chat, no tools needed
        QUICK_ANSWER,     // Can be answered from memory/knowledge
        RESEARCH,         // Needs web search, reading, synthesis
        TASK_EXECUTION,   // Needs tool use (file ops, shell, API calls)
        MULTI_STEP        // Complex task requiring research + execution
    }
    
    public Route route(String message, ConversationContext ctx) {
        // Local LLM classifies — fast, free, good enough for routing
        // Returns one of the Route values
    }
}
```

**Key change:** Router is a thin classifier, not a plan generator. Fails cheap.

### 6.2 Thinking Engine (Replaces: MentorService plan generation)

The Thinking Engine takes the current context (task + trajectory + tools + memory) and produces the next single action. This is the core intelligence.

```java
public class ThinkingEngine {
    
    private final LlmClient localLlm;   // Primary: Ollama
    private final LlmClient cloudLlm;   // Escalation: OpenAI
    private final MemorySubsystem memory;
    
    /**
     * Decide the next action. Uses local LLM by default.
     * Escalates to cloud LLM when:
     *   - Local model's confidence is low
     *   - Task requires complex multi-step reasoning
     *   - Previous local attempts failed
     */
    public AgentAction decideNextAction(
            String task,
            List<AgentObservation> trajectory,
            List<ToolSpec> availableTools,
            List<Memory> memories) {
        
        String prompt = buildThinkingPrompt(task, trajectory, availableTools, memories);
        
        // Try local first (free, fast)
        AgentAction localDecision = localLlm.generateAction(prompt);
        
        if (localDecision.confidence() > 0.7) {
            return localDecision;
        }
        
        // Escalate to cloud for hard decisions
        return cloudLlm.generateAction(prompt);
    }
}
```

**Key changes:**
- Decides ONE action, not an entire plan
- Local-first with cloud escalation
- Has full trajectory context (knows what already happened)
- Has memory context (knows what worked before)

### 6.3 Critic Agent (Replaces: completeness evaluation in ExecutorService)

Dedicated agent that evaluates task progress and provides structured feedback.

```java
public class CriticAgent {
    
    public record CriticVerdict(
        boolean done,
        boolean stuck,
        double progress,        // 0.0 to 1.0
        String assessment,      // Human-readable assessment
        String suggestion       // What to try next if not done
    ) {}
    
    public CriticVerdict evaluate(String task, List<AgentObservation> trajectory) {
        // Uses local LLM to assess:
        // - Does the collected data answer the user's question?
        // - Is the agent making progress or going in circles?
        // - Are there specific gaps that need filling?
    }
}
```

**Key changes:**
- Evaluates after EVERY step, not just at the end
- Detects circular behavior (stuck detection)
- Provides actionable guidance ("suggestion") to the Thinking Engine

### 6.4 Session Manager (Replaces: monolithic TaskOrchestrator state)

Manages task lifecycle and state without being responsible for logic.

```java
public class SessionManager {
    
    public record TaskSession(
        String sessionId,
        String userId,
        String task,
        List<AgentObservation> trajectory,
        SessionState state,        // RUNNING, PAUSED, COMPLETED, FAILED
        Instant startedAt,
        int cloudTokensUsed,
        int localTokensUsed
    ) {}
    
    // Clean, orthogonal responsibilities:
    public TaskSession createSession(String userId, String task) { ... }
    public void recordObservation(String sessionId, AgentObservation obs) { ... }
    public void complete(String sessionId, String result) { ... }
    public void cancel(String sessionId) { ... }
}
```

---

## 7. Tool System (Replacing Skills)

### Why "Tools" Not "Skills"

The current "skill" system conflates two things:
1. **Core operations** (HTTP request, file read, shell command) — stable, well-tested, rarely change
2. **Generated scripts** (LLM-written Python for specific tasks) — fragile, untested, often buggy

The redesign separates these cleanly:

### 7.1 Tool Registry

```java
public interface Tool {
    /** Unique tool identifier */
    String name();
    
    /** Human-readable description for the LLM */
    String description();
    
    /** Structured input schema (JSON Schema format) */
    JsonSchema inputSchema();
    
    /** Structured output schema (JSON Schema format) */
    JsonSchema outputSchema();
    
    /** Execute the tool */
    ToolResult execute(Map<String, Object> params, ToolContext ctx);
    
    /** Whether this tool needs network access */
    boolean requiresNetwork();
    
    /** Estimated execution time in seconds */
    int estimatedDuration();
}

public record ToolResult(
    boolean success,
    String output,                    // Human-readable output
    Map<String, Object> structured,   // Machine-readable structured data
    List<String> artifacts,           // File paths, URLs produced
    ToolResultMetadata metadata       // Timing, bytes transferred, etc.
) {}
```

**Critical change: Tools have OUTPUT SCHEMAS.** The Thinking Engine knows *exactly* what data each tool returns, so it can reason about how to use results in subsequent steps.

### 7.2 Core Tools (Hardcoded, Battle-Tested)

These replace the current Python skills with Java/Kotlin implementations that are part of the application:

| Tool | Replaces | Why Java? |
|------|----------|-----------|
| `web_fetch` | http_request + browse_web | No subprocess overhead, proper SSL, streaming |
| `web_search` | web_search | Integrated retry strategies, multiple engines |
| `file_ops` | file_operations | Direct filesystem access, proper security |
| `shell_exec` | shell_command | Already uses ProcessBuilder, no Python wrapper needed |
| `pdf_parse` | pdf_parser | Apache PDFBox (Java-native), no Python env issues |
| `html_extract` | (NEW) | CSS selector + XPath extraction from HTML |
| `json_extract` | (NEW) | JSONPath queries on structured data |
| `data_transform` | (NEW) | Filter, sort, map, reduce on collections |

**New tools `html_extract`, `json_extract`, and `data_transform` are critical.** They allow the agent to process intermediate results without needing the LLM:

```
Step 1: web_fetch("https://acme.com")     → HTML content
Step 2: html_extract(html=$1, selector="a[href$='.pdf']")  → ["menu.pdf"]
Step 3: pdf_parse(url="https://acme.com/menu.pdf")  → text content
Step 4: respond("Desserts: ...")
```

Step 2 is zero-token — it's a deterministic extraction, not an LLM call.

### 7.3 Dynamic Tools (Python Scripts — Preserved but Constrained)

For genuinely novel operations, the system can still generate Python scripts. But with critical guardrails:

```java
public class DynamicToolService {
    
    /**
     * Generate a new tool ONLY when:
     * 1. No core tool can do the job
     * 2. The Thinking Engine explicitly requests a custom script
     * 3. The script has a well-defined input/output contract
     */
    public Tool generateDynamicTool(
            String purpose,
            JsonSchema inputSchema,     // What the tool receives
            JsonSchema outputSchema,    // What the tool MUST return
            List<TestCase> testCases    // Generated test cases that MUST pass
    ) {
        // 1. Generate Python script via cloud LLM
        // 2. Validate output matches outputSchema for ALL test cases
        // 3. Only register if all tests pass
        // 4. Wrap in Tool interface with proper schemas
    }
}
```

**Key difference:** Generated scripts must satisfy test cases with expected outputs, not just "not crash."

### 7.4 Tool Use Protocol

Instead of the current stdin-JSON → stdout-JSON-lines protocol, tools use a structured Java interface. For Python tools (dynamic), the bridge protocol is tightened:

```python
# Input: strictly typed from inputSchema
# Output: MUST conform to outputSchema

# Example: a custom "extract_table" dynamic tool
def main():
    params = json.load(sys.stdin)
    # params is validated against inputSchema before we get here
    
    result = {
        "type": "result",
        "status": "success",
        "output": {
            "headers": ["Col1", "Col2"],    # MUST match outputSchema
            "rows": [["a", "b"], ["c", "d"]]
        }
    }
    print(json.dumps(result))
    
# The system validates output against outputSchema AFTER execution
# If validation fails → tool failure (not a silent success with bad data)
```

---

## 8. Memory and Learning Subsystem

### 8.1 Three Memory Types

```
┌─────────────────────────────────────────────────┐
│                 MEMORY SUBSYSTEM                 │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  EPISODIC MEMORY                         │    │
│  │  "What happened in past tasks"           │    │
│  │                                          │    │
│  │  task: "get PDF menu from acme.com"      │    │
│  │  trajectory: [browse→find_link→parse]    │    │
│  │  outcome: success                        │    │
│  │  lesson: "restaurant sites have PDF      │    │
│  │           links, browse first"           │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  PROCEDURAL MEMORY                       │    │
│  │  "Learned procedures for task types"     │    │
│  │                                          │    │
│  │  pattern: "get_document_from_website"    │    │
│  │  recipe: browse → find_link → download   │    │
│  │          → parse → extract               │    │
│  │  confidence: 0.85 (succeeded 17/20)      │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  SEMANTIC MEMORY                         │    │
│  │  "Facts about the environment"           │    │
│  │                                          │    │
│  │  fact: "DuckDuckGo instant answers work  │    │
│  │        poorly for product searches"      │    │
│  │  fact: "acme.com requires SSL, no auth"  │    │
│  │  fact: "user prefers detailed output"    │    │
│  └──────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### 8.2 Memory Storage (SQLite)

```sql
-- Episodic Memory: task executions
CREATE TABLE episodic_memory (
    id INTEGER PRIMARY KEY,
    user_id TEXT NOT NULL,
    task_embedding BLOB,              -- Vector embedding of the task description
    task_summary TEXT NOT NULL,        -- Compressed task description
    trajectory_json TEXT NOT NULL,     -- [{tool, params, result_summary}]
    outcome TEXT NOT NULL,             -- success | partial | failure
    lesson TEXT,                       -- LLM-extracted lesson learned
    tool_sequence TEXT,                -- "browse_web→html_extract→pdf_parse"
    duration_ms INTEGER,
    cloud_tokens_used INTEGER,
    created_at TEXT DEFAULT (datetime('now'))
);

-- Procedural Memory: learned recipes
CREATE TABLE procedural_memory (
    id INTEGER PRIMARY KEY,
    pattern_name TEXT UNIQUE NOT NULL,  -- "get_document_from_website"
    pattern_description TEXT NOT NULL,
    recipe_json TEXT NOT NULL,          -- ordered tool sequence with param templates
    success_count INTEGER DEFAULT 0,
    failure_count INTEGER DEFAULT 0,
    confidence REAL DEFAULT 0.5,
    last_used TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);

-- Semantic Memory: facts and observations
CREATE TABLE semantic_memory (
    id INTEGER PRIMARY KEY,
    user_id TEXT,                       -- null = global fact
    category TEXT NOT NULL,             -- 'environment', 'tool', 'site', 'preference'
    fact TEXT NOT NULL,
    confidence REAL DEFAULT 0.8,
    source TEXT,                        -- Which task produced this fact
    created_at TEXT DEFAULT (datetime('now')),
    expires_at TEXT                     -- Some facts expire (e.g., site content)
);
```

### 8.3 Memory Recall

```java
public class MemorySubsystem {
    
    /**
     * Recall relevant memories for a task.
     * Uses semantic similarity (embedding cosine distance) + keyword matching.
     */
    public List<Memory> recall(String task, String userId) {
        List<Memory> memories = new ArrayList<>();
        
        // 1. Find similar past tasks (episodic)
        memories.addAll(episodicStore.findSimilar(task, userId, limit: 3));
        
        // 2. Find applicable procedures
        memories.addAll(proceduralStore.findApplicable(task, limit: 2));
        
        // 3. Find relevant facts
        memories.addAll(semanticStore.findRelevant(task, userId, limit: 5));
        
        return memories;
    }
    
    /**
     * After task completion, extract and store learnings.
     * Uses local LLM to extract lessons — this is cheap and valuable.
     */
    public void learnFromExecution(String task, List<AgentObservation> trajectory, boolean success) {
        // Store episodic memory
        episodicStore.store(task, trajectory, success);
        
        // Extract procedural pattern if successful
        if (success && trajectory.size() > 1) {
            String toolSequence = trajectory.stream()
                .map(AgentObservation::tool)
                .collect(Collectors.joining("→"));
            proceduralStore.reinforceOrCreate(task, toolSequence, trajectory);
        }
        
        // Extract facts from observations
        for (AgentObservation obs : trajectory) {
            semanticStore.extractFacts(obs);
        }
    }
}
```

### 8.4 How Memory Helps the Agent

**Before (no memory):**
```
User: "Get the lunch menu from bella-italia.com"
Agent: [struggles through same browse→fail→heal→fail pattern]
```

**After (with memory):**
```
User: "Get the lunch menu from bella-italia.com"
Memory recall:
  - Episodic: "Similar task 'get PDF menu from acme-restaurant.com' succeeded with browse→find_pdf→parse"
  - Procedural: Recipe 'get_document_from_website' (confidence 0.85): browse→extract_links→download→parse
  - Semantic: "Restaurant sites typically have PDF menus linked from main page"

Agent: "I've seen similar tasks. I'll browse the site first to find the menu link."
  Step 1: browse_web(url="https://bella-italia.com") → [finds "Menu (PDF)" link]
  Step 2: pdf_parse(url="https://bella-italia.com/lunch-menu.pdf") → [menu text]
  Step 3: respond(extracted dessert options)
```

---

## 9. Multi-Agent Topology

### Why Multiple Agents?

A single agent with one prompt tries to be everything: researcher, planner, executor, critic. This leads to bloated prompts, confused roles, and wasted tokens.

Specialized agents with focused prompts are:
- Cheaper (smaller prompts per call)
- More reliable (clear responsibilities)
- Composable (combine agents for complex tasks)

### 9.1 Agent Roles

```
┌──────────────────────────────────────────────────┐
│                 ROUTER AGENT                      │
│  Model: Local (14B)                              │
│  Role: Classify intent, select specialist agent   │
│  Cost: ~0 (local)                                │
└──────────┬───────────────┬───────────────────────┘
           │               │
           ▼               ▼
┌─────────────────┐  ┌─────────────────────────────┐
│  CONVERSATION   │  │  TASK AGENT                  │
│  AGENT          │  │  Model: Local (default),     │
│  Model: Local   │  │         Cloud (escalation)   │
│  Role: Chat,    │  │  Role: Execute reactive loop │
│  Q&A, memory    │  │  with tools and observation  │
│  recall         │  │                              │
└─────────────────┘  └──────────┬──────────────────┘
                                │
                     ┌──────────┼──────────┐
                     │          │          │
                     ▼          ▼          ▼
              ┌───────────┐ ┌──────┐ ┌────────┐
              │ RESEARCHER│ │WRITER│ │ CRITIC │
              │ Sub-agent │ │Sub-  │ │Sub-    │
              │           │ │agent │ │agent   │
              └───────────┘ └──────┘ └────────┘
```

### 9.2 Agent Composition for Complex Tasks

For a complex task like "Research the top 5 CRM tools and create a comparison spreadsheet":

```
Router → TASK_AGENT
  TASK_AGENT delegates to:
    1. RESEARCHER: Search for CRM tools, browse top results, extract features
    2. RESEARCHER: For each tool, get pricing, features, ratings
    3. WRITER: Compile research into structured comparison
    4. TASK_AGENT: Use file_ops to write CSV/spreadsheet
    5. CRITIC: Verify completeness and accuracy
```

Each sub-agent runs its own agent loop with its own tools and trajectory.

### 9.3 Cloud Token Budget: Tiered Strategy

```
Tier 1 — FREE (Local LLM):
  - Routing (always)
  - Simple tool selection (when confidence > 0.7)
  - Data extraction from tool outputs
  - Conversation responses
  - Critic evaluation (final check)

Tier 2 — CHEAP (Cloud, single call):
  - Complex tool selection (when local confidence < 0.7)
  - Multi-tool reasoning
  - Dynamic tool generation request

Tier 3 — EXPENSIVE (Cloud, multiple calls):
  - Multi-agent research coordination
  - Dynamic tool code generation + test case generation
  - Complex synthesis/writing tasks
```

**Expected token savings vs. current system: 60-80%** because:
- Most routing/classification is local (currently uses cloud for planning)
- Single-action decisions are much smaller prompts than full DAG plans
- No speculative self-healing burns
- Memory reduces redundant reasoning

---

## 10. Self-Healing 2.0

### Current Problem: Self-healing repairs skills when the problem is the plan

### New Approach: Multi-Level Recovery

```
Level 0 — TOOL RETRY (free):
  Network timeout? Retry with backoff.
  HTTP 429? Wait and retry.
  Sandbox crash? Restart process.
  → No LLM involved. Pure mechanical retry.

Level 1 — ALTERNATIVE APPROACH (cheap, local LLM):
  Tool returned empty/useless data?
  Agent thinks: "web_fetch didn't find the PDF link. Let me try browse_web 
  with deeper link extraction."
  → This is NATURAL in the agent loop — it's just the next think step.
  → No special "self-heal" mode needed.

Level 2 — STEP BACK & RETHINK (medium, local → cloud):
  Agent is going in circles (Critic detected "stuck")?
  Agent pauses, reviews full trajectory, generates new strategy.
  → "I've tried three approaches to find this PDF. Let me search 
     for 'bella-italia menu PDF' directly."

Level 3 — ESCALATE TO USER (free):
  Agent can't make progress after N attempts?
  Ask_user: "I found the website but couldn't locate a menu PDF. 
  Could you tell me where on the site the menu is, or paste the URL?"
  → Honest, useful, and doesn't waste tokens on hopeless repairs.

Level 4 — TOOL REPAIR (expensive, cloud — rare):
  A core tool is genuinely broken (not bad params, actually broken code)?
  → This should almost never happen for core Java tools.
  → For dynamic Python tools, regenerate with better test cases from 
     the failure data.
```

**Key insight:** In a reactive loop, Level 1 recovery is FREE — it's just the agent's next decision. The current system's expensive diagnose→repair cycle becomes unnecessary for 90% of failures because the failure was never in the tool code; it was in the tool selection/parameterization, which the agent naturally corrects on the next iteration.

---

## 11. Migration Strategy

### Don't rewrite. Refactor incrementally.

The existing codebase has valuable infrastructure: sandbox management, Telegram/WebUI interfaces, conversation storage, user management, token tracking. Only the orchestration core needs replacement.

### Phase 0: Preparation (Keep running, add foundations)

1. **Introduce `Tool` interface** alongside existing skills
2. **Wrap existing Python skills** as `Tool` implementations (adapter pattern)
3. **Add output schemas** to existing skills progressively
4. **Create `AgentLoop` class** that can be activated per-user or per-task

### Phase 1: Parallel Operation

```
TaskOrchestrator (old) ←→ Feature flag ←→ AgentLoop (new)
         │                                        │
    Current skills                         Tool registry
    Plan-first                           Reactive loop
    
Users can opt: /mode classic | /mode agent
```

### Phase 2: Core Java Tools

1. Implement `web_fetch`, `html_extract`, `json_extract` as Java tools
2. Implement `pdf_parse` using Apache PDFBox
3. Implement `data_transform` for structured data manipulation
4. Keep Python skills as fallback dynamic tools

### Phase 3: Memory System

1. Add episodic memory table + storage after each task
2. Implement embedding-based retrieval (ONNX Runtime for local embeddings)
3. Add procedural memory extraction
4. Wire memory into `ThinkingEngine` prompts

### Phase 4: Multi-Agent

1. Extract Router, Critic, Researcher as separate agent types
2. Implement agent composition protocol
3. Add cloud escalation logic with confidence thresholds

### Phase 5: Retire Legacy

1. Remove `TaskOrchestrator` once `AgentLoop` is proven
2. Remove `SkillGenerator`, `SkillDiagnostician`, `SkillRepairer` (replaced by simpler dynamic tool service)
3. Simplify codebase

---

## 12. Implementation Phases

### Immediate (Week 1-2): Prove the Agent Loop

**Goal:** Get a minimal agent loop working for the canonical failure case.

Files to create/modify:
```
src/main/java/com/ownclaw/agent/
  AgentLoop.java           — Core loop: think → act → observe
  AgentAction.java         — Record: tool, params, reasoning
  AgentObservation.java    — Record: tool, success, output, structured
  AgentResult.java         — Record: success, response, trajectory
  ThinkingEngine.java      — LLM decides next action
  CriticAgent.java         — Evaluates task progress
  
src/main/java/com/ownclaw/tools/
  Tool.java                — Interface with input/output schemas
  ToolRegistry.java        — Discovers and manages tools
  ToolResult.java          — Structured tool output
  WebFetchTool.java        — Java-native HTTP + HTML extraction
  HtmlExtractTool.java     — CSS selector extraction
  PdfParseTool.java        — Apache PDFBox wrapper
  
  adapters/
    PythonSkillAdapter.java — Wraps existing Python skills as Tools
```

**Key metric:** Can the agent loop handle "Find the PDF menu on X restaurant's site and tell me the dessert options" successfully?

### Short-term (Week 3-4): Memory + Optimization

- Implement episodic memory storage and recall
- Add procedural memory extraction
- Implement local-first thinking with cloud escalation
- Wire memory into ThinkingEngine prompts

### Medium-term (Week 5-8): Full Agent System

- Multi-agent topology (Router, Researcher, Task Executor, Critic)
- Dynamic tool generation with test-case validation
- Agent composition for complex tasks
- Retire legacy TaskOrchestrator

---

## Appendix A: Thinking Engine Prompt Design

The Thinking Engine prompt is the core intelligence of the system. Unlike the current monolithic `BASE_PROMPT_TEMPLATE + PLAN_SECTION`, it's focused on a single decision:

```
You are an AI assistant with access to tools. Your job is to accomplish
the user's task by using tools one at a time.

## Current Task
{task_description}

## Available Tools
{tool_list_with_schemas}

## What You've Done So Far
{trajectory_formatted}

## Relevant Memories
{memories_formatted}

## Instructions
1. Review what you know so far from previous tool calls.
2. Decide what single action to take next.
3. If you have enough information to answer the user, use the "respond" tool.
4. If you need more data, choose the most appropriate tool and parameters.

Respond with JSON:
{
  "reasoning": "Brief explanation of your thinking",
  "tool": "tool_name",
  "params": { ... }
}
```

This prompt grows naturally as the trajectory extends. Each step adds ~100-200 tokens of context. A typical 3-5 step task uses ~2000-3000 total tokens across all thinking calls — comparable to ONE plan generation in the current system, but with far better results.

## Appendix B: Comparison Table

| Aspect | Current (Plan-First) | Proposed (Reactive Agent) |
|--------|---------------------|---------------------------|
| Decision point | All upfront | One at a time |
| Information at decision | Task text only | Task + all prior observations |
| Recovery from failure | Diagnose → Repair skill code | Just try a different approach |
| Cloud token cost | High (plan + heal + re-plan) | Low (small per-step prompts) |
| Local LLM utilization | 20% (classify/compress only) | 70% (most thinking steps) |
| New task type support | Needs new PromptStrategy | Agent figures it out |
| Learning | None (plan cache only) | Episodic + procedural + semantic |
| Tool reuse | Each task re-plans from scratch | Memories suggest proven tool sequences |
| Composability | None (monolithic plans) | Agents delegate to sub-agents |
| Testability | Mock 20+ dependencies | Each component testable in isolation |
| Debugging | 1400-line trace through orchestrator | Clear trajectory of think→act→observe |

## Appendix C: Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Agent loop runs forever | Hard step limit (default: 10) + Critic stuck-detection |
| Local LLM makes bad decisions | Confidence threshold → cloud escalation |
| More LLM calls = more latency | Most calls are local (50ms) not cloud (2s) |
| Memory grows unbounded | TTL expiry + LRU eviction + periodic compaction |
| Breaking existing functionality | Parallel operation with feature flag during migration |
| Dynamic tool generation still buggy | Required test cases + output schema validation |
| Cloud cost increases | Tiered strategy ensures most work is local |
