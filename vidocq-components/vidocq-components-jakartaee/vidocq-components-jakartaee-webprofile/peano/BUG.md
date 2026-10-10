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

## EL-003 · 2026-10-10 · Property writes omitted actual-type coercion

- Symptom: setting a String value into an Integer[] element failed rather than
  converting the value to Integer; a typed custom property received the raw value.
- Minimal reproduction: create a ValueExpression for an Integer[] element with
  expected type String, then setValue(context, "42").
- Cause: writes delegated the raw value without using the actual writable type
  required by EL 6.0 section 1.2.1.1.
- Status: resolved during P3. Query the terminal resolver type, convert through
  ELContext and then setValue. AssignmentExpressionTest covers object arrays and
  a typed custom property; API-001 remains independent.

## EL-004 · 2026-10-10 · Literal method aliases skipped inherited return checks

- Symptom: a MethodExpression variable holding a Long-valued literal was invoked
  through an alias expecting String without rejecting the signature mismatch.
- Minimal reproduction: bind createMethodExpression(context, "42", Long.class, [])
  as a variable, then invoke an alias expecting String.
- Cause: the literal invocation branch ignored return-type constraints accumulated
  through MethodExpression-valued variables.
- Status: resolved during P3; literal aliases validate inherited return constraints.
  MethodExpressionTest contains the regression assertion.

## EL-005 · 2026-10-10 · Primitive/wrapper overload metadata lost exact descriptors

- Symptom: metadata for overloads boxed(int) and boxed(Integer) selected the same
  descriptor regardless of the caller's explicit expected parameter type.
- Minimal reproduction: request each overload from the independent PublicRecord fixture
  through getMethodInfo/getMethodReference and invoke it.
- Cause: matching boxed parameter types counted as exact, and identical boxed types
  were treated as strict specificity in both directions.
- Status: resolved during P3 after a red module fixture. Exact declared types take
  precedence over boxing; equal boxed types cannot dominate each other.

## API-002 · 2026-10-10 · Inaccessible method fallback produces NullPointerException

- Symptom: BeanELResolver.invoke on a public method in a non-exported package
  throws NullPointerException instead of MethodNotFoundException.
- Minimal reproduction: invoke HiddenBean.greet in the independent bootstrap fixture
  through a fresh StandardELContext without its application resolver.
- Cause: ELUtil.findMethod returns null for an inaccessible method, then
  ELUtil.invokeMethod dereferences that null at ELUtil.java:213.
- Status: open, jakarta.el 6.0.1 API limitation. Peano wraps the NPE in ELException
  with the original cause. Invocation remains blocked; custom resolver invocation works.
  The fixture accepts the current wrapped NPE or a future corrected MethodNotFoundException.
