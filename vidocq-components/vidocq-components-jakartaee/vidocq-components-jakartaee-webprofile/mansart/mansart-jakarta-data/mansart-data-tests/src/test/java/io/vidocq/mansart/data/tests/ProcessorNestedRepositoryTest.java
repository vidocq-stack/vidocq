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

import io.vidocq.mansart.data.core.MansartData;
import jakarta.data.repository.BasicRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repositories nested in another type: each one gets its own implementation, named after its binary name
 * ({@code Outer$RepoImpl}) so that two nested repositories of the same simple name do not clash, and so that
 * {@link MansartData#repository(Class)} finds it; one the generated class cannot implement is left to the run-time
 * path instead of failing the build.
 */
class ProcessorNestedRepositoryTest {

    private static final String PET = "demo.Pet";
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

    private static String holder(String name, String modifier) {
        return """
                package demo;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;

                public class %s {
                    @Repository
                    %s interface Pets extends BasicRepository<Pet, Long> {
                    }
                }
                """.formatted(name, modifier);
    }

    @Test
    void twoNestedRepositoriesOfTheSameNameEachGetTheirImplementation() throws Exception {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE,
                "demo.Kennel", holder("Kennel", "public"), "demo.Shelter", holder("Shelter", "public"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.generatedSource("demo.Kennel$PetsImpl")).contains("implements Kennel.Pets");
        assertThat(result.generatedSource("demo.Shelter$PetsImpl")).contains("implements Shelter.Pets");
        assertThat(Files.readString(result.classes().resolve("META-INF/mansart-repositories.list")))
                .contains("demo.Kennel.Pets=demo.Kennel$PetsImpl", "demo.Shelter.Pets=demo.Shelter$PetsImpl");

        try (URLClassLoader loader = new URLClassLoader(new URL[]{result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> pets = loader.loadClass("demo.Kennel$Pets");
            Object repository = data().repository(pets);
            assertThat(repository.getClass().getName()).isEqualTo("demo.Kennel$PetsImpl");
            assertThat(((BasicRepository<?, ?>) repository).findAll().toList()).isEmpty();
        }
    }

    @Test
    void aPrivateNestedRepositoryIsLeftToTheRunTimePath() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE,
                "demo.Hidden", holder("Hidden", "private"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(d -> assertThat(d.getMessage(java.util.Locale.ROOT))
                .contains("demo.Hidden.Pets", "run-time"));
        assertThat(result.generated().resolve("demo/Hidden$PetsImpl.java")).doesNotExist();
        assertThat(result.classes().resolve("META-INF/mansart-repositories.list")).doesNotExist();
    }

    private static MansartData data() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-nested-repositories;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        MansartData data = MansartData.builder().dataSource(ds).build();
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS \"pets\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100))");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return data;
    }
}
