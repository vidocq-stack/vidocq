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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.processor.VaubanProcessor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261127-57: the processor supports every annotation ({@code *}) and returned {@code true} from the round where
 * its pipeline ran: it claimed every annotation of that round, and javac no longer handed them to the processors after
 * it. A class a companion processor generates in the first round, a bean, surfaces in the second, where the pipeline
 * runs: a processor after Vauban never saw its annotations.
 */
@DisplayName("BUG-20261127-57: the processor claims no annotation")
class ClaimsNoAnnotationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a processor after Vauban sees the annotations of the round where the pipeline runs")
    void aLaterProcessorSeesEveryAnnotatedClass() throws IOException {
        var recorder = new Recorder();

        var result = ProcessorCompiler.compile(tempDir, Map.of("app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new Companion(), new VaubanProcessor(), recorder));

        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertTrue(recorder.applicationScoped.containsAll(Set.of("app.Generated", "app.Greeter")),
                () -> "the classes a processor after Vauban saw with @ApplicationScoped: " + recorder.applicationScoped);
    }

    @Test
    @DisplayName("process returns false in every round")
    void processNeverClaims() throws IOException {
        var vauban = new RecordingVauban();

        var result = ProcessorCompiler.compile(tempDir, Map.of("app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new Companion(), vauban));

        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertTrue(vauban.rounds.size() >= 2, "rounds " + vauban.rounds);
        assertEquals(List.of(), vauban.rounds.stream().filter(Boolean::booleanValue).toList(), "rounds that claimed");
    }

    @Test
    @DisplayName("process returns false for a compilation without a bean")
    void processNeverClaimsWithoutABean() throws IOException {
        var vauban = new RecordingVauban();
        vauban.overrideBceClasses(List.of());

        // Indexed, as it carries a scope, but no bean: an interface is not a managed bean
        var result = ProcessorCompiler.compile(tempDir, Map.of("app/Service.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public interface Service {
                }
                """), List.of(), List.of(vauban));

        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertEquals(List.of(), vauban.rounds.stream().filter(Boolean::booleanValue).toList(), "rounds that claimed");
    }

    /** Vauban's processor, recording what it returned in each round. */
    private static final class RecordingVauban extends VaubanProcessor {

        final List<Boolean> rounds = new java.util.ArrayList<>();

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            rounds.add(super.process(annotations, roundEnv));
            return false;
        }
    }

    /** A companion processor: generates the bean {@code app.Generated} in the first round, as Mansart Data does. */
    private static final class Companion extends AbstractProcessor {

        private boolean done;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("jakarta.enterprise.context.ApplicationScoped");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!done && !annotations.isEmpty()) {
                done = true;
                try (var writer = processingEnv.getFiler().createSourceFile("app.Generated").openWriter()) {
                    writer.write("""
                            package app;

                            @jakarta.enterprise.context.ApplicationScoped
                            public class Generated {
                            }
                            """);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }

    /** Records the classes it is handed with {@code @ApplicationScoped}, in every round. */
    private static final class Recorder extends AbstractProcessor {

        final Set<String> applicationScoped = new TreeSet<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("jakarta.enterprise.context.ApplicationScoped");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (var annotation : annotations) {
                for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    applicationScoped.add(((TypeElement) element).getQualifiedName().toString());
                }
            }
            return false;
        }
    }
}
