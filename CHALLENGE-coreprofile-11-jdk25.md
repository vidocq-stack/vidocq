# Draft TCK challenge — Jakarta EE Core Profile 11 TCK on JDK 25

> **Status: DRAFT, not filed.** Prepared 2026-08-27 from the Vidocq conformance runs; to be
> filed by Antoine Sabot-Durand against `jakartaee/jakartaee-tck` (Core Profile TCK component)
> following the appeals process shipped in the TCK bundle
> (`doc/asciidoc/appeals-process.asciidoc`). Copy everything below the line into the issue.

---

## Title

`ee.jakarta.tck.core.common.Utils.pushMethod()` throws `UnsupportedOperationException` on JDK 25 — `StackWalker.StackFrame.getDescriptor()` called without `RETAIN_CLASS_REFERENCE`

## TCK version

Jakarta EE Core Profile TCK **11.0.0** (`jakarta-core-profile-tck-11.0.0.zip`,
SHA-256 `0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda`), artifact
`jakarta.tck.coreprofile:core-profile-tck-impl:11.0.0`.

## Affected tests (3)

| Test | Result on JDK 25 |
|---|---|
| `ee.jakarta.tck.core.json.ApplicationJsonpIT#testUseCustomProvider` | `UnsupportedOperationException: No access to RETAIN_CLASS_REFERENCE` |
| `ee.jakarta.tck.core.json.ApplicationJsonpIT#testUseJsonWithCustomProvider` | same |
| `ee.jakarta.tck.core.jsonb.JsonbApplicationIT#testUseCustomProvider` | same |

The remaining 10 composite tests pass; all constituent specification TCKs pass on the same
runtime (CDI 4.1 Lite 774/774, Dependency Injection 2.0, Annotations 3.0 signature, JSON-P 2.1
179/179, JSON-B 3.0 290/295 — 5 upstream-disabled, RESTful Web Services 4.0 2538/2538 in the
Core Profile / SE-Bootstrap configuration).

## Description

The three tests deploy a custom JSON-P / JSON-B provider whose methods call the TCK helper
`ee.jakarta.tck.core.common.Utils.pushMethod()` to record the calling method on a stack the
test later inspects (`Utils.popStack()`). The helper is:

```java
public static void pushMethod() {
    StackWalker walker = StackWalker.getInstance();            // no options
    Optional<String> methodName = walker.walk(frames -> frames
            .skip(1)
            .findFirst()
            .map(Utils::getMethodInfo));
    callStack.push(methodName.get());
}

private static String getMethodInfo(StackWalker.StackFrame frame) {
    return frame.getMethodName() + frame.getDescriptor();     // <-- throws on JDK 25
}
```

`StackWalker.StackFrame.getDescriptor()` is specified (since Java 10, JDK-8195107) as:

> *@throws UnsupportedOperationException if this StackWalker is not configured with
> `Option.RETAIN_CLASS_REFERENCE`.*

The helper creates its walker **without** that option, so the call is out of contract on every
JDK. JDK 17 and 21 happened to return the descriptor anyway (the `StackFrameInfo`
implementation did not enforce the check), which is why the tests pass on the JDKs the
compatible implementations used for ratification (WildFly Preview 34, Open Liberty
24.0.0.11-beta, both on JDK 17/21). JDK 25 enforces the documented contract
(`java.lang.StackFrameInfo.getDescriptor` → `ensureRetainClassRefEnabled()`), so the helper
now fails before the implementation under test is even exercised.

Core Profile 11 requires "Java SE 17 **or higher**"; an implementation targeting a current JDK
cannot pass these three tests regardless of its conformance. The failure is in the TCK
helper, not in the behaviour being certified: the same three tests pass when the helper is
patched (see below) and the surrounding assertions (`Utils.popStack()` returning the expected
`methodName(descriptor)` string) hold.

## Minimal reproducer (no container needed)

```java
public class Repro {
    static String info() {
        return StackWalker.getInstance()
                .walk(f -> f.skip(1).findFirst().map(x -> x.getMethodName() + x.getDescriptor()))
                .orElseThrow();
    }
    public static void main(String[] a) { System.out.println(info()); }
}
```

```
$ java -version   # 21.0.x
$ java Repro.java
main([Ljava/lang/String;)V

$ java -version   # 25
$ java Repro.java
Exception in thread "main" java.lang.UnsupportedOperationException: No access to RETAIN_CLASS_REFERENCE
        at java.base/java.lang.StackFrameInfo.getDescriptor(StackFrameInfo.java:104)
```

## Proposed resolution

Create the walker with the option the API requires (one-line change in
`core/common/Utils.java`):

```java
StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
```

This is behaviour-preserving on JDK 17/21 (the descriptor string is identical) and makes the
helper conform to the `StackWalker` contract. Alternatively — if retaining class references is
undesirable — record `frame.getMethodName()` plus `frame.getMethodType()` is *also* gated by
the same option, so the option is the only correct fix short of dropping the descriptor from
the recorded string (which would change the expected values in the tests).

## Requested outcome

Until a TCK service release ships the fix, accept exclusion of the three tests above for
implementations running on JDK ≥ 25 (per the appeals process: "a test is challenged when it
is an invalid test, e.g. depends on an implementation-specific or JDK-specific behaviour not
required by the specification").

## Environment of the report

- Implementation: Vidocq runtime 0.3.0 (Vauban CDI 4.1 Lite, Cassini REST 4.0, Champollion
  JSON-P 2.1 / JSON-B 3.0, Chappe HTTP), JDK-25-native (`--release 25`, cannot run on 17/21).
- JDK: Eclipse Temurin 25 (25.0.3+…), macOS 15 / Linux — reproduced on both.
- Runner: `vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile`
  (Arquillian embedded container booting the assembled runtime), full log available on request.
