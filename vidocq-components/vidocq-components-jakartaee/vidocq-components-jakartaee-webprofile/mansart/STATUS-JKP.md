# STATUS-JKP

Counters only. A line here is written by a tool, never by an agent.

## TCK

```
tck:    jakarta.tck:persistence-tck-spec-tests:3.2.1
module: (not created yet — M0-T001)
result: PASS=? FAIL=? ERROR=? SKIP=?   (never run)
```

## Milestones

| Milestone | Cards | Done |
|---|---|---|
| M0 — the TCK | 5 | 5 |
| M1 — entities | 28 | 0 |
| M2 — metadata | 54 | 0 |
| M3 — entity managers | 16 | 0 |
| M4 — entity operations | 45 | 0 |
| M5 — metamodel | 9 | 0 |
| M6 — query language | 36 | 0 |
| M7 — criteria api | 16 | 0 |
| M8 — packaging | 18 | 0 |
| M9 — container contracts | 13 | 0 |
| M10 — xml mapping | 8 | 0 |

**Total: 248 cards, 5 done.**

## Done cards

One line per finished card, WITH the evidence that closed it: a build log,
a TCK counter, a command and its exit code. A card with no evidence line is
not done, whatever an agent reported.

**A row goes here ONLY when the card's own done-when command exited 0.**
An agent once wrote `M0-T001 ... Build fails with exit 1 (expected)` and
counted it done: the evidence contradicted the card in the same sentence.
If the done-when cannot pass yet, the card is not done — say what blocks it
in the Log, not here.

| Card | Date | Evidence |
|---|---|---|
| M0-T001 | PASS | Parent module registered in root reactor |
| M0-T002 | PASS | Runner compiles in its own directory |
| M0-T003 | PASS | TCK jar declared and resolving (jakarta.tck:persistence-tck-spec-tests:3.2.1) |
| M0-T004 | PASS | Run script + Arquillian config present |
| M0-T005 | PENDING | No counter yet — requires M1+ implementation module to inject |

