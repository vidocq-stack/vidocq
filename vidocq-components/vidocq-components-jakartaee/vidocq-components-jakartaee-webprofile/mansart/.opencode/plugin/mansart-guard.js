// mansart-guard — keeps agent contexts small and build results honest.
//
// Every rule is measured on this repo (Vibe V3 logs, 10 sessions / 1 701 steps):
//   - 262 Maven runs logged exit_code 0 while 38 had failed  -> raw maven denied
//   - 218 find calls, the same one 75 times in 3 spellings   -> 3rd search denied
//   - 476 read_file calls, 40% re-reading an unchanged file  -> re-read denied
//   - 28% of primary context was raw bash output             -> long cat denied
//
// IMPORTANT: this file exports EXACTLY ONE symbol. OpenCode loads every export
// of a plugin file as a plugin; an export returning null kills the session with
// "plugin config hook failed". The rules live in ../guard-rules.js.
//
// The guard never hard-fails a session: any internal error lets the call through
// with a warning. Refusal works by throwing — OpenCode surfaces the message to
// the model, which then corrects itself (verified 2026-09-09).

import {
  generatedFileVerdict,
  isRawMaven, isTckWrite, isArchiveDump, longCatTarget,
  makeSessionState, searchVerdict, rereadVerdict,
} from "../guard-rules.mjs"

class Deny extends Error {}

export const MansartGuard = async ({ directory }) => {
  const sessions = new Map()
  const stateFor = (id) => {
    if (!sessions.has(id)) sessions.set(id, makeSessionState())
    return sessions.get(id)
  }
  console.error(`[mansart-guard] loaded (dir=${directory ?? "?"})`)

  return {
    "tool.execute.before": async (input, output) => {
      try {
        const st = stateFor(input.sessionID ?? "default")

        if (input.tool === "bash") {
          const cmd = output.args?.command
          if (typeof cmd !== "string" || !cmd) return

          if (isRawMaven(cmd))
            throw new Deny(
              "raw maven is denied. Use ./scripts/build.sh — a piped mvn returns the " +
                "pipe's exit code (always 0), which is how 38 failed builds got logged " +
                "green. build.sh keeps the full log on disk and returns Maven's real code.",
            )

          if (isTckWrite(cmd))
            throw new Deny("the TCK tree is read-only. Change the implementation, not the test suite.")

          if (isArchiveDump(cmd))
            throw new Deny(
              "archive/bytecode dumps belong in docs/spec-notes/, once. If the answer is " +
                "already noted, read the note; if not, write it there after this run.",
            )

          const long = longCatTarget(cmd)
          if (long)
            throw new Deny(
              `${long.path} is ${long.lines} lines. Use sed -n '<a>,<b>p' or grep -n instead — ` +
                "a full cat is 28% of what blew up the primary context.",
            )

          if (searchVerdict(cmd, st) === "deny")
            throw new Deny(
              "3rd identical search this session. Reuse the earlier result, or ask the parent " +
                "agent — in V3 the same find ran 75 times.",
            )
          return
        }

        if (input.tool === "write" || input.tool === "edit" || input.tool === "patch") {
          const path = output.args?.filePath ?? output.args?.path
          const why = generatedFileVerdict(path)
          if (why) throw new Deny(why)
        }

        if (input.tool === "read" || input.tool === "read_file") {
          const path = output.args?.filePath ?? output.args?.path
          if (typeof path === "string" && rereadVerdict(path, st) === "deny")
            throw new Deny(`${path} is unchanged since you read it — it is still in your context.`)
        }
      } catch (e) {
        if (e instanceof Deny) throw new Error(`mansart-guard: ${e.message}`)
        console.error(`[mansart-guard] non-fatal: ${e?.message ?? e}`) // fail open, by design
      }
    },
  }
}
