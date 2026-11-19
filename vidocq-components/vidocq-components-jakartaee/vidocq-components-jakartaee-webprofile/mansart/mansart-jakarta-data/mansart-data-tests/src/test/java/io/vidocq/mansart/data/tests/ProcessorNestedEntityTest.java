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

/**
 * An entity may be a static nested class (Jakarta Persistence: "a non-final top-level class or static inner
 * class"). Its metamodel is {@code _Library_Shelf} in the entity's package and refers to the entity as
 * {@code Library.Shelf} (MANSART-050).
 */
class ProcessorNestedEntityTest {

    @TempDir
    Path out;

    @Test
    void aNestedEntityGetsItsMetamodelInItsPackage() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, "shop.Library", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                public class Library {
                    @Entity
                    public static class Shelf {
                        @Id public Long id;
                        public String name;
                    }
                }
                """, "shop.Shelves", """
                package shop;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Shelves extends BasicRepository<Library.Shelf, Long> {
                    List<Library.Shelf> findByName(String name);
                }
                """);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.generatedSource("shop._Library_Shelf"))
                .contains("package shop;", "Library.Shelf", " name = ");
        assertThat(result.generatedSource("shop.ShelvesImpl")).contains("_Library_Shelf.name");
    }
}
