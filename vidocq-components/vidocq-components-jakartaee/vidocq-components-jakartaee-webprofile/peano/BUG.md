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

## API-001 · 2026-10-10 · Primitive array writes rejected by the API resolver

- Symptom: writing an Integer value into an int[] through jakarta.el 6.0.1's
  ArrayELResolver throws ClassCastException, although the element is compatible.
- Minimal reproduction: call `new ArrayELResolver().setValue(context, new int[1], 0, 42)`
  with a non-null ELContext. Peano's ValueExpression delegates the same operation.
- Cause: ArrayELResolver checks `componentType.isAssignableFrom(value.getClass())`
  before Array.set; primitive `int.class` is not assignable from `Integer.class`.
- Status: open, upstream API limitation. Peano reports ELException with the
  ClassCastException cause. Object-array writes and primitive-array
  reads work. No fallback bypasses user resolvers or swallows their exceptions.
- Evidence: ArrayELResolver.java:219 in the 6.0.1 API stack, and the setValue bytecode
  checks the primitive component type before invoking Array.set.

## EL-002 · 2026-10-10 · Resolver runtime failure leaked outside the EL contract

- Symptom: a custom resolver throwing IllegalStateException leaked that exception
  directly from ValueExpression instead of an ELException with a cause.
- Minimal reproduction: evaluate a property with an ELResolver whose getValue
  throws IllegalStateException; repeat with setValue.
- Cause: engine resolver calls did not wrap non-EL runtime exceptions.
- Status: resolved during P2; guarded resolver calls preserve ELException subclasses
  and wrap other runtime failures. Regression tests verify read/write causes.
