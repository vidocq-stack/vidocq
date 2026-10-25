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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * {@link VidocqTest} classes run by the JUnit Platform, against a module named {@code io.vidocq.runtime.core} that
 * this test compiles: its {@code Vidocq} main class answers {@code GET /hello} on the port the extension gives it,
 * with the properties it was launched with (Vidocq/vidocq#350).
 */
class VidocqTestExtensionTest {

    /** The runtime's main module and class, as {@link ApplicationLaunch} launches them. */
    private static final String MODULE_INFO = """
            module io.vidocq.runtime.core {
                requires jdk.httpserver;
            }
            """;

    private static final String MAIN = """
            package io.vidocq.runtime.core;

            public final class Vidocq {
                public static void main(String[] args) throws Exception {
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(
                            System.getProperty("vidocq.chappe.listener.default.host"),
                            Integer.getInteger("vidocq.chappe.listener.default.port")), 0);
                    server.createContext("/hello", exchange -> {
                        byte[] body = (System.getProperty("greeting") + " from "
                                + System.getProperty("vidocq.app.module")).getBytes();
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
                    server.start();
                }
            }
            """;

    /** The application the fixtures saw, to check it is shared, then stopped. */
    static final AtomicReference<VidocqApplication> SEEN = new AtomicReference<>();

    private static String previousModulePath;

    @BeforeAll
    static void compileTheRuntime(@TempDir Path dir) throws IOException, InterruptedException {
        Path src = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(src.resolve("io/vidocq/runtime/core"));
        Path moduleInfo = Files.writeString(src.resolve("module-info.java"), MODULE_INFO);
        Path main = Files.writeString(src.resolve("io/vidocq/runtime/core/Vidocq.java"), MAIN);
        // javac as a process: this test module does not read java.compiler.
        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        Process compile = new ProcessBuilder(javac.toString(), "-d", classes.toString(), moduleInfo.toString(),
                main.toString()).inheritIO().start();
        assertEquals(0, compile.waitFor(), "the fake runtime does not compile");
        // What Surefire sets when it runs an application's tests on the module path.
        previousModulePath = System.setProperty("jdk.module.path", classes.toString());
    }

    @AfterAll
    static void restoreTheModulePath() {
        if (previousModulePath == null) {
            System.clearProperty("jdk.module.path");
        } else {
            System.setProperty("jdk.module.path", previousModulePath);
        }
    }

    @Test
    void theApplicationStartsOnceForTheClassItsNestedClassesIncludedAndStopsAfterIt() {
        SEEN.set(null);

        TestExecutionSummary summary = run(Fixture.class);

        assertEquals(4, summary.getTestsSucceededCount(), () -> failures(summary));
        assertEquals(0, summary.getTotalFailureCount(), () -> failures(summary));
        assertFalse(SEEN.get().isAlive(), "the application outlived its class");
    }

    @Test
    void aVidocqUrlParameterOfAnotherTypeIsNotResolved() {
        TestExecutionSummary summary = run(WrongUrlType.class);

        assertEquals(1, summary.getTotalFailureCount());
        assertTrue(failures(summary).contains("No ParameterResolver registered for parameter [int "),
                failures(summary));
    }

    @Test
    void theExtensionAloneNamesTheMissingAnnotation() {
        TestExecutionSummary summary = run(NotAnnotated.class);

        assertTrue(failures(summary).contains(NotAnnotated.class.getName() + " is not annotated @VidocqTest"),
                failures(summary));
    }

    private static TestExecutionSummary run(Class<?> fixture) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request().selectors(selectClass(fixture)).build(), listener);
        return listener.getSummary();
    }

    private static String failures(TestExecutionSummary summary) {
        return summary.getFailures().stream()
                .map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException())
                .toList().toString();
    }

    // Fixtures: static nested classes, which neither Surefire nor JUnit's discovery of this class runs on their own.

    @VidocqTest(module = "io.vidocq.runtime.core", properties = "greeting=hi")
    static class Fixture {

        @Test
        void theApplicationAnswers(VidocqApplication application) {
            SEEN.compareAndSet(null, application);
            assertSame(SEEN.get(), application);
            assertEquals("hi from io.vidocq.runtime.core", application.get("/hello").body());
        }

        @Test
        void theUrlComesAsAUriOrAString(@VidocqUrl URI uri, @VidocqUrl String url, VidocqApplication application) {
            assertEquals(application.url(), uri);
            assertEquals(application.url().toString(), url);
        }

        @Test
        void theApplicationIsTheSameForEveryMethod(VidocqApplication application) {
            SEEN.compareAndSet(null, application);
            assertSame(SEEN.get(), application);
        }

        @Nested
        class Inner {
            @Test
            void aNestedClassSharesTheApplication(VidocqApplication application) {
                SEEN.compareAndSet(null, application);
                assertSame(SEEN.get(), application);
            }
        }
    }

    @VidocqTest(module = "io.vidocq.runtime.core")
    static class WrongUrlType {
        @Test
        void port(@VidocqUrl int port) {
            // never runs: no resolver takes an int
        }
    }

    @ExtendWith(VidocqTestExtension.class)
    static class NotAnnotated {
        @Test
        void nothing() {
            // never runs: the extension fails before
        }
    }
}
