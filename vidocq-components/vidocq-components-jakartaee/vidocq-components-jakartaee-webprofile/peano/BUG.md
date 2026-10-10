# BUG.md — Peano

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## CI-001 · 2026-10-10 · Missing producer dependency graph

- Symptom: PR #2 passes the Maven install and all 18 unit tests, then fails the
  downstream impact step with HTTP 404.
- Minimal reproduction: request
  `https://codefloe.com/Vidocq/dep-graph/raw/branch/main/data/Vidocq_peano.json`.
  The global `graph/inverted.json` and `scripts/resolve_impact.py` both return 200.
- Cause: the new Peano repository has not published its producer metadata;
  the dependency update workflow has no prior run.
- Status: resolved. Workflow run 8 (`update-dep-graph.yml` on `main`) initialized
  the producer metadata. PR validation run 9 passes both governance and the
  complete build/downstream validation on commit `1d4065d`.
- Evidence: PR validation run 7, job `pr-validate`, fails after `BUILD SUCCESS`.

## EL-001 · 2026-10-10 · Asymmetric empty-string enum equality

- Symptom: comparing an empty string with an enum on the right throws ELException,
  while the reversed operands correctly return false.
- Minimal reproduction: `Comparison.equal("", Color.RED)` with any enum `Color`.
- Cause: empty-string-to-enum conversion returns null (EL 6.0 section 1.25.6);
  the equality branch invoked equals on that null result.
- Status: resolved during P1 implementation. Compare from the enum value;
  regression assertions cover both operand orders in ComparisonTest.
