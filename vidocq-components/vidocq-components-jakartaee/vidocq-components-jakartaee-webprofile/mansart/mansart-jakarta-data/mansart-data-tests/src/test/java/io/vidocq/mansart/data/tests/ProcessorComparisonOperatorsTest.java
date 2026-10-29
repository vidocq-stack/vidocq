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

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The comparison operators of a {@code @Query} that the annotation processor reads at compile time. */
class ProcessorComparisonOperatorsTest {

    private static final String PET = "demo.Pet";
    private static final String PETS = "demo.Pets";
    private static final String PET_SOURCE = """
            package demo;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class Pet {
                @Id @Column(name = "id") private Long id;
                @Column(name = "name") private String name;

                public Pet() {
                    // instantiated by the generated repository
                }

                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """;

    @TempDir
    Path out;

    private static String repository(String queries) {
        return """
                package demo;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Pets extends BasicRepository<Pet, Long> {
                %s
                }
                """.formatted(queries);
    }

    @Test
    void compilesEveryComparisonOperator() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE, PETS,
                repository("""
                        @Query("WHERE name <> ?1") List<Pet> notNamed(String name);
                        @Query("WHERE name != ?1") List<Pet> otherThan(String name);
                        @Query("WHERE id < ?1") List<Pet> below(Long id);
                        @Query("WHERE id <= ?1") List<Pet> upTo(Long id);
                        @Query("WHERE id > ?1") List<Pet> above(Long id);
                        @Query("WHERE id >= ?1") List<Pet> from(Long id);
                        @Query("WHERE id = ?1") List<Pet> exactly(Long id);
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        String impl = result.generatedSource("demo.PetsImpl");
        assertThat(impl).contains("notNamed", "otherThan", "below", "upTo", "above", "from", "exactly");
    }

    /** An invalid query fails the compilation with an error that names it; the processor does not crash javac. */
    @Test
    void rejectsALoneExclamationMark() throws IOException {
        String pets = repository("""
                @Query("WHERE name ! ?1") List<Pet> odd(String name);
                """);

        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE, PETS, pets);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anySatisfy(error -> assertThat(error)
                .contains("demo.Pets", "odd", "Unexpected '!'"));
    }
}
