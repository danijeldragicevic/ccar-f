# CCAR-F — Claude Certified Architect: Foundations

Hands-on exercises in Java, written while studying for Anthropic's
**Claude Certified Architect – Foundations** exam.

[![Tests](https://github.com/danijeldragicevic/ccar-f/actions/workflows/tests.yml/badge.svg)](https://github.com/danijeldragicevic/ccar-f/actions/workflows/tests.yml)

## Prerequisites

-   **Java 21+** and **Maven 3.9+**
-   An Anthropic API key, exported as an environment variable

### `ANTHROPIC_API_KEY` (required)

Get a key from the [Anthropic Console](https://console.anthropic.com/settings/keys)
(sign in or create an account, then **Create Key**).

The exercises never read the key from a file. The Anthropic SDK's
`AnthropicOkHttpClient.fromEnv()` reads it straight from the environment, so the
only setup is one exported variable:

```bash
# ~/.zshrc  (or ~/.bashrc)
export ANTHROPIC_API_KEY="sk-ant-..."
```

## Configuration vs. secrets

Non-secret settings (model ids, `max.tokens`, ...) live in
`common/src/main/resources/ccarf.properties` and are read via
`dev.ccarf.common.Config`. The `ANTHROPIC_API_KEY` secret is never put in that
file — it stays an environment variable only (see above).

## Build and run

```bash
# build everything
mvn -q install -DskipTests

# run a class with a main() — one example below; each module accumulates more
# exercises over time, browse its src/main/java tree for the full list
mvn -q -pl d1-agentic-architecture-and-orchestration exec:java \
    -Dexec.mainClass=dev.ccarf.d1.agenticloops.ToolDefinitions
```

`common` is a `SNAPSHOT` sibling, so a single-module build needs it installed
first. Add `-am` to build it in the same reactor while editing both:

```bash
mvn -q -pl d1-agentic-architecture-and-orchestration -am compile
```

Running an exercise calls the real Anthropic API and spends actual tokens —
building/compiling does not.

## Test

```bash
# run every test in every module
mvn test

# run tests for one module only
mvn -pl common test

# run a single test class
mvn -pl common test -Dtest=ConfigTest
```

## Exam domains

| #   | Domain                                 | Weight |
| --- | -------------------------------------- | ------ |
| 1   | Agentic Architecture & Orchestration   | 27%    |
| 2   | Tool Design & MCP Integration          | 18%    |
| 3   | Claude Code Configuration & Workflows  | 20%    |
| 4   | Prompt Engineering & Structured Output | 20%    |
| 5   | Context Management & Reliability       | 15%    |

## Links

-   [Anthropic Academy — CCAR-F](https://anthropic.skilljar.com/claude-certified-architect-foundations-certification/444989)
-   [Prep course path](https://anthropic-partners.skilljar.com/page/claude-certified-architect-foundations-prep-courses)
-   [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java)
-   [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
