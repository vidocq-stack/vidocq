---
description: Ingest a spec (url) and derive TASKS-XXX.md + STATUS-XXX.md
agent: lead
---
ARGS: $ARGUMENTS   (<url-to-spec> <XXX>, XXX = 3 uppercase letters)

TASKS-<XXX>.md exists? STOP. YOU NEVER READ THE SPEC — the scripts do, one
chapter per @noter, sequentially. Each step prints the next one.

1. ./scripts/steps/STEP010_fetch_spec.sh <url> <XXX>
   Fewer than 2 chapters or non-zero exit? STOP and report. Wrong TCK keyword
   guessed from the url? Add --tck-keyword <k>.
2. ./scripts/steps/STEP020_note_chapters.sh <XXX>
   Read its last line: notes: n/total written, skipped, timed out,
   reported-but-missing. Not complete? Run it again (it resumes). Twice without
   progress -> STOP.
3. ./scripts/steps/STEP030_detect_tck.sh <XXX>   exit 1 -> STEP040_install_tck.sh
4. ./scripts/steps/STEP050_module_path.sh <XXX>   then READ module.conf; fix the
   name if wrong.
5. Write docs/spec-src/<XXX>/milestones.tsv — the ONE judgement no script makes:
   <milestone-name><TAB><note globs>, one per line, in DEPENDENCY order.
   e.g.  entities<TAB>ch-04*.md ch-05*.md
6. ./scripts/steps/STEP060_plan_tasks.sh <XXX>
   Never hand-edit the output. REPORT its last two lines: assembled counts and
   the shape report. A group with 0 cards? Say which; fix is --force.
