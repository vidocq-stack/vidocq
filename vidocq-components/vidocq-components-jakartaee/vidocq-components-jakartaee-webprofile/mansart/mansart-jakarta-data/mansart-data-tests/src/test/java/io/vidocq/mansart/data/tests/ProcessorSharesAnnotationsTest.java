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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-20261128-90: the Mansart processor claimed {@code @Entity} and {@code @Repository}, so a processor that javac
 * runs after it (a persistence provider's metamodel processor, Noether's or Hibernate's) never saw them.
 */
class ProcessorSharesAnnotationsTest {

    @TempDir
    Path out;

    /** Records the annotations javac hands it. */
    @SupportedAnnotationTypes({"jakarta.persistence.Entity", "jakarta.data.repository.Repository"})
    static final class Recorder extends AbstractProcessor {
        final Set<String> seen = ConcurrentHashMap.newKeySet();

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            annotations.forEach(a -> seen.add(a.getQualifiedName().toString()));
            return false;
        }
    }

    @Test
    void aProcessorAfterMansartStillSeesTheEntityAndRepositoryAnnotations() throws IOException {
        Recorder recorder = new Recorder();

        var result = InProcessCompilation.compileSourcesWith(out, List.of(recorder),
                "demo.Book", """
                        package demo;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;

                        @Entity
                        public class Book {
                            @Id
                            public Long id;
                            public String title;
                        }
                        """,
                "demo.Books", """
                        package demo;

                        import jakarta.data.repository.BasicRepository;
                        import jakarta.data.repository.Repository;

                        @Repository
                        public interface Books extends BasicRepository<Book, Long> {
                        }
                        """);

        assertThat(result.errors()).isEmpty();
        assertThat(recorder.seen).containsExactlyInAnyOrder("jakarta.persistence.Entity",
                "jakarta.data.repository.Repository");
    }
}
