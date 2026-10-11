# BUG.md — Peano

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## COMP-002 · 2026-10-11 · Serialized expressions accepted changed emitted bodies

- Symptom: a serialized compiled expression could restore its old source metadata
  against a regenerated class evaluating a different expression.
- Minimal reproduction: serialize demo.E2 compiled from ${42}; replace E2 with
  bytecode compiled from ${43}; restore the old stream in a fresh JVM.
- Cause: every emitted class used the same fixed serialVersionUID value.
- Status: resolved. Each UID derives from canonical parsed identity and an emission
  schema tag. Equivalent source forms remain compatible; changed ASTs are rejected
  by InvalidClassException. The initial fixed-UID prototype artifacts need regeneration.
- Evidence: the stale-artifact test failed before the fingerprint and now rejects
  changed bytecode, while a ${42} to #{ 42 } equivalent redeployment succeeds.

## COMP-001 · 2026-10-11 · Class-File limits leaked generic builder errors

- Symptom: compiling a valid oversized literal template threw IllegalArgumentException
  without identifying the expression class.
- Minimal reproduction: compile demo.Large from a 70,000-character literal string.
- Cause: the Class-File builder's UTF-8 constant limit escaped the compiler boundary.
- Status: resolved. Builder failures become ELException diagnostics naming the class,
  retaining the original cause. Invalid binary names retain IllegalArgumentException.
- Evidence: the oversized-template regression failed before the diagnostic wrapper
  and now passes.

## COMP-005 · 2026-10-11 · Integral equality unboxed custom null conversions

- Symptom: a custom converter returning null for the right Long operand raised an
  error instead of false; null on the left skipped the right conversion.
- Minimal reproduction: evaluate `${1==2}` with Long conversion returning null.
- Cause: emitted equality used primitive lcmp instead of the interpreter's Long.equals.
- Status: resolved by converting both operands before invoking Long.equals. Ordered
  comparisons retain primitive comparison and their existing unboxing order.
- Evidence: independent consumer checks right-null false and left-null error/call order.

## COMP-004 · 2026-10-11 · Interned text operands skipped equality conversions

- Symptom: emitted equality between distinct nonempty string literals skipped the
  context conversions performed by the interpreter.
- Minimal reproduction: evaluate `${'x'=='x'}` with a context recording String conversion.
- Cause: ldc interned both strings, incorrectly triggering the reference-identity shortcut.
- Status: resolved by initializing a distinct private static final String for each
  nonempty parsed literal. Each literal retains its stable identity across evaluations.
  Emission schema v2 rejects serialized artifacts from earlier emission rules; regenerate
  those application classes before redeployment.
- Evidence: independent consumer checks both String conversions and the no-conversion
  identity shortcut for cached integers; all generated classes pass verification.

## COMP-003 · 2026-10-11 · Discarded numeric overflow failed during compilation

- Symptom: compiling a lazy logical expression rejected an overflowing numeric literal
  even when evaluation would discard that operand.
- Minimal reproduction: compile and evaluate `${true or 9223372036854775808}`.
- Cause: compiler validation parsed every literal before emitted branch selection.
- Status: resolved by emitting numeric failures at their evaluation site. A discarded
  operand raises no error; a selected invalid literal raises ELException with a
  NumberFormatException cause, matching the interpreter.
- Evidence: independent named-consumer tests cover discarded positive and negative
  overflow plus selected overflow, and verify emitted class files.

## EL-016 · 2026-10-11 · Registered legacy property editors are not discovered

- Symptom: factory coercion of nonempty text to a custom target fails even after
  PropertyEditorManager registers a matching editor and java.desktop is resolved.
- Minimal reproduction: run the property-editor named-consumer fixture. Register
  ValueEditor for Value, then call factory.coerceToType("7", Value.class).
- Cause: Peano intentionally has no java.beans dependency or editor-registry lookup.
  EL 6.0 section 1.25.9 describes the editor path; section 1.25 permits custom converters.
- Status: resolved by the approved opt-in peano-beans-compat artifact. Installing it
  enables registered editors for factory and context conversion. Without it, discovery
  remains unavailable by design and explicit application converters remain supported.
- Evidence: CompatibilityModuleTest covers presence/absence, both reflection modes,
  registry changes, input failures, resolver precedence and fresh concurrent editors.
- Evidence: PropertyProcessorTest's editor integration scenario verifies the missing
  factory lookup and the working explicit converter, including empty/invalid input.

## APT-007 · 2026-10-10 · Unselected child interfaces caused adapter diagnostics

- Symptom: selecting a functional root caused an unrelated unselected child interface
  to fail the functional annotation requirement during generation.
- Minimal reproduction: selected @FunctionalInterface Source with get(); unselected
  Unselected extends Source adds another abstract method.
- Cause: class-family discovery also selected every source subinterface.
- Status: resolved. Implicit family traversal skips interfaces; explicit interface
  selection retains validation and unresolved-hierarchy deferral.
- Evidence: the closed-module callback fixture failed compilation before the fix
  and now compiles and runs with the unselected interface present.

## APT-005 · 2026-10-10 · Static hiding generated duplicate signatures

- Symptom: a generated child table rejected a hidden static method as ambiguous.
- Minimal reproduction: Bean and Derived both declare public static getStaticValue;
  evaluate Derived.getStaticValue through its generated table.
- Cause: the compiler member list included both declarations and generation retained both.
- Status: resolved. Signature deduplication preserves overriding or hiding declarations.
- Evidence: the closed-module fixture failed with MethodNotFoundException before the fix.

## APT-006 · 2026-10-10 · Later-generated parent lost inherited accessors

- Symptom: a selected child table omitted properties inherited from a generated parent.
- Minimal reproduction: selected Model extends Later; another processor emits Later
  with public getValue returning 7 after the initial round.
- Cause: the table was emitted while its superclass was an unresolved error type.
- Status: resolved. Unresolved hierarchies defer emission and are retried each round.
- Evidence: the named-module runtime test failed its inherited-property assertion
  before the fix and now reads 7 through the generated table.

## APT-004 · 2026-10-10 · Application names shadowed generated Java types

- Symptom: selected models named Class, Object or String prevented generated
  providers from compiling; wrapper names also broke annotation literal helpers.
- Minimal reproduction: select demo.Class in a named application module.
- Cause: generated source used unqualified java.lang names inside the model package.
- Status: resolved in both property and method generators. All emitted Java platform
  type/wrapper/annotation names are qualified.
- Evidence: a nine-name compilation regression was red and is green, including
  floating annotation values in a model named Float.

## EL-015 · 2026-10-10 · Mapped static functions bypassed generated accessors

- Symptom: a captured public static function on a generated closed model failed
  through the API reflective resolver despite a directly callable generated table.
- Minimal reproduction: map a public selected model's getStaticValue method and
  evaluate f:value() while the model package is not exported.
- Cause: captured functions invoked the API StaticFieldELResolver directly.
- Status: resolved. Captured owner/name/descriptor first dispatches through its
  registered table; unresolved functions retain the documented API fallback.
- Evidence: the named consumer failed before direct registered dispatch and now passes.

## APT-001 · 2026-10-10 · Module-qualified resource path rejected

- Symptom: provider source was generated but its registration resource failed
  with a FilerException during single-module javac compilation.
- Minimal reproduction: compile an annotated model with -d pointing to a plain
  class-output directory and a module-prefixed createResource package.
- Cause: CLASS_OUTPUT is not a multi-module output location in this compilation mode.
- Status: resolved. Sources retain explicit module ownership; fragments use the
  single compilation's unqualified class-output resource location.
- Evidence: positive processor integration cases were red with the FilerException
  and now compile and verify all registration fragments.

## APT-002 · 2026-10-10 · Boolean get-getter replaced is-getter

- Symptom: generated ready property called getReady instead of primitive-boolean
  isReady when both methods existed.
- Minimal reproduction: a bean whose isReady returns true and getReady returns false.
- Cause: equal primitive return types passed the subtype replacement condition.
- Status: resolved. An existing primitive-boolean is-getter keeps precedence.
- Evidence: the named consumer failed its boolean precedence assertion before the fix.

## APT-003 · 2026-10-10 · Explicit generated model diagnosed before later rounds

- Symptom: explicit selection of a model generated by another processor failed
  with Unknown accessor type before that processor's output reached the next round.
- Minimal reproduction: select demo.Later and run Peano before a second processor
  that generates demo.Later in its first round.
- Cause: configured names were resolved only once and immediately diagnosed.
- Status: resolved. Pending names are retried; genuinely missing types are diagnosed
  only when annotation processing completes.
- Evidence: the two-processor regression was red and now generates the provider.

## TCK-001 · 2026-10-10 · Official profile executed no tests

- Symptom: the official profile could report BUILD SUCCESS without executing the suite.
- Minimal reproduction: install the official artifact and run the old
  `./mvnw -Ptck,tck-official -pl peano-tck test` configuration.
- Cause: Surefire's default discovery excludes upstream ELClientIT/ELSigTestIT names.
- Status: resolved. Explicit *IT inclusion and failIfNoTests are configured;
  the script also rejects empty/unknown selections. No tests were excluded.
- Evidence: the pre-change run with failIfNoTests enabled was red with
  "No tests were executed"; the configured full suite executes 361 cases.

## TCK-002 · 2026-10-10 · Missing mapper and signature harness properties

- Symptom: mapper cases failed with null VariableMapper; signature testing had
  an empty test classpath and an invalid jimage extraction directory.
- Minimal reproduction: run the unconfigured official suite after enabling *IT discovery.
- Cause: upstream contexts instantiate the class named by variable.mapper, and
  signature tests require sigTestClasspath and jimage.dir.
- Status: resolved. Test-only StandardVariableMapper delegates to the API's actual
  mapper. Signature configuration supplies the API jar and extracted JDK classes.
- Evidence: mapper cases and ELSigTestIT pass in the full 361-test run.

## EL-009 · 2026-10-10 · Dynamic root writes queried current bean types

- Symptom: reassignment of a null bean name failed with a wrapped NPE; dynamic
  namespace writes could coerce to a type inferred from a previous value.
- Minimal reproduction: define bean a as null, then evaluate `a=1; a=2.5; a`.
- Cause: root writes queried BeanNameELResolver.getType before setValue, triggering
  API-003 and applying a property-type inference to dynamic namespace entries.
- Status: resolved. Root writes pass their original value to the resolver.
  Non-root property writes still use their declared writable type.
- Evidence: OfficialCompatibilityTest and all assignment/semicolon TCK cases pass.

## EL-010 · 2026-10-10 · Expression identity used raw source text

- Symptom: equivalent expressions differing only by whitespace, operator aliases
  or immediate/deferred delimiters compared unequal.
- Minimal reproduction: compare expressions `${A<B}` and `#{ A lt B }`.
- Cause: equals/hashCode used the original expression string.
- Status: resolved through normalized immutable syntax. Operand order, expected
  types and captured bindings remain significant; diagnostics retain original source.
- Evidence: regression cases and official valueExpressionEqualsTest pass.

## EL-011 · 2026-10-10 · Void literal method expression failed too early

- Symptom: creation rejected a literal MethodExpression with expected void return type.
- Minimal reproduction: create a method expression from "literal" with return
  type void and an empty parameter-type array.
- Cause: the void check ran during construction instead of invocation.
- Status: resolved. Creation and metadata work; invocation raises ELException,
  matching MethodExpression.invoke's API contract.
- Evidence: updated red regression and official negativeMethodExpressionTest pass.

## EL-012 · 2026-10-10 · Unspecified conversion target rejected

- Symptom: ExpressionFactory.coerceToType(null, null) threw NullPointerException.
- Minimal reproduction: call the method with a null target type.
- Cause: Coercions required a non-null target before applying conversion rules.
- Status: resolved. An unspecified target preserves the original value.
- Evidence: UnspecifiedConversionTest and official coerceToTypeTest pass.

## EL-013 · 2026-10-10 · Bare methods required an invocation-aware custom chain

- Symptom: a bare MethodExpression could report correct metadata but fail to invoke
  a public method when its custom resolver chain only resolved the target.
- Minimal reproduction: bind a Vector and invoke `#{vect.add}` using an empty invocation chain.
- Cause: invocation stopped at the unresolved chain instead of invoking the method
  found under section 1.6's bare-reference rules.
- Status: resolved through API BeanELResolver/StaticFieldELResolver fallback for bare
  references only. Custom resolver errors and claims keep precedence; inline calls
  retain the chain's invocation contract and module access remains enforced.
- Evidence: regression and official positiveMethodExpressionTest pass.

## EL-014 · 2026-10-10 · Lambda could not convert to Java Predicate

- Symptom: a Java method taking Predicate rejected an EL lambda with ELException.
- Minimal reproduction: request Predicate.class for `x->x>5` with ELProcessor.getValue.
- Cause: functional-interface coercion had no compiled adapter.
- Status: Predicate case resolved through a statically compiled private implementation.
  Arbitrary functional interfaces remain planned for build-time generation.
- Evidence: PredicateConversionTest, named-consumer check and the official lambda
  coercion case pass; no proxy or runtime bytecode generation is used.

## API-003 · 2026-10-10 · BeanNameELResolver null bean type query

- Symptom: getType on a resolved writable name holding null throws NullPointerException.
- Minimal reproduction: define bean a as null with ELProcessor and ask its context's
  resolver for getType(context, null, "a").
- Cause: jakarta.el 6.0.1 BeanNameELResolver calls getBean(name).getClass() without
  a null check.
- Status: open, upstream API limitation. Peano wraps direct metadata failures.
  Dynamic root writes avoid the unnecessary type query (EL-009).
- Evidence: BeanNameELResolver.java:162 in the official assignment/semicolon failures.

## EL-007 · 2026-10-10 · Evaluation boundary notifications missing

- Symptom: EvaluationListener received resolver property events but no expression
  before/after events.
- Minimal reproduction: register a listener on StandardELContext and evaluate
  a ValueExpression created from `${1+2}`.
- Cause: expression entry points did not call the Jakarta context notification hooks.
- Status: resolved during P5. Text value evaluation, method invocation and method
  references notify before evaluation and after success. Writes/metadata preserve
  reference behavior, and object variable bindings emit no synthetic boundary.
- Evidence: four EvaluationEventsTest scenarios were red before the implementation.

## EL-008 · 2026-10-10 · Parsed expressions could not be serialized

- Symptom: ObjectOutputStream rejected value/method expressions with
  NotSerializableException for Template, Ast.Call or java.lang.reflect.Method.
- Minimal reproduction: write a parsed `${1+2}` expression to ObjectOutputStream.
- Cause: default serialization traversed the parser tree and reflective function objects.
- Status: resolved during P5 through a source/binding serialization proxy. Public
  function descriptors preserve overloads, while the receiving context owns execution.
  Nonserializable captured user objects still fail with NotSerializableException.
- Evidence: four ExpressionSerializationTest scenarios failed before the proxy;
  named-consumer round trips also pass without adding exports or opens.

## EL-006 · 2026-10-10 · Null stream callback results unboxed incorrectly

- Symptom: a predicate returning null caused a wrapped NullPointerException instead
  of excluding the element; null limit/comparator values had the same unboxing issue.
- Minimal reproduction: evaluate `[1,2].stream().filter(x->null).toList()`.
- Cause: stream dispatch requested nullable wrapper conversions and then unboxed
  the returned null, instead of requesting primitive EL conversion defaults.
- Status: resolved during P4. Predicate, limit and comparator operands convert through
  ELContext to boolean, long and int respectively. Regression tests cover all three.
- Evidence: StreamOperationsTest was red with the null predicate and is green after
  the primitive conversion change.

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
