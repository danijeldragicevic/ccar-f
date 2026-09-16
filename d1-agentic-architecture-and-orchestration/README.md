# Domain 1 — Agentic Architecture & Orchestration

Module `d1-agentic-architecture-and-orchestration`, base package `dev.ccarf.d1`.
27% of the CCAR-F exam. See the [repo root README](../README.md) for
build/run/test commands and for why the code across exercises in a section
is intentionally duplicated rather than DRY.

## Structure

This domain's curriculum is broken into sections; each gets its own Java
sub-package under `dev.ccarf.d1`.

| #   | Section                                 | Package                   | Status      |
| --- | --------------------------------------- | ------------------------- | ----------- |
| 1.1 | Agentic Loops                           | `agenticloops`            | Done        |
| 1.2 | Multi-Agent Orchestration               | `multiagentorchestration` | Done        |
| 1.3 | Subagent Invocation and Context Passing | —                         | Not started |
| 1.4 | Workflow Enforcement and Handoff        | —                         | Not started |
| 1.5 | Agent SDK Hooks                         | —                         | Not started |
| 1.6 | Task Decomposition Strategies           | —                         | Not started |
| 1.7 | Session State and Resumption            | —                         | Not started |

This table is updated as new sections are started — expect more packages
under `dev.ccarf.d1` over time.

## 1.1 Agentic Loops (`agenticloops`)

Builds a multi-tool agent loop against the Messages API, one exercise at a
time:

-   `ToolDefinitions` — defines a `calculator` and a `web_search` (stub) tool.
-   `AgenticLoop` — the base loop: send a request, inspect `stop_reason`,
    loop while it's `tool_use`.
-   `ToolExecutionLoop` — executes the requested tool(s) and appends the
    `tool_result`(s) back into the conversation.
-   `FinalResponseLoop` — extracts and returns the final response's text once
    `stop_reason` is `end_turn`.
-   `SequentialToolCallLoop` — a prompt that forces two _sequential_,
    dependent tool calls (search, then calculate using that result), to
    exercise the full multi-turn lifecycle.
-   `SafetyCappedLoop` — adds a bounded iteration cap as a fallback safety net,
    distinct from (and never the primary substitute for) the `stop_reason`
    check that actually ends the loop.

Each class handles `stop_reason` values the same, deliberate way: `end_turn`
is checked explicitly to return the final result, `tool_use` continues the
loop, and any other value (`max_tokens`, `refusal`, `pause_turn`,
`stop_sequence`) is treated as an error rather than silently returned as if
it were a normal answer.

## 1.2 Multi-Agent Orchestration (`multiagentorchestration`)

Builds a hub-and-spoke multi-agent research system, one exercise at a time:

-   `CoordinatorAgent` — the central hub: accepts a broad research topic and
    produces a structured report. The coordinator owns decomposition, subagent
    selection, and result aggregation; it never researches directly.
-   `TaskDecompositionAgent` — decomposes a broad topic into at least 5
    distinct subtopics, guarding against narrow decomposition (e.g. settling
    for just solar and wind on "renewable energy" and silently omitting
    geothermal, tidal, biomass, fusion).
-   `SubagentInvocation` — spawns a web-search subagent and a document-analysis
    subagent with explicit context passing: each subagent prompt carries every
    fact it needs inline, since subagent isolation means no shared memory and
    nothing is inherited from the coordinator.
-   `ResultAggregation` — merges both subagents' findings and rates coverage
    per subtopic (`WELL_COVERED` / `PARTIALLY_COVERED` / `MISSING`), evaluating
    every subtopic explicitly rather than skipping ones neither subagent
    mentioned.
-   `IterativeRefinementLoop` — closes the loop: on detected gaps, re-delegates
    to subagents with queries scoped to just the missing subtopics, and
    re-aggregates until coverage is sufficient or a max iteration count is hit.
    This is what separates a coordinator from a single-shot dispatcher.
-   `RenewableEnergyCoverageVerification` — an end-to-end check against the
    "renewable energy technologies" topic, verifying the final output covers
    solar, wind, geothermal, tidal, biomass, and fusion. The diagnostic lesson:
    if coverage is incomplete, check the coordinator's decomposition first — a
    subtopic it never created can never be well-covered.
