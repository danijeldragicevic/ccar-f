# CLAUDE.md

Instructions for Claude Code (or any AI assistant) working in this repository.
See [README.md](README.md) for what this project is and how to build it.

## Secrets — read this first

This project's `ANTHROPIC_API_KEY` lives in a **`.env` file at the repository
root** (see README → Prerequisites). That file is gitignored and **holds
secrets**. It is deliberately *not* an exported environment variable: an
exported key is visible to every process started from that shell, including
AI coding assistants, which may pick it up and bill against it.

**The `.env` file is off-limits. Never read, list, or parse it in any way** —
not to "check the format", not to "confirm the key is there", not even
partially. Concretely:

- Do not open, `cat`, `head`, `tail`, `less`, `grep`, `sed`, `awk`, `source`,
  or `.` it, and do not read it with the Read tool or any other tool.
- Do not list or enumerate its contents or its keys, and do not print its
  name in directory listings you produce (`ls -a`, `find`, `tree`, ...). If
  you truly need to know whether it exists, use a presence check that prints
  nothing from the file: `[ -f .env ] && echo present || echo missing`.
- Keep it out of searches: recursive commands (`grep -r`, `find ... -exec`,
  `rg --hidden`/`--no-ignore`, `xargs cat`, ...) must exclude it, e.g. restrict
  them with `--include='*.java'` or `--exclude=.env`. A search that opens the
  file counts as reading it.
- Do not create, edit, overwrite, move, copy, or delete it. The user maintains
  it by hand.
- Do not write code that prints, logs, or returns its contents. The only code
  allowed to read it is `dev.ccarf.common.AnthropicClients`, which passes the
  key straight to the SDK client; error messages there name the file and the
  missing entry, never a value. Tests use fake `.env` files in a temporary
  directory, never the real one.

**Never read, print, log, or echo the value of `ANTHROPIC_API_KEY`** (or any
value matching `*_API_KEY`, `*_TOKEN`, `*_SECRET`), in code, in terminal
output, or in chat:

- If a value must be shown for debugging, mask it: first 6 and last 4
  characters plus a length, nothing more.
- Do not commit, stage, or suggest committing anything that contains a key —
  if a diff or a file you're about to write contains something that looks
  like a credential, stop and flag it instead of proceeding.
- If a task genuinely requires the key's value (e.g. calling an external tool
  that isn't the Anthropic SDK itself), ask the user to supply or apply it
  directly — don't read it from `.env` yourself to pass it along.

Exercises create their client with `AnthropicClients.fromDotEnv()` (in
`common`), which finds `.env` and hands the key to the SDK. Exercise code
never touches the key itself.

## Build & run

```bash
mvn -q install -DskipTests                                   # build everything
mvn -q -pl <module> exec:java -Dexec.mainClass=<fully.qualified.Class>
mvn -q -pl <module> -am compile                               # module + its deps
```

`common` is a versioned sibling module — a single-module build needs it
installed first (`-am` builds it in the same reactor instead).

## Structure & conventions

- Java 25, Maven multi-module. One module per exam domain
  (`d1-agentic-architecture-and-orchestration`, etc.), plus `common` for
  shared configuration (`Config`) and client creation (`AnthropicClients`).
- Package per module: `dev.ccarf.d1`, `dev.ccarf.d2`, ... ; shared code in
  `dev.ccarf.common`.
- Config vs. secrets is a hard split: non-secret settings (model ids,
  `max.tokens`, ...) live in `common/src/main/resources/ccarf.properties`,
  read via `dev.ccarf.common.Config` — a classpath resource, not a file path,
  because Maven's working directory is the *module* dir even when invoked
  from the repo root. Secrets live only in the root `.env` file, per the
  section above. Don't blur this line by adding a secret to the properties
  file or a config value to `.env`.
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
