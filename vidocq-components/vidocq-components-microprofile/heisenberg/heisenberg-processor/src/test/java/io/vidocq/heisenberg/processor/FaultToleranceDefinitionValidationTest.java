/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.heisenberg.processor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MicroProfile Fault Tolerance 4.1 definition errors (the {@code FaultToleranceDefinitionException} cases of the
 * specification) are compile errors of the Heisenberg processor: an invalid definition no longer waits for the
 * container to boot (vidocq-workspace#40, item 6). One test per rule, each on the smallest class that breaks it.
 */
class FaultToleranceDefinitionValidationTest {

    @TempDir
    Path tempDir;

    /** The compiler errors of {@code body}, a class {@code t.Bean}, or an empty list when it compiles. */
    private List<String> errorsOf(String imports, String body) throws Exception {
        return errorsOfSources(List.of("t/Bean.java"), List.of("package t;\n" + imports
                + "\nimport org.eclipse.microprofile.faulttolerance.*;\n" + body));
    }

    private List<String> errorsOfSources(List<String> names, List<String> contents) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        File out = Files.createDirectories(tempDir.resolve("classes")).toFile();
        File gen = Files.createDirectories(tempDir.resolve("generated")).toFile();
        List<File> sources = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Path file = tempDir.resolve("src").resolve(names.get(i));
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents.get(i));
            sources.add(file.toFile());
        }
        List<File> classPath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) classPath.add(new File(entry));
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out));
            files.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(gen));
            files.setLocation(StandardLocation.CLASS_PATH, classPath);
            compiler.getTask(null, files, diagnostics, List.of("--release", "25", "-proc:full"), null,
                    files.getJavaFileObjectsFromFiles(sources)).call();
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ROOT))
                .toList();
    }

    private void assertRejected(String imports, String body, String expectedFragment) throws Exception {
        List<String> errors = errorsOf(imports, body);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expectedFragment)),
                () -> "expected an error containing '" + expectedFragment + "', got " + errors);
    }

    private void assertAccepted(String imports, String body) throws Exception {
        assertEquals(List.of(), errorsOf(imports, body));
    }

    // -- @Asynchronous, spec section 4 -------------------------------------------------------------------------

    @Test
    void asynchronousNeedsAFutureOrACompletionStage() throws Exception {
        assertRejected("", "public class Bean { @Asynchronous public String call() { return null; } }",
                "@Asynchronous requires Future or CompletionStage return type");
    }

    @Test
    void asynchronousAcceptsFutureAndCompletionStage() throws Exception {
        assertAccepted("import java.util.concurrent.*;", """
                public class Bean {
                    @Asynchronous public Future<String> a() { return null; }
                    @Asynchronous public CompletionStage<String> b() { return null; }
                    @Asynchronous public CompletableFuture<String> c() { return null; }
                }""");
    }

    @Test
    void asynchronousOnTheClassChecksEveryMethod() throws Exception {
        assertRejected("", "@Asynchronous public class Bean { public void call() { } }",
                "@Asynchronous requires Future or CompletionStage return type");
    }

    // -- @Bulkhead, spec section 6 ------------------------------------------------------------------------------

    @Test
    void bulkheadValueMustBeAtLeastOne() throws Exception {
        assertRejected("", "public class Bean { @Bulkhead(0) public void call() { } }", "@Bulkhead value must be >= 1");
    }

    @Test
    void bulkheadQueueMustNotBeNegativeWithAsynchronous() throws Exception {
        assertRejected("import java.util.concurrent.*;", """
                public class Bean {
                    @Asynchronous @Bulkhead(value = 2, waitingTaskQueue = -1)
                    public Future<String> call() { return null; }
                }""", "@Bulkhead waitingTaskQueue must be >= 0 for @Asynchronous");
    }

    @Test
    void bulkheadQueueIsNotCheckedWithoutAsynchronous() throws Exception {
        assertAccepted("", "public class Bean { @Bulkhead(value = 2, waitingTaskQueue = -1) public void call() { } }");
    }

    // -- @CircuitBreaker, spec section 5 -------------------------------------------------------------------------

    @Test
    void circuitBreakerFailureRatioMustBeWithinZeroAndOne() throws Exception {
        assertRejected("", "public class Bean { @CircuitBreaker(failureRatio = 1.5) public void call() { } }",
                "@CircuitBreaker failureRatio must be within [0,1]");
        assertRejected("", "public class Bean { @CircuitBreaker(failureRatio = -0.1) public void call() { } }",
                "@CircuitBreaker failureRatio must be within [0,1]");
    }

    @Test
    void circuitBreakerRequestVolumeThresholdMustBeAtLeastOne() throws Exception {
        assertRejected("", "public class Bean { @CircuitBreaker(requestVolumeThreshold = 0) public void call() { } }",
                "@CircuitBreaker requestVolumeThreshold must be >= 1");
    }

    @Test
    void circuitBreakerSuccessThresholdMustBeAtLeastOne() throws Exception {
        assertRejected("", "public class Bean { @CircuitBreaker(successThreshold = 0) public void call() { } }",
                "@CircuitBreaker successThreshold must be >= 1");
    }

    // -- @Retry, spec section 3 ----------------------------------------------------------------------------------

    @Test
    void retryMaxRetriesMustBeAtLeastMinusOne() throws Exception {
        assertRejected("", "public class Bean { @Retry(maxRetries = -2) public void call() { } }",
                "@Retry maxRetries must be >= -1");
    }

    @Test
    void retryDelayJitterAndMaxDurationMustNotBeNegative() throws Exception {
        assertRejected("", "public class Bean { @Retry(delay = -1) public void call() { } }", "@Retry delay must be >= 0");
        assertRejected("", "public class Bean { @Retry(jitter = -1) public void call() { } }", "@Retry jitter must be >= 0");
        assertRejected("", "public class Bean { @Retry(maxDuration = -1) public void call() { } }",
                "@Retry maxDuration must be >= 0");
    }

    @Test
    void retryMaxDurationMustNotBeShorterThanTheDelay() throws Exception {
        assertRejected("", "public class Bean { @Retry(delay = 500, maxDuration = 100) public void call() { } }",
                "@Retry maxDuration must be >= delay when maxDuration > 0");
    }

    // -- @Timeout, spec section 7 -------------------------------------------------------------------------------

    @Test
    void timeoutValueMustNotBeNegative() throws Exception {
        assertRejected("", "public class Bean { @Timeout(-1) public void call() { } }", "@Timeout value must be >= 0");
    }

    // -- @Fallback, spec section 8 -------------------------------------------------------------------------------

    @Test
    void fallbackNeedsExactlyOneStrategy() throws Exception {
        assertRejected("", "public class Bean { @Fallback public String call() { return null; } }",
                "@Fallback must define exactly one strategy");
        assertRejected("", """
                public class Bean {
                    @Fallback(value = H.class, fallbackMethod = "other") public String call() { return null; }
                    public String other() { return null; }
                    public static class H implements FallbackHandler<String> {
                        public String handle(ExecutionContext c) { return null; }
                    }
                }""", "@Fallback must define exactly one strategy");
    }

    @Test
    void fallbackMethodMustExist() throws Exception {
        assertRejected("", "public class Bean { @Fallback(fallbackMethod = \"missing\") public String call() { return null; } }",
                "Invalid fallbackMethod 'missing'");
    }

    @Test
    void fallbackMethodMustHaveTheGuardedParametersAndAssignableReturnType() throws Exception {
        assertRejected("", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public String call(String s) { return null; }
                    public String other(Integer i) { return null; }
                }""", "Invalid fallbackMethod 'other'");
        assertRejected("", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public String call() { return null; }
                    public Integer other() { return null; }
                }""", "Invalid fallbackMethod 'other'");
        assertRejected("", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public int call() { return 0; }
                    public long other() { return 0; }
                }""", "Invalid fallbackMethod 'other'");
    }

    @Test
    void fallbackMethodMayHaveANarrowerReturnTypeButTheSameParameters() throws Exception {
        assertAccepted("", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public CharSequence call(String s) { return null; }
                    public String other(String s) { return null; }
                }""");
        // As the run-time validation does, a parameter of another type is no match, even a broader one.
        assertRejected("", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public CharSequence call(String s) { return null; }
                    public String other(CharSequence s) { return null; }
                }""", "Invalid fallbackMethod 'other'");
    }

    @Test
    void fallbackMethodMustAgreeOnConcreteGenericArguments() throws Exception {
        assertRejected("import java.util.List;", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public String call(List<String> l) { return null; }
                    public String other(List<Integer> l) { return null; }
                }""", "Invalid fallbackMethod 'other'");
        assertAccepted("import java.util.List;", """
                public class Bean {
                    @Fallback(fallbackMethod = "other") public String call(List<String> l) { return null; }
                    public String other(List<String> l) { return null; }
                }""");
    }

    @Test
    void fallbackMethodMayBePrivateInheritedOrFromAnInterface() throws Exception {
        assertAccepted("", """
                public class Bean extends Base implements Fallbacks {
                    @Fallback(fallbackMethod = "own") public String a() { return null; }
                    @Fallback(fallbackMethod = "inherited") public String b() { return null; }
                    @Fallback(fallbackMethod = "viaInterface") public String c() { return null; }
                    private String own() { return null; }
                }
                class Base { protected String inherited() { return null; } }
                interface Fallbacks { default String viaInterface() { return null; } }""");
    }

    @Test
    void aPrivateFallbackMethodOfASuperclassIsNotVisible() throws Exception {
        assertRejected("", """
                public class Bean extends Base {
                    @Fallback(fallbackMethod = "hidden") public String call() { return null; }
                }
                class Base { private String hidden() { return null; } }""", "Invalid fallbackMethod 'hidden'");
    }

    @Test
    void fallbackHandlerReturnTypeMustFitTheGuardedMethod() throws Exception {
        assertRejected("", """
                public class Bean {
                    @Fallback(H.class) public Integer call() { return null; }
                    public static class H implements FallbackHandler<String> {
                        public String handle(ExecutionContext c) { return null; }
                    }
                }""", "FallbackHandler return type mismatch");
        assertAccepted("", """
                public class Bean {
                    @Fallback(H.class) public int call() { return 0; }
                    public static class H implements FallbackHandler<Integer> {
                        public Integer handle(ExecutionContext c) { return 1; }
                    }
                }""");
    }

    // -- Which annotation applies: the runtime reader's rule (method, else nearest class) ------------------------

    @Test
    void anInvalidClassLevelAnnotationIsHiddenByAValidOneOnTheMethod() throws Exception {
        assertAccepted("", "@Timeout(-1) public class Bean { @Timeout(5) public void call() { } }");
    }

    @Test
    void aSubclassAnnotationHidesTheInvalidOneOfItsSuperclass() throws Exception {
        assertAccepted("", """
                @Timeout(5) public class Bean extends Base { }
                @Timeout(-1) abstract class Base { public void call() { } }""");
    }

    @Test
    void anInvalidAnnotationOfAnInheritedMethodIsRejected() throws Exception {
        assertRejected("", """
                public class Bean extends Base { }
                abstract class Base { @Timeout(-1) public void call() { } }""", "@Timeout value must be >= 0");
    }

    @Test
    void privateAndStaticMethodsAreNeverGuardedSoTheyAreNotChecked() throws Exception {
        assertAccepted("", """
                public class Bean {
                    @Timeout(-1) private void hidden() { }
                    @Timeout(-1) public static void shared() { }
                }""");
    }

    @Test
    void anAbstractClassIsCheckedThroughItsConcreteSubclasses() throws Exception {
        assertAccepted("", "public abstract class Bean { @Timeout(-1) public abstract void call(); }");
        assertRejected("", """
                public abstract class Bean { @Timeout(-1) public void call() { } }
                class Impl extends Bean { }""", "@Timeout value must be >= 0");
    }

    @Test
    void theErrorNamesTheMethod() throws Exception {
        assertRejected("", "public class Bean { @Timeout(-1) public void slow() { } }", "slow");
    }
}
