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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code -A} option the processor reads is one it declares, so javac does not report it as recognised by no
 * processor (BUG-20261123-40: {@code vauban.mainOutput} was read and not declared).
 */
@DisplayName("VaubanProcessor - supported options")
class SupportedOptionsTest {

    /** Each option the processor reads from {@code processingEnv.getOptions()}. */
    private static final List<String> READ_OPTIONS = List.of("vauban.validation", "vauban.validation.scope",
            "vauban.producerProxy", "vauban.providesHint", "vauban.generationRound", "vauban.mainOutput");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("declares each option it reads")
    void declaresEachOptionItReads() {
        var declared = new VaubanProcessor().getSupportedOptions();

        assertEquals(List.of(), READ_OPTIONS.stream().filter(option -> !declared.contains(option)).toList(),
                "options read but not declared");
    }

    @Test
    @DisplayName("javac reports none of its options as recognised by no processor")
    void javacRecognisesEveryOption() throws Exception {
        var mainOutput = Files.createDirectories(tempDir.resolve("main-classes"));
        var options = List.of("-Xlint:processing", "-Avauban.validation=true", "-Avauban.validation.scope=main",
                "-Avauban.producerProxy=note", "-Avauban.providesHint=true", "-Avauban.generationRound=early",
                "-Avauban.mainOutput=" + mainOutput);

        var result = ProcessorCompiler.compile(tempDir.resolve("build"), Map.of("app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), options);

        assertTrue(result.success(), () -> "compilation should succeed: " + result.messages());
        assertFalse(result.mentions("not recognized by any processor"), () -> "javac warned: " + result.messages());
    }
}
