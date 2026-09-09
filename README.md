# CCAR-F — Claude Certified Architect: Foundations

Hands-on exercises in Java, written while studying for Anthropic's
**Claude Certified Architect – Foundations** exam.

![Under Construction](https://img.shields.io/badge/STATUS-UNDER%20CONSTRUCTION-FFD700?style=for-the-badge&labelColor=000000)
[![Tests](https://github.com/danijeldragicevic/ccar-f/actions/workflows/tests.yml/badge.svg)](https://github.com/danijeldragicevic/ccar-f/actions/workflows/tests.yml)

## Prerequisites

-   **Java 21+** and **Maven 3.9+**
-   An Anthropic API key, exported as an environment variable

### `ANTHROPIC_API_KEY` (required)

The exercises never read the key from a file. The Anthropic SDK's
`AnthropicOkHttpClient.fromEnv()` reads it straight from the environment, so the
only setup is one exported variable:

```bash
# ~/.zshrc  (or ~/.bashrc)
export ANTHROPIC_API_KEY="sk-ant-..."
```

## Build and run

```bash
# build everything
mvn -q install -DskipTests

# run a class with a main()
mvn -q -pl d1-agentic-architecture-and-orchestration exec:java \
    -Dexec.mainClass=dev.ccarf.d1.HelloWorld
```

`common` is a `SNAPSHOT` sibling, so a single-module build needs it installed
first. Add `-am` to build it in the same reactor while editing both:

```bash
mvn -q -pl d1-agentic-architecture-and-orchestration -am compile
```

## Test

```bash
# run every test in every module
mvn test

# run tests for one module only
mvn -pl common test

# run a single test class
mvn -pl common test -Dtest=ConfigTest
```

## Structure

Will be added later, once I'm done with complete project

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
