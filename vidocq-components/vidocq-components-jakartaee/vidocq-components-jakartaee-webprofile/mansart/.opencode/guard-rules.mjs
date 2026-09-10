// mansart-guard rules — pure functions, unit-testable.
// NOT under .opencode/plugin/: OpenCode treats EVERY export of a plugin file as a
// plugin, calls it with {directory}, and dies on the first one returning null
// (`plugin config hook failed: null is not an object`). Rules live here, the
// plugin file exports exactly one thing.
//
// Every rule here exists because it was measured on this repo (Vibe V3 logs,
// 10 sessions / 1 701 steps), not because it sounded prudent:
//
//   - 262 Maven runs logged exit_code 0 while 38 had failed  -> raw maven denied
//   - 218 find calls, the same one 75 times in 3 spellings   -> 3rd search denied
//   - 476 read_file calls, 40% re-reading an unchanged file  -> re-read denied
//   - 28% of primary context was raw bash output             -> long cat denied
//
// Contract: the guard NEVER hard-fails a session. Any internal error lets the
// call through with a warning — the path denylist stays the hard guarantee.
// Refusal works by throwing: OpenCode surfaces the message to the model, which
// then corrects itself (verified 2026-09-09).

import { statSync, readFileSync } from "node:fs"

const STRIP_PREFIX = [/^\s*cd\s+[^&|;]+&&\s*/, /^\s*rtk\s+/]
const STRIP_SUFFIX = [/\s*2>\s*\/dev\/null\s*$/, /\s*2>&1\s*$/, /\s*>\s*\/dev\/null\s*2>&1\s*$/]

/** Reduce a command to a comparable form. Without this, the 75 duplicate
 *  searches of V3 look like 3 different commands. */
export function normalise(cmd) {
  let c = String(cmd ?? "").trim()
  for (let i = 0; i < 4; i++) {
    const before = c
    for (const re of STRIP_PREFIX) c = c.replace(re, "")
    for (const re of STRIP_SUFFIX) c = c.replace(re, "")
    c = c.trim()
    if (c === before) break
  }
  return c.replace(/\s+/g, " ")
}

/** Maven outside scripts/build.sh — piped or not. */
export function isRawMaven(cmd) {
  const c = normalise(cmd)
  if (/scripts\/(build|sonar)\.sh/.test(c)) return false
  return /(^|[|;&]\s*)(\.\/)?mvnw?\b/.test(c)
}

const TCK_PATH = /[\w./-]*[\w-]+-tck\//

/** A shell write into a TCK tree. The path denylist on write/edit cannot see
 *  a redirect, so this is the only layer that can express it. */
export function isTckWrite(cmd) {
  const c = normalise(cmd)
  if (!TCK_PATH.test(c)) return false
  if (/>>?\s*[^|;&]*[\w-]+-tck\//.test(c)) return true // > or >> into tck
  if (/\btee\b[^|;&]*[\w-]+-tck\//.test(c)) return true
  if (/\b(cp|mv|rsync|install)\b[^|;&]*[\w-]+-tck\//.test(c)) return true
  if (/\bsed\b[^|;&]*-i[^|;&]*[\w-]+-tck\//.test(c)) return true
  return false
}

/** Archive/bytecode dumps: fine once, then it belongs in a spec note. */
export function isArchiveDump(cmd) {
  const c = normalise(cmd)
  if (/\bjar\s+[a-z]*t[a-z]*f?\b/.test(c)) return true
  if (/\bunzip\s+-l\b/.test(c)) return true
  if (/\bjavap\b/.test(c)) return true
  return false
}

const MAX_CAT_LINES = 200

/** Whole-file read of a file longer than 200 lines -> {path, lines}, else null.
 *
 *  Matches `cat X` AND `read X` — because `rtk rewrite` turns `cat BUG.md` into
 *  `rtk read BUG.md`, normalise() strips the `rtk`, and a cat-only pattern would
 *  silently stop firing. Measured: `rtk read` does not truncate (346 lines in,
 *  346 out), so the rule is still needed after the rewrite. */
export function longCatTarget(cmd) {
  const c = normalise(cmd)
  const m = c.match(/^(?:cat|read)\s+((?:-{1,2}[A-Za-z-]+(?:\s+\S+)?\s+)*)([^\s|;&<>-][^\s|;&<>]*)/)
  if (!m) return null
  const path = m[2]
  try {
    const st = statSync(path)
    if (!st.isFile()) return null
    const lines = readFileSync(path, "utf8").split("\n").length
    return lines > MAX_CAT_LINES ? { path, lines } : null
  } catch {
    return null // missing/unreadable: not our business
  }
}

export function makeSessionState() {
  return { searches: new Map(), reads: new Map() }
}

const SEARCH_CMD = /^(find|grep|rg|ag|fd|ls\s+-R)\b/

/** 3rd identical search in a session -> "deny". Two are tolerated: a repeat is
 *  often legitimate, a third means the result was never kept. */
export function searchVerdict(cmd, state) {
  const c = normalise(cmd)
  if (!SEARCH_CMD.test(c)) return "allow"
  const n = (state.searches.get(c) ?? 0) + 1
  state.searches.set(c, n)
  return n >= 3 ? "deny" : "allow"
}

/** Re-read of a file unchanged since the last read (same mtime+size). */
export function rereadVerdict(path, state) {
  let key
  try {
    const st = statSync(path)
    if (!st.isFile()) return "allow"
    key = `${st.mtimeMs}:${st.size}`
  } catch {
    return "allow"
  }
  const seen = state.reads.get(path)
  state.reads.set(path, key)
  return seen === key ? "deny" : "allow"
}



/**
 * Files a script owns. Writing to one is always a mistake, never a shortcut.
 *
 * WHY: asked to build the M0 runner, the lead needed somewhere to write its
 * detailed work order — and the only file it knew about was TASKS-JKP.md. It
 * overwrote a 248-card plan with a single-card brief. Nothing forbade it, and
 * nothing had ever offered it a better place to write.
 *
 * TASKS-XXX.md is assembled by scripts/spec-tasks.sh from planner fragments; an
 * edit to it is erased by the next regeneration, silently. Per-card notes belong
 * in tasks/XXX/<CARD>.md, which is the agent's own space.
 */
export function generatedFileVerdict(path) {
  if (typeof path !== "string") return null
  const base = path.split("/").pop() ?? ""
  if (/^TASKS-[A-Z]{3}\.md$/.test(base))
    return (
      `${base} is GENERATED by scripts/spec-tasks.sh — an edit here is erased by the next ` +
      "regeneration, and an agent already destroyed a 248-card plan this way. " +
      "Write the card's work order to tasks/<XXX>/<CARD>.md instead."
    )
  if (path.includes("/.fragments/"))
    return `${path} is planner output, rewritten by scripts/spec-tasks.sh. Do not edit it.`
  return null
}


/**
 * `grep "-suite.xml"` — the pattern starts with a dash, so grep reads it as
 * options and dies with `invalid option -- t`. The agent ran the identical
 * command twice and got the identical usage dump twice: two failures, zero
 * information, and a wasted round trip each time.
 *
 * A single-dash token holding a character no short option uses (a dot, a slash,
 * a star, an equals) is a pattern, not a flag. Returns the offending token.
 */
export function grepDashPattern(cmd) {
  const c = normalise(cmd)
  if (!/\b(grep|egrep|fgrep|rg)\b/.test(c)) return null
  const tokens = c.match(/(?:"[^"]*"|'[^']*'|\S)+/g) ?? []
  for (let i = 0; i < tokens.length; i++) {
    const prev = (tokens[i - 1] ?? "").replace(/^["']|["']$/g, "")
    if (/^(-e|-f|--regexp|--file)$/.test(prev)) continue // the pattern is declared, fine
    const raw = tokens[i].replace(/^["']|["']$/g, "")
    if (raw === "--") return null // everything after -- is a pattern, on purpose
    if (!raw.startsWith("-") || raw.startsWith("--") || raw === "-") continue
    if (/[.\/*=\[\]]/.test(raw.slice(1))) return raw
  }
  return null
}


/**
 * A new spec gets a NEW parent module and one line in the root pom. Every module
 * that existed before is delivered, TCK-passing, and frozen for the harness.
 *
 * WHY: a /tck run "fixed" a junit version in mansart-jakarta-data/pom.xml and
 * mansart-transactions/mansart-transactions-tests/pom.xml on its way to wiring
 * the persistence runner — two modules at 74/74 and 5/5 that nobody asked it
 * to touch. Nothing forbade it, because "stay in your module" was written down
 * and enforced nowhere.
 *
 * `frozen` is computed by the plugin at load: the root pom's <modules> minus
 * every parent named in docs/spec-src/XXX/module.conf. Pure function otherwise.
 */
export function frozenModuleVerdict(path, frozen) {
  if (typeof path !== "string" || !frozen || frozen.length === 0) return null
  const rel = path.replace(/^\.\//, "")
  for (const m of frozen) {
    if (rel === m || rel.startsWith(m + "/") || rel.includes("/" + m + "/"))
      return (
        `${m} is a delivered module (in the root reactor, not this spec's). It is frozen: ` +
        "a run once changed a junit version in two delivered, TCK-passing modules while " +
        "wiring a new runner. Your work lives under the parent named in module.conf."
      )
  }
  return null
}


/**
 * OPENCODE_*.md are the humans' trace of this project — design reasoning,
 * field notes, ~2 000 lines between them. They are not documentation for an
 * agent: everything an agent must know is in AGENTS.md, once. Opening one
 * pours a thousand lines of history into a context that is re-sent every step.
 */
export function traceDocVerdict(path) {
  if (typeof path !== "string") return null
  const base = path.split("/").pop() ?? ""
  if (/^OPENCODE_[A-Z0-9_-]+\.md$/i.test(base))
    return (
      `${base} is the humans' trace of this project, not documentation for you. ` +
      "Every rule you need is in AGENTS.md. Nothing in this file changes what to do next."
    )
  return null
}
