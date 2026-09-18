# Domain 1 — Agentic Architecture & Orchestration

Module `d1-agentic-architecture-and-orchestration`, base package `dev.ccarf.d1`.
27% of the CCAR-F exam. See the [repo root README](../README.md) for
build/run/test commands and for why the code across exercises in a section
is intentionally duplicated rather than DRY.

## Structure

This domain's curriculum is broken into sections; each gets its own Java
sub-package under `dev.ccarf.d1`.

| #   | Section                                 | Package                               | Status      |
| --- | --------------------------------------- | ------------------------------------- | ----------- |
| 1.1 | Agentic Loops                           | `agenticloops`                        | Done        |
| 1.2 | Multi-Agent Orchestration               | `multiagentorchestration`             | Done        |
| 1.3 | Subagent Invocation and Context Passing | `subagentinvocationandcontextpassing` | Done        |
| 1.4 | Workflow Enforcement and Handoff        | —                                     | Not started |
| 1.5 | Agent SDK Hooks                         | —                                     | Not started |
| 1.6 | Task Decomposition Strategies           | —                                     | Not started |
| 1.7 | Session State and Resumption            | —                                     | Not started |

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

## 1.3 Subagent Invocation and Context Passing (`subagentinvocationandcontextpassing`)

The exam material for this section describes the Claude Agent SDK's own
vocabulary — a `query()` call whose `options` include `allowedTools`
containing `Task`/`Agent`, plus subagent definitions under `options.agents`.
There is no Java build of the Agent SDK (it ships for Python and TypeScript
only), and the plain Anthropic Messages API this module calls has no
`query()`/`allowedTools`/`agents` equivalent at all — that whole mechanism is
Agent SDK-internal. Every exercise below recreates the same *behavior* by
hand instead of reproducing that literal shape; `TaskToolCoordinatorAgent`'s
Javadoc has the full translation note.

-   `TaskToolCoordinatorAgent` — gives the coordinator a real `task` `Tool`
    registered on its request: the raw-SDK stand-in for an Agent SDK
    coordinator's `allowedTools` containing `Task`/`Agent`. A tool absent from
    the Messages API's `tools` list can never be invoked by the model, which
    is arguably a stricter guarantee than the Agent SDK's own allowedTools
    gating.
-   `SubagentDefinitions` — defines a `web_search_agent` and a
    `document_analysis_agent`, each an `AgentDefinition` record
    (`description`/`systemPrompt`/`tools`) — the stand-in for `options.agents`
    — with a tool set restricted to only what that role needs: the web-search
    subagent gets a `web_search` tool, the document-analysis subagent gets a
    `read_document` tool, and neither can reach the other's.
-   `StructuredFindingFormat` — the `Finding` record: content (`claim` from
    web search, `analysis` from document analysis) kept separate from
    metadata (`sourceUrl`, `documentName`, `pageNumber`, `confidence`,
    `retrievedBy`), so provenance survives being passed on to a synthesis
    subagent instead of being flattened into unsourced prose.
-   `SynthesisWithPreservedMetadata` — invokes both research subagents, then
    hands their *complete*, combined findings — every metadata field intact —
    to a synthesis subagent. Stripping metadata before this handoff is the
    root cause of a synthesis agent producing unsourced claims; keeping it is
    the fix.
-   `AttributionVerification` — verifies every claim in the synthesis
    subagent's report is attributed to a specific source (a `[citation]`
    per line). An orphaned claim here means the context-passing pipeline
    failed to get metadata to the synthesis subagent for that finding — not a
    synthesis-prompt defect, so the fix is to trace back to the pipeline, not
    to reword the prompt.
-   `ParallelSubagentDispatch` — refactors the coordinator to spawn both
    research subagents in parallel: it must emit two `task` tool_use blocks in
    a single response rather than one now and one in a later turn. Both calls
    are dispatched concurrently via `CompletableFuture` and awaited together
    before the coordinator proceeds to synthesis, since sequential spawning of
    two independent subagents only wastes wall-clock time.
