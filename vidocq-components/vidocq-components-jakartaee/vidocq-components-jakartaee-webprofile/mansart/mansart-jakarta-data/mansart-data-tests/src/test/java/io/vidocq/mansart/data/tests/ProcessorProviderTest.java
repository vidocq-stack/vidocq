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
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Repository(provider = "…")} names the Jakarta Data provider that implements the repository (specification
 * §4): a repository declared for another provider is not Mansart's to implement, at build time or at run time
 * (mansart#85).
 */
class ProcessorProviderTest {

    private static final String PET = "demo.Pet";
    private static final String PET_SOURCE = """
            package demo;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class Pet {
                @Id public Long id;
                public String name;
            }
            """;

    @TempDir
    Path out;

    @Test
    void aRepositoryOfAnotherProviderIsLeftToIt() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE, "demo.Pets", """
                package demo;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;

                @Repository(provider = "Hibernate")
                public interface Pets extends BasicRepository<Pet, Long> {
                }
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.generated().resolve("demo/PetsImpl.java")).doesNotExist();
        assertThat(result.classes().resolve("META-INF/mansart-repositories.list")).doesNotExist();
        assertThat(result.diagnostics()).anySatisfy(d -> assertThat(d.getMessage(Locale.ROOT))
                .contains("demo.Pets", "provider \"Hibernate\"", "Mansart leaves it to that provider"));
    }

    @Test
    void aRepositoryNamingMansartAsItsProviderIsImplemented() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, PET, PET_SOURCE, "demo.Pets", """
                package demo;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;

                @Repository(provider = "mansart")
                public interface Pets extends BasicRepository<Pet, Long> {
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generated().resolve("demo/PetsImpl.java")).exists();
    }
}
