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
package io.vidocq.mansart.data.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles this module's entities and repositories ({@code src/main/java}) again, with the annotation processor
 * running in the test JVM, and checks the processor's contract on them: the compilation succeeds with the expected warning only,
 * every entity gets its {@code _Entity} metamodel, every repository its {@code Impl} listed in
 * {@code META-INF/mansart-repositories.list}, and the generated sources are identical to the ones the build
 * produced (the generation is reproducible). The behaviour of the generated code is tested by the other tests of
 * this module, against H2.
 */
class ProcessorReproducibilityTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path BUILD_GENERATED = Path.of("target/generated-sources/annotations");

    @TempDir
    static Path out;

    private static InProcessCompilation.Result result;

    @BeforeAll
    static void compile() throws IOException {
        result = InProcessCompilation.compileTree(MAIN_SOURCES, out);
    }

    @Test
    void compilesTheFixturesWithTheOnlyExpectedWarning() {
        assertThat(result.errors()).isEmpty();
        // TransactionalArticleRepository.findByTitleSensitive only checks @Transactional propagation: its name
        // derives no query on purpose.
        assertThat(result.warnings()).containsExactly("Mansart could not derive a query for "
                + "'io.vidocq.mansart.data.tests.TransactionalArticleRepository.findByTitleSensitive'; the generated "
                + "method will throw UnsupportedOperationException at runtime. Check the method name and attribute "
                + "spelling.");
        assertThat(result.success()).isTrue();
    }

    @Test
    void generatesAMetamodelPerEntityAndAnImplementationPerRepository() throws IOException {
        List<String> sources = relativeJavaFiles(MAIN_SOURCES);
        List<String> generated = relativeJavaFiles(result.generated());

        for (String source : sources) {
            String text = Files.readString(MAIN_SOURCES.resolve(source), StandardCharsets.UTF_8);
            String simple = source.substring(source.lastIndexOf('/') + 1, source.length() - ".java".length());
            String dir = source.substring(0, source.lastIndexOf('/') + 1);
            if (text.contains("@Entity")) {
                assertThat(generated).as("metamodel of %s", source).contains(dir + "_" + simple + ".java");
            }
            if (text.contains("@Repository")) {
                assertThat(generated).as("implementation of %s", source).contains(dir + simple + "Impl.java");
            }
        }
    }

    @Test
    void listsEveryRepositoryImplementation() throws IOException {
        List<String> entries = Files.readAllLines(result.classes().resolve("META-INF/mansart-repositories.list"))
                .stream().filter(l -> !l.startsWith("#")).toList();

        assertThat(entries).isNotEmpty().allSatisfy(entry -> {
            String[] pair = entry.split("=");
            assertThat(pair[1]).isEqualTo(pair[0] + "Impl");
            assertThat(result.classes().resolve(pair[1].replace('.', '/') + ".class")).exists();
        });
        assertThat(entries).containsExactlyInAnyOrderElementsOf(Files.readAllLines(
                Path.of("target/classes/META-INF/mansart-repositories.list")).stream()
                .filter(l -> !l.startsWith("#")).toList());
    }

    @Test
    void generatesTheSameSourcesAsTheBuild() throws IOException {
        List<String> generated = relativeJavaFiles(result.generated());

        assertThat(generated).containsExactlyInAnyOrderElementsOf(relativeJavaFiles(BUILD_GENERATED));
        for (String file : generated) {
            assertThat(result.generated().resolve(file)).as(file)
                    .hasSameTextualContentAs(BUILD_GENERATED.resolve(file), StandardCharsets.UTF_8);
        }
    }

    private static List<String> relativeJavaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }
}
