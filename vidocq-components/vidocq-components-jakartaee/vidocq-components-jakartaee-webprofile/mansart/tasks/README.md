# tasks/ — per-card work orders

`TASKS-XXX.md` is **generated**: it holds one row per card, and a script owns it.
This directory is where an agent writes everything that does not fit in a row —
the detailed brief for one card, what it read, what it decided, what it tried.

    tasks/<XXX>/<CARD>.md        e.g. tasks/JKP/M0-T001.md

One file per card. Free form. Nothing here is generated, parsed, or overwritten.

## Why it exists

Asked to wire the M0 runner, the lead needed somewhere to write a detailed work
order — and the only file it knew about was `TASKS-JKP.md`. It overwrote a
248-card plan with a one-card brief. The plan was regenerable, so nothing was
lost, but the lesson is not about the loss: **an agent with something to write
and no place to put it will write over whatever is nearest.** Forbidding the
write is half the fix; the other half is offering a legitimate destination.

The guard now refuses writes to `TASKS-*.md` and points here.
