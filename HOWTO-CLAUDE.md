# HOWTO — How I used Claude Code for Vidocq

> A feedback post on collaborating with **Claude Code (Opus 4.7 / 1M)**
> to build `vidocq-servlet-chappe-extension` and go from **0 → 99.2 %
> of the official Jakarta Servlet 6.1 TCK** (608/613) in ~60 commits.

---

## 1. Collaboration philosophy

Claude is neither a snippet copilot nor an oracle: it is an **autonomous
pair-programmer** to whom you delegate milestones and who delivers a diff +
a commit. My role: frame, arbitrate, validate. Its role: explore, code,
decompile, test, retry.

Rules I set (that held throughout):

- **One commit = one functional milestone** (e.g. `M2b filters`, `M2c sessions`, …).
  Never "WIP" or catch-all commits.
- **No destructive shortcut** (`--no-verify`, `git reset --hard`, …) without
  explicit authorisation.
- **Root cause before workaround**: when a TCK test fails, understand *why*
  before hacking the code.
- **English everywhere** (commit messages, code, docs).

---

## 2. Claude Code configuration

### 2.1 Targeted permissions (`.claude/settings.local.json`)

Rather than allowing `Bash(*)`, I **whitelisted on-the-fly** the useful
commands:

```json
{
  "permissions": {
    "allow": [
      "Bash(git reset:*)",
      "Bash(git commit:*)",
      "Bash(git add:*)",
      "Bash(jar tf *)",
      "Bash(javap -p -c /tmp/*.class)",
      "Bash(unzip -p /Users/yblazart/.m2/repository/jakarta/tck/...)",
      "mcp__plugin_context-mode_context-mode__ctx_batch_execute",
      "mcp__plugin_context-mode_context-mode__ctx_search"
    ]
  }
}
```

**Why it works**: Claude decompiles TCK classes (`javap -c`) to understand what
the test expects on the server side. I allow it to do so without prompting each
time, **but only in `/tmp`** and **only on TCK JARs**. No `Bash(rm *)`,
no `Bash(curl *)`.

### 2.2 Global instructions (`~/.claude/CLAUDE.md`)

- **Language**: English required.
- **Tone**: concise, no internal narration, no end-of-turn summaries.
- **RTK** (Rust Token Killer): CLI proxy that rewrites git/mvn commands to
  save 60–90 % of output tokens.

### 2.3 Context-mode MCP

All tools producing >20 lines (Maven logs, `javap` output, TCK reports) go
through `ctx_batch_execute` / `ctx_execute_file`. The result stays in an
FTS5-indexed sandbox; Claude only retrieves the lines it needs via `ctx_search`.
Without this, a single official TCK `mvn test` run fills the context on its own.

---

## 3. TCK-driven methodology

The guiding principle behind the last 50 commits:

```
1. Run the official TCK → retrieve the list of failing tests
2. For each failing test:
   a. `unzip -p servlet-tck-runtime.jar <TestClass>.class > /tmp/t.class`
   b. `javap -p -c /tmp/t.class` → read the test bytecode
   c. Identify the precise expectation (status code, header, side-effect)
   d. Fix the corresponding Chappe implementation
   e. Re-run ONLY the targeted test class (`-Dtest=...`)
3. Once a coherent group passes → commit with the numeric delta
   ("TCK Servlet 6.1 — 96.6 % → 98.5 % (+12 tests, total 604/613)")
```

This loop is **entirely driven by Claude**. My involvement is limited to:
- saying which TCK package to tackle next (`servletcontext30`, `cookie`, …),
- arbitrating when the implementation diverges from the JSR (e.g. `Max-Age=0`
  on Cookie),
- validating commits.

---

## 4. Persistent memory

Claude maintains `~/.claude/projects/.../memory/MEMORY.md` which stores:

- **user**: my profile (Java 25 / CDI expert, rigorous about the spec).
- **feedback**: corrections applied once (e.g. "never mock the
  ServletContext, use real Chappe"), reused afterwards.
- **project**: why Chappe exists, why the TCK runners sit behind the `tck`
  Maven profile (a plain install must not download the TCK), architecture decisions.
- **reference**: path to the TCK JAR, launch script, etc.

Practical result: three days later I start a new session and Claude already
knows **where the TCK stands**, **which Vauban bugs are known** (cf.
`VAUBAN-BUGS.md`), and **which commit convention** to use.

---

## 5. External tools connected

| Tool | Role | Gain |
|---|---|---|
| **RTK** | CLI proxy that filters git/mvn output | -60 to -90 % output tokens |
| **context-mode MCP** | Sandbox + FTS5 index for large outputs | Enables full TCK run |
| **ctx_fetch_and_index** | Replaces WebFetch for the Servlet/Jersey spec | Targeted reading |
| **Subagents (Explore, Plan)** | Parallel code exploration | Main context preserved |

---

## 6. What *really* made the difference

1. **Letting Claude read the TCK bytecode.** It is the only reliable source of
   truth — Jakarta docs are incomplete, Tomcat diverges on details.
2. **Granular commits in English with numeric metrics.** Forces finishing a
   milestone before starting the next, avoids the "grand refactor" that breaks
   40 tests at once.
3. **Evolving Bash whitelist.** Each new permission is a conscious decision —
   no lazy `Bash(*)`.
4. **Systematic context-mode.** Without it, the TCK drowns the context in 2 runs.
5. **Active memory.** The Vauban bugs (`#1` to `#6`) identified by Claude were
   reported upstream with the full bytecode diagnosis.

---

## 7. What I do NOT do

- ❌ Ask Claude to "make the TCK pass" in one go. Always in batches of 5–15 tests.
- ❌ Use `--dangerously-skip-permissions`. Permission prompts are a signal:
  if Claude asks for a command I hadn't planned, there is a lead I need to
  understand.
- ❌ Let Claude write docs or READMEs spontaneously. Only on explicit request
  (like this file).
- ❌ Mix several milestones in one conversation. `/clear` between each major
  milestone — persistent memory takes over.

---

## 8. Measured outcome

- **63 commits** on `main`, all signed and dated.
- **0 → 99.2 % of the official Servlet 6.1 TCK** (608/613).
- **6 Vauban bugs** identified, documented with bytecode evidence, fixed
  upstream (cf. `VAUBAN-BUGS.md`).
- **~48 ms** startup for the servlet example with 3 servlets + filter +
  listener, on JDK 25 + Vauban CDI Lite.

---

*File written by Claude on request — reviewed and approved by Yann Blazart.*
