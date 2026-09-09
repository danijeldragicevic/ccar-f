# CLAUDE.md

Instructions for Claude Code (or any AI assistant) working in this repository.
See [README.md](README.md) for what this project is and how to build it.

## Secrets — read this first

This project uses `ANTHROPIC_API_KEY`, exported as an environment variable (see
README → Prerequisites). It is never stored in a file inside this repo.

**Never read, print, log, or echo the value of `ANTHROPIC_API_KEY`** (or any
variable matching `*_API_KEY`, `*_TOKEN`, `*_SECRET`), in code, in terminal
output, or in chat. Concretely:

- Do not run `echo $ANTHROPIC_API_KEY`, `env | grep ANTHROPIC`, `printenv`, or
  similar — not even to "confirm it's set." Check *presence* only, e.g.
  `[ -n "$ANTHROPIC_API_KEY" ] && echo set || echo unset`.
- Do not write Java (or any) code that prints, logs, or returns the raw key.
  If a value must be shown for debugging, mask it: first 6 and last 4
  characters plus a length, nothing more.
- Do not `cat`, open, or read a `.env` file if one exists locally, and never
  create one — the convention here is an exported shell variable, not a file
  (see README → Prerequisites for why).
- Do not commit, stage, or suggest committing anything that contains a key —
  if a diff or a file you're about to write contains something that looks
  like a credential, stop and flag it instead of proceeding.
- If a task genuinely requires the key's value (e.g. calling an external tool
  that isn't the Anthropic SDK itself), ask the user to supply or apply it
  directly — don't source or print it yourself to pass it along.

The Anthropic Java SDK (`AnthropicOkHttpClient.fromEnv()`) reads the variable
itself. Exercise code should never need to touch it at all.

## Build & run

```bash
mvn -q install -DskipTests                                   # build everything
mvn -q -pl <module> exec:java -Dexec.mainClass=<fully.qualified.Class>
mvn -q -pl <module> -am compile                               # module + its deps
```

`common` is a versioned sibling module — a single-module build needs it
installed first (`-am` builds it in the same reactor instead).

## Structure & conventions

- Java 21, Maven multi-module. One module per exam domain
  (`d1-agentic-architecture-and-orchestration`, etc.), plus `common` for
  shared, non-secret configuration.
- Package per module: `dev.ccarf.d1`, `dev.ccarf.d2`, ... ; shared code in
  `dev.ccarf.common`.
- Config vs. secrets is a hard split: non-secret settings (model ids,
  `max.tokens`, ...) live in `common/src/main/resources/ccarf.properties`,
  read via `dev.ccarf.common.Config` — a classpath resource, not a file path,
  because Maven's working directory is the *module* dir even when invoked
  from the repo root. Secrets are environment variables only, per the section
  above. Don't blur this line by adding a secret to the properties file or a
  config value to the environment.
- Every exercise is a small class with a `main()` — no framework, runnable
  directly via `exec:java` or "run current file" in any IDE.
- No IDE-specific project files are committed (`.vscode/`, `.idea/` are
  gitignored). `.vscode/settings.json` may exist locally for
  `java.configuration.updateBuildConfiguration`, but nothing in the repo
  should depend on it being present.

## Working style

- One task at a time. This project grows exercise by exercise — don't
  scaffold ahead, add modules, or create files beyond what was asked.
- Default to skeletons (Javadoc spec + `TODO` that throws) rather than full
  solutions, unless explicitly asked to implement something.
- State the cost/consequence of an action before doing it when it's not
  obvious (an exercise run spends real API tokens; a `git rm` removes a
  tracked file) — brief is fine, silence is not.
