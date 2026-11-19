# TCK Challenge — Core Profile 11 composite tests fail on JDK 24+ (`Utils.getDescriptor()`)

> Ready-to-file Jakarta EE TCK challenge. Submit as a **new issue** on
> <https://github.com/eclipse-ee4j/jakartaee-platform/issues> with the label
> **`challenge`**. Everything below the line is the issue body.
>
> Per the Core Profile TCK appeals process (`doc/asciidoc/appeals-process.asciidoc`)
> and the [Jakarta EE TCK Process 1.4.2](https://jakarta.ee/committees/specification/tckprocess/):
> a test case, its configuration, beans, annotations and **other resources** are
> challengeable. This challenge targets a shared test **helper resource**, not a
> specification assertion.

---

## Challenge: `ee.jakarta.tck.core.common.Utils.getDescriptor()` is not portable to a spec-compliant JDK (24+)

**TCK:** Jakarta EE Core Profile TCK 11.0.0 (`jakarta-core-profile-tck-11.0.0`,
`core-profile-tck-impl`), SHA-256 `0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda`

### Affected tests (3)

| Test |
|------|
| `ee.jakarta.tck.core.json.ApplicationJsonpIT.testUseJsonWithCustomProvider` |
| `ee.jakarta.tck.core.json.ApplicationJsonpIT.testUseCustomProvider` |
| `ee.jakarta.tck.core.jsonb.JsonbApplicationIT.testUseCustomProvider` |

All three exercise a deployment-supplied custom `JsonProvider` / `JsonbProvider`
and, on invocation, call the shared helper `ee.jakarta.tck.core.common.Utils`,
which walks the stack and reads each frame's method descriptor.

### Root cause — a TCK helper, not the implementation under test

`Utils` builds a `StackWalker` **without** `Option.RETAIN_CLASS_REFERENCE` and then
calls `StackWalker.StackFrame.getDescriptor()`:

```java
// ee.jakarta.tck.core.common.Utils
public static void pushMethod() {
    StackWalker walker = StackWalker.getInstance();          // <-- no RETAIN_CLASS_REFERENCE
    Optional<String> methodName = walker.walk(frames -> frames
            .skip(1)
            .findFirst()
            .map(Utils::getMethodInfo));
    callStack.push(methodName.get());
}
private static String getMethodInfo(StackWalker.StackFrame frame) {
    return frame.getMethodName() + frame.getDescriptor();    // Utils.java:28 — throws on JDK 24+
}
```

`StackWalker.StackFrame.getDescriptor()` is **documented since JDK 10** to throw
`UnsupportedOperationException` when the walker was not configured with
`Option.RETAIN_CLASS_REFERENCE`
([Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/StackWalker.StackFrame.html#getDescriptor())).
JDK ≤ 21 implemented the method leniently (it derived the descriptor without
retaining class references, so it happened to work). JDK 24 restructured
`StackFrameInfo` so that `getDescriptor()` now goes through `getMethodType()`,
which enforces the documented requirement. The test therefore fails on any
spec-compliant JDK 24+.

### Failure

```
java.lang.UnsupportedOperationException: No access to RETAIN_CLASS_REFERENCE
    at java.base/java.lang.ClassFrameInfo.ensureRetainClassRefEnabled(ClassFrameInfo.java:132)
    at java.base/java.lang.StackFrameInfo.getMethodType(StackFrameInfo.java:79)
    at java.base/java.lang.StackFrameInfo.getDescriptor(StackFrameInfo.java:104)
    at ee.jakarta.tck.core.common.Utils.getMethodInfo(Utils.java:28)
    at java.base/java.util.Optional.map(Optional.java:260)
    at ee.jakarta.tck.core.common.Utils.lambda$pushMethod$0(Utils.java:24)
    ...
    at ee.jakarta.tck.core.json.ApplicationJsonpIT.testUseJsonWithCustomProvider(ApplicationJsonpIT.java:120)
```

### Minimal reproducer (independent of any Jakarta EE implementation)

```java
public class SW {
    public static void main(String[] a) {
        var w = StackWalker.getInstance();  // as Utils does, no RETAIN_CLASS_REFERENCE
        String r = w.walk(f -> f.findFirst().map(x -> {
            try { return "desc=" + x.getDescriptor(); }
            catch (Throwable t) { return "THREW: " + t; }
        }).orElse("none"));
        System.out.println(Runtime.version() + " -> " + r);
    }
}
```

```
$ java21  SW.java   →  21.0.7+6-LTS -> desc=([Ljava/lang/String;)V
$ java25  SW.java   →  25+36-LTS    -> THREW: java.lang.UnsupportedOperationException: No access to RETAIN_CLASS_REFERENCE
```

This is **not an OpenJDK bug** — JDK 25 conforms to the documented `getDescriptor()`
contract; JDK ≤ 21 was leniently non-conformant. The defect is in the TCK helper,
which relied on that leniency.

### Why this matters

The Core Profile is explicitly targeted at *smaller, modern runtimes*. An
implementation that is **JDK-25-native** (e.g. one built on `ScopedValue`
(JEP 487) and the Class-File API (JEP 484), which are not available before JDK 21/24)
cannot run these three tests on any JDK where they pass, because passing requires
a JDK whose `StackWalker` implementation violates its own documented contract.

### Proposed fix

`ee.jakarta.tck.core.common.Utils` should request the option the method requires:

```java
-        StackWalker walker = StackWalker.getInstance();
+        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
```

This is behaviour-preserving on JDK ≤ 21 and makes the three tests pass on JDK 24+.
Per the appeals process, we understand an accepted challenge results in an updated
`11.0.x` TCK carrying the corrected helper (no new tests added).

### Environment

- **Product:** Vidocq Runtime (JDK-25-native Jakarta EE Core Profile 11 implementation)
- **JDK:** Temurin 25 (`25+36-LTS`)
- **OS:** macOS / Linux
- **All other Core Profile constituents pass** on the same JDK 25: REST 4.0
  (2538), JSON-P 2.1, JSON-B 3.0, CDI 4.1 Lite (774), Dependency Injection 2.0
  (atinject), Annotations 3.0; the composite bundle is 10/13, the only 3 failures
  being the ones challenged here.
