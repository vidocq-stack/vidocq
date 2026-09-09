# OpenCode + JPA — the story of the second attempt

Field notes from rebuilding a local agent harness on `ybl/jpa-opencode2`,
6–9 September 2026. Raw material for an article; everything here is measured on
one machine (M5 Max, 128 GB, oMLX 0.6.2 then 0.6.4) and reproducible from the
repo.

There is a thread running through all of it, and it is worth stating up front:

> **Almost every failure in this story was silent.** Not a crash, not a stack
> trace — a green build that had failed, a guard that stopped guarding, a rule
> that quietly stopped matching, an agent reporting eleven files it never wrote.
> The work was not making things happen. It was noticing they had not.

---

## 1. Where this starts

Three attempts at Jakarta Persistence 3.2 preceded this one.

| Attempt | Harness | Outcome |
| --- | --- | --- |
| 1 | local, hand-driven | drifted into stubs and reflection, **2/1745 TCK** |
| 2 | OpenCode + oMLX (`ybl/jpa-opencode`) | reached M0–M4, never wired the real TCK |
| 3 | Mistral Vibe, cloud GLM 5.2 (`ybl/jpa-vibe`) | 24 of 112 cards, **$43.98**, and a set of measurements worth more than the code |

Attempt 3 left the number that shaped everything after it. Over 10 sessions and
1 701 steps, the primary agent's context came from:

| Source | Share |
| --- | --- |
| `read_file` | 42% |
| `bash` (build output, searches) | 28% |
| `write_file` + `edit` | 21% |
| `task` returns — actual delegation | **4.6%** |

**72% of the expensive agent's context was work it could have handed off.** A
subagent's context is discarded when it returns; the primary's is re-sent on
every single step. That asymmetry is the whole design problem.

Three pathologies came with it, all measured:

- **218 `find` calls, 124 distinct.** The identical `find … EntityModel.java`
  ran **75 times**, spread across three spellings that differed only by a
  stderr redirect.
- **476 `read_file` calls, 40% of them re-reading a file nothing had changed.**
- **262 Maven runs, every one logged `exit_code: 0`** — while 38 of those
  outputs contained `BUILD FAILURE`, 3 compile errors and 17 test failures.

That last line is the first silent failure, and the most expensive: `mvn … |
tail -5` returns *tail's* exit code, which is always 0. For 262 runs, the
harness believed everything was fine.

---

## 2. Choosing the models, and what it took to measure them

Seven local models were on the machine. The question looked simple — which one
drives the harness — and took three days to answer honestly.

### The trap that invalidated a whole campaign

Halfway through, a control run exposed something ugly. The **same configuration**
measured at the start and the end of a one-hour series gave:

| | Decode | Prefill @60k |
| --- | --- | --- |
| start | 128.5 tok/s | **2 406 tok/s** |
| after ~1 h | 72.9 tok/s | **944 tok/s** |

**−61%.** And it recovered fully after **3 minutes idle** (2 340 tok/s), so it
was thermal, not a leak. Which meant an entire sequential A/B of config flags —
baseline → KV 8-bit → KV 4-bit → ANE prefill → baseline — was worthless: the
numbers decayed monotonically **in chronological order**, not by flag. I had
already reported those flags as harmful. They are not. They are *untested*.

The protocol that works: unload the model and idle 150 s **before every single
measurement**. Proof it works — the reference model measured at the opening and
the closing of a 58-minute series landed within **1%** (120.4 / 119.1 tok/s
decode, 2 515 / 2 507 prefill).

Cost of this lesson: two full benchmark campaigns thrown away.

### The result

| Model | GB | Decode | Prefill 60k | Prefill 120k | Code task (10 assertions) |
| --- | --- | --- | --- | --- | --- |
| **Qwen3.6-35B-A3B-MTPLX** | 22.0 | **120** | **2 507** | 655–830 | 10/10 — 40 s, 4 700 tok |
| Qwen3-Next-80B-Instruct | 47.1 | 86 | 1 730 | 910 | 10/10 — **4.5 s, 348 tok** |
| Qwen3-Next-80B-Thinking | 47.1 | 83 | 1 799 | **1 128** | 7/10 direct — 98 s, 8 044 tok |
| Qwen3-Coder-Next-mxfp4 | 44.5 | 85 | 1 657 | 741 | 7/10 direct — 9 s |
| gemma-4-26b-a4b | 16.1 | 44 | 1 027 | 746 | prose inside the .java file |
| Laguna-XS-2.1 | 22.6 | 35 | 894 | 608 | correct after repair — 439 s, 15 435 tok |
| gemma-4-12b-coder | 7.0 | 59 | 857 | **419** | missed the needle at 120k, no tool call |

Quality was never judged by reading the code. Each model wrote a
`FlushOrderResolver` (topological sort + cycle detection), which was compiled
with `javac 25` and run against 10 assertions. Opinions do not survive that.

**There is a crossover around 100k.** The 35B dominates up to 60k — ×1.4 prefill,
×1.4 decode, half the RAM. Past 100k the 80Bs overtake it. Since the working
range here is ~53k, the 35B wins where it matters.

### The split: who pays what

The roster does not follow "big model for hard things". It follows cost shape:

- the **primary** re-sends a long context every step → it pays **prefill**;
- a **subagent** starts fresh and short → it pays **wall-clock per task**.

The 35B prefills 1.45× faster; the 80B Instruct finishes the same task **9×
faster** because it burns no reasoning. Both are 10/10. So: 35B leads, 80B works.
And they **cohabit** — 69 GB of weights under an ~84 GB ceiling, 85% of system
memory still free with a 60k context prefilled on each. No swapping, and the
prefix caches survive.

### Two dead ends, stated plainly

**The MTPLX side-car is broken at the source.** The model is named
"MTPLX-Optimized-Speed" and ships `mtp.safetensors` (582 MB), but it had never
been imported. Importing it works (2 321 tensors), then loading fails:

```
Expected shape (256, 512, 32) but received shape (256, 512, 64)
for language_model.mtp.layers.0.mlp.switch_mlp.gate_proj.scales
```

The manifest explains it: body from `mlx-community/…-4bit` (group_size 64),
side-car from `cyankiwi/…-AWQ-4bit`. Two incompatible quantisations shipped in
one repo. The advertised accelerator cannot run. Index restored.

**A Thinking model cannot be turned into an Instruct.** Both chat templates are
identical except the generation prompt — Thinking ends `…assistant\n<think>\n` —
and there is no `enable_thinking` switch to flip. Removing the primer does
nothing: **the model writes `<think>` itself**. Priming a closed empty block does
suppress it (505–780 tokens in 7–14 s instead of 8 044 in 98 s, a ×14 speedup),
but quality collapses **reproducibly**: 6/10 on three separate runs, always
inverting the topological order — the core of the task. Out of distribution. The
real Instruct is both better and faster.

---

## 3. Building the plumbing, and four silent failures

### build.sh — fixing the 262 green builds

One script becomes the only path to Maven: it pins the JDK from `.sdkmanrc`,
sends the full log to a file, prints ~2 lines, and **returns Maven's real exit
code**. A 66-line log becomes 2 lines of context — which also attacks the 28%
`bash` share from §1. Its contract test was written first and is now 10/10.

It caught a second silent failure immediately: a harness run compiled with
**Java 26** while the repo pins **25.0.3-tem**. An agent shell never runs
`sdk env`. A wrong compiler that still succeeds is worse than a failure, so a
missing pinned JDK now exits **78** (EX_CONFIG) — a machine problem, not a build
problem — and every verdict line ends with `jdk: <version>`.

### The guard, and why a permission list cannot do this

`tools.bash.denylist` matches command prefixes *after* a tree-sitter parse has
split on pipes. `mvn … | tail` becomes two harmless parts. **No permission rule
can express "mvn must not be piped"** — only a hook that sees the raw string.
OpenCode exposes `tool.execute.before`, so the guard lives there: raw Maven, TCK
writes through a shell, archive dumps, `cat` of a >200-line file, third identical
search, re-read of an unchanged file. 44/44 on its contract.

Refusal works by throwing, and the result was better than expected — the message
reaches the model, which **corrects itself**:

```
$ mvn -v
Error: mansart-guard: raw maven is denied. Use ./scripts/build.sh …
$ ./scripts/build.sh -v            ← the model's own next move
OK (exit 0) jdk: 25.0.3-tem
```

By the end, the model refused `mvn` *before* the guard fired, citing AGENTS.md.
Written rules steer; the guard catches.

### Three ways the guard silently stopped guarding

**A plugin file must export exactly one symbol.** OpenCode loads *every* export
of a plugin file as a plugin, calls it with `{directory}`, and dies on the first
one returning `null` — `plugin config hook failed: null is not an object`, whole
session lost. My `longCatTarget` returns `null` when the command is not a `cat`.
Rules moved to `.opencode/guard-rules.mjs`, outside `plugin/`.

**rtk rewrites the command before the guard sees it.** `cat BUG.md` becomes
`rtk read BUG.md`. After `normalise()` strips the `rtk`, a `cat`-only pattern
matches nothing — the rule had stopped firing, with no error anywhere. And
`rtk read` does **not** truncate (346 lines in, 346 out), so the rule was still
needed. Every rule must be written against the *rewritten* form.

**`.mjs` plugins are not discovered.** Renaming the plugin to `.mjs` (to dodge a
`package.json` that OpenCode auto-ignores) left the guard **completely inactive**
— `opencode agent list` still looked fine, runs still worked, nothing complained.
Caught only by grepping for the load line: `plugin loaded: 0 times`. Back to
`.js`; rules stay `.mjs`.

Three different ways to end up with a guard that guards nothing, none of which
announces itself.

### sonar.sh

SonarQube was there all along — container `vidocq-sonar`, port **9001** — just
stopped with OrbStack. The script starts it, waits for `status: UP`, and scans.
Two findings: the scanner **rejects `-pl`** ("Maven session does not declare a
top level project"), so scope with `-Dsonar.inclusions=<module>/**`; and the
plugin is invoked **by coordinates**, so no POM is touched and the build stays
clean for anyone not running Sonar. Without a token it skips with exit 0 and says
so, rather than breaking the card loop.

---

## 4. The roster

| Agent | Model | Writes | Why |
| --- | --- | --- | --- |
| `lead` (primary) | Qwen3.6 | TASKS / STATUS / notes | Decides, never types. Cheapest prefill. |
| `recon` | 80B Instruct | **nothing** | Answers one question in 3 lines |
| `noter` | 80B Instruct | `docs/spec-notes/` | Reads one chapter, writes one note |
| `tdd` | 80B Instruct | `src/test/` | The failing test, first |
| `impl` | 80B Instruct | `src/main/` | Makes it pass, never touches tests |
| `verify` | 80B Instruct | **nothing** | Runs things, reports numbers |
| `thinker` | Qwen3.6 | **nothing** | Only after two failures |
| `tck-runner` | 80B Instruct | **nothing** | Runs the official TCK, reports the counter |

Enforcement is layered rather than repeated: `recon`, `verify` and `thinker` have
`tools: {write, edit, patch: false}` — they *cannot* write, it is not a request.
`verify` exists because in attempt 3, **24 cards were marked DONE while the real
conformance counter said 2**. A card is done when a tool says so.

Commands: `/spec-add`, `/tck`, `/next`, `/fixbug`, `/status`, `/push`. `/next` does one
card end to end then **stops**, and commits but never pushes — pushing publishes,
and an unattended command should not publish.

---

## 5. First real run: ingesting the JPA 3.2 spec

The spec is 2.5 MB of HTML, 1.22 M characters. It does not fit any context.

Design: a deterministic script fetches it, converts it, and splits it on headings
(20 chapters at first, **39** once splitting was added — see §6); one `noter` per
chapter writes a ≤200-line note; the lead then derives milestones **from the notes
only**. It never reads the spec.

A useful accident: the command was given the **PDF** URL, but no PDF converter
existed on the machine and oMLX exposes no markitdown endpoint — my design had
assumed one without checking. Jakarta publishes the same spec as HTML, so the
script now prefers it: stdlib only, and headings survive for the split.

**First run produced zero notes.** The agents reported eleven successes. The
directory was empty. My fault, squarely: `/spec-add` told `recon` to write the
notes, and I had defined `recon` with `write: false`. A contradiction between a
command and an agent, reported as success. Hence `noter`.

Second run, three more silent-failure lessons:

- **Truncated filenames.** My slug cuts at 40 characters
  (`…persistence-contex.md`), the lead reconstructed plausible names that did not
  exist, and got a wall of `ENOENT`. Names must be copied from `ls`, never
  rebuilt.
- **The lead reported notes that were not there** — again. But the instruction to
  `ls` and count after the calls worked: it noticed ch-09 was missing and
  **relaunched it on its own**.
- 12 `noter` calls in parallel serialise on `max_concurrent_requests: 2`.

Result:

| | |
| --- | --- |
| chapters | 20 (11 substantive, front matter and appendices skipped) |
| notes | 11 |
| `TASKS-JKP.md` | **10 milestones, 58 cards**, 161 lines |
| `STATUS-JKP.md` | counters at zero |

Cards are traceable and measurable — each carries its spec section and a
done-when that a test can check:

```
| M1-T003 | Primary key declaration: @Id, @EmbeddedId, @IdClass, UUID, composite
| ch-04 [2.4], [2.4.1], [2.4.2] | Primary key required; key class has no-arg
  constructor, equals/hashCode; dependent entity identity enforced |
```

And the point of the whole pipeline held: **the lead never read a line of the
spec.** Eleven notes, 367 lines, were all it saw of 1.22 M characters.

---

## 6. The TCK, and three more silent failures

A reviewer's question exposed the biggest hole in the generated plan: **the word
"TCK" appeared zero times in 58 cards.** A plan that says what to implement but
not how anyone would know it works. That is exactly how attempt 1 reached 2/1745
and attempt 3 marked 24 cards DONE against a real counter of 2.

The fix had to live in the harness, not in a hand-written module — otherwise the
next spec starts from nothing:

- **`scripts/tck-find.py <keyword>`** scans the local M2 for TCK artifacts and the
  repo for runners to copy. Generic: `persistence` → `jakarta.tck:persistence-tck:3.2.1`,
  `data` → `jakarta.data:jakarta-data-tck:1.0.1`, unknown keyword → exit 1 with a
  clear message. It also finds the two runners that already work here
  (`mansart-data-tck` at 74/74, `mansart-transactions-tck`), so an agent copies a
  working layout instead of inventing one.
- **`spec-fetch.py`** now writes `spec-meta.json`: chapters plus TCK coordinates,
  keyword derived from the url. This file is what makes the harness reusable —
  `/tck` reads it instead of guessing.
- **`/tck <XXX>` + agent `tck-runner`**: runs, fixes nothing, cannot write,
  reports four lines. Written into its prompt: *ZERO PASS IS A VALID RESULT*, and
  `ERROR=all` (the harness does not compile) is not the same information as a
  conformance failure.
- **M0 is now always the TCK**, before any implementation milestone. `M0-T005`'s
  done-when is "the TCK produces a counter, any counter".

On dependencies, the answer was smaller than expected: **JNDI is not a Jakarta
spec** — `javax.naming` is in the JDK. The TCK's `jakarta.tck:common` pulls the
whole EE platform (`ejb`, `jms`, `servlet`, `el`…) but that is the shared base of
every Jakarta TCK, not what JPA needs at runtime. What JPA actually requires —
Transactions, CDI, Annotations, JDBC, a connection pool — already exists in this
ecosystem as `mansart-transactions`, `vauban` and `mansart-pool`.

Re-running the pipeline produced three more silent failures, all worth the price:

**A note written one directory too high.** `docs/spec-notes/ch-05.md` instead of
`docs/spec-notes/JKP/ch-05.md`. The agent reported success, the file existed, and
the count stayed at 8 of 11 for twenty minutes. Fix: the noter no longer *receives*
an output path, it **derives** it from the chapter path. You cannot mistype a path
you are never handed.

**A worker that forgot what it was for.** Given an 80 KB chapter, the noter read
it and replied: *"What would you like me to do with this specification?"* — 20k
tokens of spec had pushed its system prompt out of reach. Two fixes: `spec-fetch`
now splits any chapter over 45 KB on paragraph boundaries (39 files instead of 20,
largest 48 KB instead of 160), and the task is **repeated in the message**, not
left to the system prompt alone.

**A report that named the wrong source file** while writing the right one. Harmless
here, but it is the same family: what an agent says about its work is not evidence.
Only `ls` is.

Three failures, three different mechanisms, zero error messages between them.


### The worst silent failure: a run that looks alive

The third `/spec-add` attempt hung. Not crashed — **hung**. The process was there,
`pgrep` found it, oMLX reported one request "active". It stayed that way for
**51 minutes without writing a single file**: last log line and last note both at
16:13, killed at 17:04. Fourteen notes of thirty-nine, no `TASKS-JKP.md`.

Every earlier failure at least left a trace: an error, a missing file, a wrong
number. This one imitated work perfectly. And my own monitoring was complicit —
I was watching whether the *process existed*, not whether it *progressed*. A run
that writes nothing for fifty minutes is dead, whatever `ps` says.

The cause was upstream, and it was mine: the lead dispatched all 39 noter calls
at once at a server with `max_concurrent_requests: 2`. Nine active, seven queued,
prompt processing down from 1 730 to **170 tok/s**, and in that crush one call
never came back.

**The fix removes the lead from the loop entirely.** Dispatching N mechanical
calls is not agent work — a shell does it better:

```
scripts/spec-note.sh <XXX>
  - one @noter at a time, strictly sequential
  - a hard `timeout` per call: a hung call dies in 600 s instead of forever
  - front matter filtered by pattern: 39 chapters -> 24, at zero token cost
  - already-written notes skipped, so it resumes where it stopped
  - final line: notes: n/total written, n skipped, n timed out,
    n REPORTED-BUT-MISSING
```

That last counter exists because of this whole story: an agent saying it wrote a
file is not evidence. The script `ls` the file afterwards and counts the lie
separately from the failure.

**Parallelism bought nothing here.** With two concurrent slots server-side, firing
39 requests adds latency, hides progress, and turns one lost call into a silent
hour. Sequential is not the slow option — measured at ~47 s per note, 24 chapters
land in about 20 minutes, against 90 minutes of nothing. The lesson generalises
past this project: **do not parallelise AI calls against a server that serialises
them.** You gain no throughput and lose observability.

Also worth recording: upgrading oMLX (0.6.2 -> 0.6.4) **wiped the prefix cache** —
42 GB down to 12 MB. Any cache measurement taken across an upgrade is comparing
two different machines.


### The preparatory work is the work

It is tempting to read §3 and §6 as detours before the real thing. They are not.
Nothing here was written by inspiration: every rule exists because a measurement
contradicted an assumption, and the harness is the accumulated residue of those
contradictions. The cost is visible — three days before a single card was
implemented — and so is the alternative: attempt 1 started coding immediately and
finished at 2/1745.

A concrete example of the price, from the last run. Splitting chapters at 45 KB
fixed a worker that had forgotten its instructions, but turned 20 chapters into
39 — and the lead dispatched all 39 at once against a server configured for
`max_concurrent_requests: 2`. The observed rate: **9 active, 7 queued, 170 tok/s
of prompt processing** where the same model measures 1 730 tok/s alone. The fix
that made the worker reliable made the pipeline twice as slow, then the
unthrottled fan-out made it slower still.

The cumulative stats show something sharper. The lead, replaying a near-identical
context, reaches **17 250 tok/s** of apparent prefill on 6 125 requests — almost
all of it cache hits. The noter, reading 39 different chapters, gets **274 tok/s**
on 342 requests, with no cache at all. The role that benefits least from the
prefix cache was handed to the model that prefills slowest. That is a design
mistake the roster table in §4 does not reveal, and it only became visible under
load.

Open question, honestly unanswered: the two models scored **10/10 each** on a
code task, but note-writing was never measured. Whether the 80B extracts
requirements better than the 35B is testable — same chapter, both models, compare
— and until that runs, the roster rests on an assumption.

---

## 7. Deriving the plan — where the lead broke for good

The notes landed well. `scripts/spec-note.sh` walked 24 chapters sequentially in
**35 minutes**: 24 notes, **1 463 lines**, zero `REPORTED-BUT-MISSING`. Against
367 lines for 11 chapters on the previous pass, splitting at 45 KB did what it
promised. The notes are genuinely implementable — chapter 5 reduces the Metamodel
API to 15 lines that still carry the exact declarations
(`public static volatile SingularAttribute<X, Y> y;`) and the name-mangling rule.

One instructive false alarm: the script reported `23/24 written, 1 timed out`,
yet 24 complete notes were on disk. The watchdog had killed `ch-11` at 600 s
**after** it finished writing — its last line matches the chapter's last line
exactly. The work was done; the agent hung on its closing report. Worth knowing
before trusting a timeout counter: it counts calls, not outcomes. The resume
logic has the mirror risk — it skips any non-empty note, so a *partial* file left
by a kill would be silently accepted as done.

### Three attempts, three exit codes of zero

Then the lead had to turn 24 notes into `TASKS-JKP.md`, and could not.

**Attempt 1.** It read 12 notes and stopped. Exit 0. No error, no final message,
no file. I suspected the context first and was wrong: measured with the server's
own `count_tokens`, all 24 notes are **34 913 tokens** against a declared limit of
65 536, and it died at 21 000. The real ceiling was `output: 12288` — a reasoning
model that burns 4 300 tokens of thought on a trivial task, asked to emit a
hundred-card document in one response. Truncation mid-generation surfaced as an
empty turn, which OpenCode reports as success.

**Attempt 2**, with the cap raised to 32 768 and instructions to write the file in
five increments. It got further and died the same way — but it left evidence
worth more than the file: M0 came out as five cards reading *"resolve the
`persistence-tck` artifact"*, *"resolve the `persistence-tck-dist` artifact"*, one
per line of a JSON blob. Asked for a plan, it paraphrased its input. And
`STATUS-JKP.md` contained `jakarta.tack:persistence-tck` — a typo inside Maven
coordinates, the kind that fails hours later for no visible reason.

### The fix: the script owns everything a model gets wrong

`scripts/spec-tasks.sh` applies the `spec-note.sh` lesson one level up. The model
never sees the document:

```
scripts/spec-tasks.sh <XXX>
  - one @planner call per milestone group, sequential, watchdog per call
  - the planner emits ROWS ONLY: | title | spec sections | done-when |
  - no id column — the script assigns M3-T007, so no invented duplicates
  - rows that do not match the shape are DELETED, not trusted: prose is dropped
  - M0 is generated from spec-meta.json with NO MODEL AT ALL
  - milestone order comes from docs/spec-src/<XXX>/milestones.tsv, not a model
  - the script counts what it cannot judge: cards bundling 4+ requirements,
    and duplicate titles across groups
```

M0 is the important line. The coordinates are now copied by `python3`, not
retyped by a language model, so `jakarta.tack` cannot happen again. Ordering
milestones is a judgement, so it lives in a ten-line file a human can read and
edit; fanning calls out is mechanical, so it lives in the shell.

A dry run at `--timeout 1` — ten deliberate failures, no tokens spent — caught a
bug worth the whole practice: the loop processed **one** group and stopped.
`opencode` was reading the TSV from stdin and swallowing it. Fixed with
`</dev/null`. Without that cheap rehearsal I would have shipped a file with one
milestone filled and nine empty, and blamed the model.

### The negative result: neither prompt nor model fixes card shape

The pipeline then worked perfectly and produced cards that were wrong:

```
| The Entity Class | 2.1 | annotated with @Entity, is top-level or static inner
  class, has a no-arg constructor, is non-final, all methods non-final |
```

A section heading wearing a card costume — five behaviours in one done-when, so
no agent can ever finish it and nothing can ever be marked done. That is the
mechanism by which attempt 3 marked 24 cards DONE against a counter of 2.

I hardened the planner prompt: verb-first titles, exactly one assertion per
done-when, the failed example quoted verbatim with its correction, and the cap on
row count removed so splitting would win over tidiness.

| | before | after |
| --- | --- | --- |
| cards | 309 | 248 |
| duplicate titles | 3+ | **0** |
| done-when bundling 4+ requirements | not measured | 39 |

Duplicates vanished. **The shape did not move at all.** And the giveaway:
**seven of ten groups returned exactly the same card count as before** — metadata
54, entity-managers 16, metamodel 9, query-language 36, criteria 16, packaging 18,
xml 8. A substantially rewritten prompt changed nothing about how the work was
carved up.

So I ran the control: the same chapter through the **35B reasoning model**. It
produced **12 cards where the 80B produced 54** — and the identical defect,
`| Entity (name) |`, `| Callback Annotations |`, `| NamedEntityGraph |`. Four
times less coverage, same flaw.

Two models, two architectures, a hardened prompt, low temperature, three runs of
the same shape. The conclusion is not that the models disobey:

> **Generating cards from section-structured notes is a structure-preserving
> transformation.** The notes are organised by spec section, so the cards are too.
> This is not a prompting problem, and no fourth wording will fix it.

It is consistent with what does work. The `noter` succeeds because its task is
line-by-line: one requirement in, one line out. Ask the same model to *group and
arbitrate* and it mirrors its input instead. That boundary — transform versus
decide — is the most useful thing this session measured, and it was only visible
because the failure was measurable rather than aesthetic.

The decision is to keep the 248 cards as a **coverage map** and stop polishing.
What decides this project is the TCK counter, not the elegance of a markdown
table — and M0, the only part needed to start, is the part no model touched.

### A monitoring mistake, mine

Twice I reported a run as finished when it was not. I had wrapped the script in
both `nohup` and the harness's own background mode, so the completion
notification described my wrapper — a `sleep 90` — rather than the script. Exactly
the error this document opens with: watching the wrong thing and believing it.

---

## 8. The first `/tck` run, and a card that could not be done

An interactive OpenCode session took M0. It built the runner module properly:
standalone POM out of the reactor, Arquillian `ApplicationArchiveProcessor`,
`LoadableExtension` registered as a service, run script — the layout copied from
`mansart-data-tck`, which is what `spec-meta.json` pointed it at. Verified, not
taken on trust: `./scripts/build.sh test-compile` really returns 0 on JDK 25.

Then it reported: *"Removed the missing Maven Central dependency
(jakarta.tck:persistence-tck)"*. M0-T002's done-when is "the TCK jar resolves
from the local M2". **It made the build green by deleting the requirement** —
the precise move this harness exists to prevent.

Except the card was impossible as written, and that was my bug. `tck-find.py`
recommended the artifact with the most plausible *name*:

```
jakarta.tck:persistence-tck:3.2.1        .pom + cyclonedx, NO JAR
jakarta.tck:persistence-tck-dist:3.2.1   8 KB jar,     0 test classes
jakarta.tck:persistence-tck-common:3.2.1 156 KB jar,   0 test classes
jakarta.tck:persistence-tck-spec-tests   2.4 MB jar, 161 test classes  <-- the suite
```

The recommended coordinate was a POM-only aggregator. The agent chased something
that cannot resolve and "fixed" it the only way left to it. A bad card first, a
bad reflex second — and the bad card came from a script that trusted a name.

**The fix reads the jar instead of the name.** `tck-find.py` now opens each
candidate and counts test classes, and reports the evidence with the
recommendation: `(161 test classes in the jar)`. A name cannot lie about content
that has been counted.

That surfaced a second trap immediately. Ranking by test count alone picked
`jakarta.persistence:persistence-tck-spec-tests:4.0.0-SNAPSHOT` — **321 classes,
and the TCK of the next spec version**. A 3.2 implementation would have been
measured against 4.0. So the spec version now pins the TCK version:
`spec-fetch.py` extracts it from the URL and passes `--spec-version 3.2`, and a
release beats a SNAPSHOT at equal relevance.

Also stated plainly: the reported *"TCK execution runs successfully"* was **one
test, one skipped**. Zero official tests ran. That is not `PASS=0` — it is no
counter at all, and the two must never be confused.

The M2 was cleaned back to the state before the run (stale
`io.vidocq.mansart:mansart-persistence-*` artifacts from the August attempts,
which were silently satisfying a dependency whose sources are not in this branch;
backed up to a tarball first). The official Jakarta TCK jars were **kept** — they
are not public, they were not installed by this run, and deleting them would have
been the one irreversible act available.

M0-T002 now reads: *"`./scripts/build.sh dependency:resolve` lists the jar.
Removing the dependency to make the build green is not a fix — an agent did
exactly that."* The failure is written into the card it broke.

### Purging the TCK, and what the purge exposed

Keeping the TCK jars was the wrong call, and the objection was sharp: a run that
starts with the suite already installed by hand proves nothing about a harness
meant to work on any spec. So all 41 MB of Jakarta Persistence TCK artifacts were
removed from the M2 (backed up outside it — a tarball is not on any classpath).

Two defects surfaced within minutes, both invisible while the jars were there.

**My own lookup failed silently.** Asked for a version it could not satisfy,
`tck-find.py` printed the artifact list, said nothing about the failure, and
**exited 0**. The tool written to make a missing metric loud had the exact bug it
was built against. It now prints `NO RUNNABLE TCK: <reason>` and exits 1.

**A version filter that falls back is not a filter.** `--spec-version` was a
preference: no 3.2 match, take whatever exists. That is how a 3.2 implementation
gets measured against a 4.0 TCK and reports a real-looking counter. It is now a
hard constraint — `3.2` matches `3.2`, `3.2.1`, `3.2.2-SNAPSHOT`, never `4.0` and
never `3.20` — and returns nothing rather than something wrong. **The TCK version
comes from the spec, always**: `spec-fetch.py` reads it out of the URL, so
`TitiToto 4.3` looks for the TitiToto 4.3 TCK and refuses a 5.0 one.

And the distinction the purge forced into the design: **"no TCK installed" is not
"no TCK exists"**. The gap between those two is where a project quietly invents a
substitute metric. With an empty M2, M0 is no longer a shrug — its first card is:

```
M0-T001  Obtain the official TCK for persistence 3.2 and install it into
         the local M2
done-when: scripts/tck-find.py persistence --spec-version 3.2 exits 0 and
           reports a jar with >0 test classes
```

A done-when that is a script's exit code. Nobody can argue with it — including me.

### Can the local model just work it out?

Purging the TCK left a hole: M0-T001 became "install the TCK" and no command
could do it. My first instinct was to write the Eclipse URL into a script. The
objection was sharper than the proposal — *the user should not have to know
that; tomorrow this harness runs Bean Validation.*

Checking three spec pages settled it immediately. The distribution names share
**no pattern at all**:

```
persistence 3.2       jakarta-persistence-tck-3.2.1.zip
bean-validation 3.1   validation-tck-dist-3.1.1.zip
data 1.0              data-tck-1.0.0.zip
```

Any convention inferred from one fails on the other two. A hardcoded pattern
would have been a bug with a two-spec fuse on it.

But the answer was not "let the model figure it out" either. **The spec page
links its own TCK** — and the page url was already sitting in `spec-meta.json`,
one directory up from the document we ingested. And every jar inside the
archive carries its exact Maven coordinates in
`META-INF/maven/<groupId>/<artifactId>/pom.properties`. So the whole chain is
deterministic once you stop guessing and start reading:

```
spec-meta.json url -> landing page -> href containing "tck" ending .zip
                   -> published .sha256 verifies the download
                   -> each jar's pom.properties gives g:a:v
                   -> mvn install:install-file
                   -> tck-find.py counts test classes    <- the verdict
```

Verified end to end from the emptied M2: 4.3 MB, sha256 verified, 3/3 jars
installed, **161 test classes confirmed**, `spec-meta.json` refreshed, M0
regenerated back to "build the runner".

So where does the local model belong? Exactly one place, and the script says it
out loud: **exit 5, no archive linked on the page.** That is open-ended — a
moved download, a spec hosted elsewhere — and it is what WebFetch is for. Even
there the agent reports a url and stops; it never fabricates a coordinate.

### "Does that hold for five specs?"

It did not, and the question was the whole value. Discovery held 9/9 — but the
*install* step rested on an assumption I had verified on exactly one archive:
that every jar carries its coordinates. Tested on four more:

| spec | jars | installable | the rest |
| --- | --- | --- | --- |
| data 1.0 | 1 | 1 | — |
| cdi 4.1 | 4 | 4 | — |
| bean-validation 3.1 | 42 | 6 | 28 third-party, 8 with no metadata |
| transactions 2.0 | 13 | **0** | not a Maven TCK at all |

Bean Validation ships slf4j, jQuery and AssertJ inside its TCK archive — a jar
with a `pom.properties` is not therefore *ours*, and installing all 34 would
have pushed other projects' artifacts into the M2. Transactions is worse and
more interesting: its only jar carrying coordinates is `jaxen:jaxen:1.1.6`, an
XPath library. My installer would have installed jaxen, called it a day, and
missed the TCK entirely — because Transactions 2.0 is a **JavaTest/TSharness
distribution**, `lib/jtatck.jar` plus an Ant harness, a TCK that is *run* rather
than depended upon. A whole family of specs my design had not imagined.

The design held where it mattered: the verdict is `tck-find.py`, so even the
broken version would have failed loudly rather than reporting a fake success.
But "fails loudly" is not "works". Now the script installs only jars that belong
to the spec, names the third-party ones it deliberately left alone, and when
nothing is Maven-consumable it says so and exits 7, pointing at
`mansart-transactions-tck` — the runner of that family this repo already has.

Two verifications, two different outcomes: the one I ran (one spec) confirmed my
design, the one I was pushed to run (five specs) refuted it. The generalisable
part is not the fix. It is that **a harness claimed to work "for any spec" has
to be tried on specs you did not design it against**, and that the person asking
"does that hold for five?" is doing the most valuable work in the room.

The rule that falls out of it is worth more than the script: **trust a model to
search, never to conclude.** This session already paid for the second half — an
agent reported *"TCK execution runs successfully"* over one skipped test, and
another "resolved" a missing dependency by deleting it. Discovery is a judgement
under uncertainty, which is what models are for. A verdict is a count, which is
what scripts are for. The done-when of the install card is a script's exit code
precisely so the search above it can be delegated safely.

### The file I edited by hand, and the command that should have existed

Refreshing those coordinates exposed a hole I had walked straight through.
`spec-meta.json` is generated by `spec-fetch.py`, but that script re-downloads
and re-splits the whole spec — far too much for a file whose only stale field
was the TCK. So I had written a throwaway snippet to patch the one key.

Defensible, and still wrong: **a file that is sometimes generated and sometimes
typed is a file nobody can trust.** The TCK moves — installed, upgraded, purged —
while the spec text never does, so the missing piece was a command, not a
shortcut. `spec-fetch.py <XXX> --refresh-tck` redoes the lookup alone, prints
`before:` / `after:`, and exits 1 when nothing runnable matches the spec version.
The lookup itself is now one function shared by both paths, so a refreshed file
and a freshly ingested one are byte-identical.

Its first run earned its place: a one-line diff against what I had committed —
the trailing newline my hand-edit had added. Trivial in itself, and exactly the
point. The command's first use was to date my own lapse.

Two smaller things fell out of it. `--tck-keyword` was swallowing its own value,
which was then counted as a positional argument, so the command printed usage
instead of running — **the identical bug I had just fixed in `tck-find.py`**, sitting
in the neighbouring script. And the refreshed file was silently losing the spec
version, because `tck-find` returns what it *found* while the spec says what was
*being looked for*; both are now kept, or a refresh quietly drops the constraint
that stops a 3.2 implementation being measured against a 4.0 TCK.

---

## 9. What is true today

Verified, not assumed:

- `scripts/build.sh` — 10/10 contract, real exit codes, JDK pinned
- `scripts/sonar.sh` — real scan against SonarQube 26.5.0, dashboard produced
- `.opencode/guard-rules.mjs` + plugin — 44/44, refusal verified end to end
- `scripts/tck-find.py` — generic TCK lookup, verified on two different specs
- eight agents loaded, six commands, `/status` and `/spec-add` run for real
- global OpenCode config repaired (it declared two models deleted from oMLX)

- `scripts/spec-note.sh` — 24 notes, 1 463 lines, 35 min, 0 reported-but-missing
- `scripts/spec-tasks.sh` — 11 milestones, 248 cards, 10/10 groups, M0 generated
  without a model

`TASKS-JKP.md` exists again, and its worth is uneven — **M0 is exact**
(coordinates copied by script, five cards, a done-when a tool can check), the 248
cards below it are a coverage map with the right spec sections and the wrong
granularity. Said plainly rather than dressed up: the plan is good enough to
start M0 and not good enough to be called a plan.

## 10. What is not solved

- **Card granularity is unsolved, and not by prompting.** 248 cards shaped like
  spec sections, 39 of them bundling four or more requirements. Two models and a
  hardened prompt failed to change it. The remaining ideas are a deterministic
  splitter (one card per note line — 1 463 cards, a worse cure) or letting
  `/next` split a card at execution time, when an agent has a real test in front
  of it. Untried.
- **A timeout counter counts calls, not outcomes.** `ch-11` was killed at 600 s
  after writing a complete note. Worse in the other direction: the resume logic
  accepts any non-empty file, so a partial note left by a kill would be skipped
  as done. Quarantining a timed-out note as `.partial` is a four-line fix, not
  yet written.
- **`impl` is kept out of `src/test/` by instruction, not by tooling.**
  `permission.edit` is per-agent, not per-path, and the guard cannot see which
  agent issued a call. If an `impl` ever edits a test to go green, that is the
  hole.
- **KV quantisation and ANE prefill remain untested**, not disproven.
- **`/next` has never run.** Everything above is scaffolding for a loop that has
  not yet delivered a single card.

The measure of this harness is not that it exists. It is whether the TCK counter
moves — and that number is still zero.
