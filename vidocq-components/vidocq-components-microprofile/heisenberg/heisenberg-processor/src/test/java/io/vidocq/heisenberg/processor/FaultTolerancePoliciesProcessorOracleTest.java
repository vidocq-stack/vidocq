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

import io.vidocq.heisenberg.internal.AnnotationReader;
import io.vidocq.heisenberg.spi.gen.FaultToleranceCompanion;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The companions the processor generates say, for every method an interceptor can guard, what the runtime's
 * reflective {@link AnnotationReader} says: the processor is checked against it as an oracle, on a corpus of
 * declarations covering method and class level, inheritance, overloads, defaults and every member type.
 */
class FaultTolerancePoliciesProcessorOracleTest {

    @TempDir
    static Path tempDir;

    private static URLClassLoader loader;
    private static List<String> notes;

    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("t/Handler.java", """
                package t;
                import org.eclipse.microprofile.faulttolerance.ExecutionContext;
                import org.eclipse.microprofile.faulttolerance.FallbackHandler;
                class Handler implements FallbackHandler<String> {
                    public String handle(ExecutionContext context) { return "handled"; }
                }
                """);
        SOURCES.put("t/Base.java", """
                package t;
                import java.time.temporal.ChronoUnit;
                import org.eclipse.microprofile.faulttolerance.Retry;
                import org.eclipse.microprofile.faulttolerance.Timeout;
                @Retry(maxRetries = 7, delay = 20, delayUnit = ChronoUnit.SECONDS, retryOn = java.io.IOException.class)
                public abstract class Base {
                    @Timeout(value = 300, unit = ChronoUnit.MILLIS)
                    public String inherited() { return "base"; }
                    @Timeout(9)
                    public String overridden() { return "base"; }
                    String packagePrivate() { return "pp"; }
                }
                """);
        SOURCES.put("t/Service.java", """
                package t;
                import java.util.List;
                import java.util.Map;
                import org.eclipse.microprofile.faulttolerance.Asynchronous;
                import org.eclipse.microprofile.faulttolerance.Bulkhead;
                import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
                import org.eclipse.microprofile.faulttolerance.Fallback;
                import org.eclipse.microprofile.faulttolerance.Retry;
                @CircuitBreaker
                public class Service extends Base {
                    @Override public String overridden() { return "service"; }
                    @Bulkhead(value = 3, waitingTaskQueue = 4)
                    @Fallback(fallbackMethod = "recover", applyOn = {IllegalStateException.class, java.io.IOException.class},
                              skipOn = IllegalArgumentException.class)
                    public String guarded(String id, int[] counts, List<String> names, Map.Entry<String, Integer> e) {
                        return id;
                    }
                    public String recover(String id, int[] counts, List<String> names, Map.Entry<String, Integer> e) {
                        return "fallback";
                    }
                    @Retry(maxRetries = 1, jitter = 0, abortOn = {})
                    public <T extends CharSequence> T generic(T value) { return value; }
                    @Fallback(Handler.class)
                    @Asynchronous
                    public java.util.concurrent.CompletionStage<String> async() { return null; }
                    @CircuitBreaker(failOn = RuntimeException.class, delay = 5, requestVolumeThreshold = 2,
                                    failureRatio = 0.25, successThreshold = 3)
                    protected String tuned() { return "tuned"; }
                    private String hidden() { return "hidden"; }
                    public static String statik() { return "static"; }
                    public static class Nested {
                        @org.eclipse.microprofile.faulttolerance.Timeout(42)
                        public String call() { return "nested"; }
                        @Fallback
                        public String defaultHandler() { return "default"; }
                    }
                    private static class Hidden {
                        @org.eclipse.microprofile.faulttolerance.Timeout(1)
                        public String call() { return "hidden"; }
                    }
                }
                """);
        SOURCES.put("t/Plain.java", """
                package t;
                public class Plain {
                    public String call() { return "plain"; }
                }
                """);
    }

    @BeforeAll
    static void compile() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        File out = Files.createDirectories(tempDir.resolve("classes")).toFile();
        File gen = Files.createDirectories(tempDir.resolve("generated")).toFile();
        List<File> sources = new ArrayList<>();
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            Path file = tempDir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
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
            boolean ok = compiler.getTask(null, files, diagnostics, List.of("--release", "25", "-proc:full"),
                    null, files.getJavaFileObjectsFromFiles(sources))
                    .call();
            notes = diagnostics.getDiagnostics().stream().map(d -> d.getMessage(Locale.ROOT)).toList();
            if (!ok) fail("compilation failed: " + notes);
        }
        loader = new URLClassLoader(new java.net.URL[] {out.toURI().toURL()},
                FaultTolerancePoliciesProcessorOracleTest.class.getClassLoader());
    }

    private static FaultToleranceCompanion companion(String beanClass) throws Exception {
        Class<?> type = loader.loadClass(beanClass + FaultToleranceCompanion.SUFFIX);
        return (FaultToleranceCompanion) type.getDeclaredConstructor().newInstance();
    }

    /** Every method an interceptor can guard, nearest declaration first, as the runtime resolves it. */
    private static Map<String, Method> guardable(Class<?> beanClass) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                methods.putIfAbsent(FaultToleranceCompanion.methodKey(m.getName(), m.getParameterTypes()), m);
            }
        }
        return methods;
    }

    private static void assertMatchesReflection(String beanClassName) throws Exception {
        Class<?> beanClass = loader.loadClass(beanClassName);
        FaultToleranceCompanion companion = companion(beanClassName);
        assertEquals(beanClass, companion.beanClass());
        for (Map.Entry<String, Method> entry : guardable(beanClass).entrySet()) {
            AnnotationReader.FaultToleranceAnnotations expected =
                    AnnotationReader.reflectively(entry.getValue(), beanClass);
            FaultToleranceCompanion.Policies actual = companion.policies(entry.getKey());
            String where = beanClassName + "#" + entry.getKey();
            if (actual == null) {
                assertEquals(new AnnotationReader.FaultToleranceAnnotations(null, null, null, null, null, null),
                        expected, where + ": the companion has no policies");
                continue;
            }
            assertSameAnnotation(expected.retry(), actual.retry(), where);
            assertSameAnnotation(expected.timeout(), actual.timeout(), where);
            assertSameAnnotation(expected.circuitBreaker(), actual.circuitBreaker(), where);
            assertSameAnnotation(expected.bulkhead(), actual.bulkhead(), where);
            assertSameAnnotation(expected.fallback(), actual.fallback(), where);
            assertSameAnnotation(expected.asynchronous(), actual.asynchronous(), where);
        }
    }

    /** Same annotation type and same value for every member, arrays compared by content. */
    private static void assertSameAnnotation(Annotation expected, Annotation actual, String where) throws Exception {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, where);
            return;
        }
        assertEquals(expected.annotationType(), actual.annotationType(), where);
        for (Method member : expected.annotationType().getDeclaredMethods()) {
            Object want = member.invoke(expected);
            Object got = member.invoke(actual);
            String label = where + " @" + expected.annotationType().getSimpleName() + "." + member.getName();
            if (want instanceof Object[] array) {
                assertTrue(Arrays.equals(array, (Object[]) got), label + ": " + Arrays.toString(array)
                        + " vs " + Arrays.toString((Object[]) got));
            } else {
                assertEquals(want, got, label);
            }
        }
    }

    @Test
    void theCompanionOfAClassMatchesTheReflectiveReadingOfEveryMethod() throws Exception {
        assertMatchesReflection("t.Service");
    }

    @Test
    void aNestedClassHasItsOwnCompanion() throws Exception {
        assertMatchesReflection("t.Service$Nested");
    }

    @Test
    void theKeysCoverGenericArrayNestedAndPrimitiveParameters() throws Exception {
        FaultToleranceCompanion companion = companion("t.Service");
        assertNotNull(companion.policies("guarded(java.lang.String,int[],java.util.List,java.util.Map$Entry)"));
        assertNotNull(companion.policies("generic(java.lang.CharSequence)"));
        assertNull(companion.policies("nothing()"));
    }

    @Test
    void classesTheProcessorCannotServeHaveNoCompanion() {
        for (String name : List.of("t.Service$Hidden", "t.Base", "t.Plain", "t.Handler")) {
            assertThrows(name);
        }
        assertTrue(notes.stream().anyMatch(n -> n.contains("t.Service$Hidden" + FaultToleranceCompanion.SUFFIX)),
                () -> "a note names the private class: " + notes);
    }

    private static void assertThrows(String beanClass) {
        try {
            loader.loadClass(beanClass + FaultToleranceCompanion.SUFFIX);
            fail(beanClass + " has a companion");
        } catch (ClassNotFoundException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    void theCompanionsAreListedForServiceLoader() throws Exception {
        try (var in = loader.getResourceAsStream("META-INF/services/" + FaultToleranceCompanion.class.getName())) {
            assertNotNull(in, "no services file");
            String listed = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(List.of("t.Service$$HeisenbergPolicies", "t.Service$Nested$$HeisenbergPolicies"),
                    listed.lines().toList());
        }
    }
}
