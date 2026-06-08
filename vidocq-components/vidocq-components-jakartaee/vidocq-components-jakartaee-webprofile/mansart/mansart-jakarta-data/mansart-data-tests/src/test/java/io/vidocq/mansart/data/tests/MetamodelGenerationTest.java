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

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TemporalAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the APT processor produced the {@code _Book} metamodel with the right shape:
 *  - {@code books} as the table name (snake_case + pluralization),
 *  - one {@link IdAttribute} for {@code id},
 *  - one {@link TextAttribute} for {@code title},
 *  - one {@link ReferenceAttribute} for {@code author} with column {@code author_id},
 *  - one {@link TemporalAttribute} for {@code publishedOn} with column {@code published_on},
 *  - one {@link VersionAttribute} for {@code version}.
 */
class MetamodelGenerationTest {

    @Test
    void metamodelExposesEntityModel() {
        assertThat(_Book.$MODEL.tableName()).isEqualTo("books");
        assertThat(_Book.$MODEL.entityClass()).isEqualTo(Book.class);
        assertThat(_Book.$MODEL.id().name()).isEqualTo("id");
        assertThat(_Book.$MODEL.version()).isPresent();
        assertThat(_Book.$MODEL.version().orElseThrow().name()).isEqualTo("version");
        assertThat(_Book.$MODEL.attributes())
                .extracting(Attribute::name)
                .containsExactlyInAnyOrder("id", "title", "author", "publishedOn", "version");
    }

    @Test
    void idIsTypedAsIdAttribute() {
        assertThat(_Book.id).isInstanceOf(IdAttribute.class);
        assertThat(_Book.id.columnName()).isEqualTo("id");
        assertThat(_Book.id.javaType()).isEqualTo(Long.class);
        assertThat(_Book.id.generated()).isTrue();
    }

    @Test
    void titleIsText() {
        assertThat(_Book.title).isInstanceOf(TextAttribute.class);
        assertThat(_Book.title.columnName()).isEqualTo("title");
        assertThat(_Book.title.length()).isEqualTo(300);
        assertThat(_Book.title.nullable()).isFalse();
    }

    @Test
    void authorIsReferenceWithFkColumn() {
        assertThat(_Book.author).isInstanceOf(ReferenceAttribute.class);
        assertThat(_Book.author.columnName()).isEqualTo("author_id");
        assertThat(_Book.author.javaType()).isEqualTo(Author.class);
    }

    @Test
    void publishedOnIsTemporalSnakeCase() {
        assertThat(_Book.publishedOn).isInstanceOf(TemporalAttribute.class);
        assertThat(_Book.publishedOn.columnName()).isEqualTo("published_on");
        assertThat(_Book.publishedOn.javaType()).isEqualTo(LocalDate.class);
    }

    @Test
    void versionIsVersionAttribute() {
        assertThat(_Book.version).isInstanceOf(VersionAttribute.class);
        assertThat(_Book.version.javaType()).isEqualTo(Integer.class);
    }

    @Test
    void methodHandlesReadAndWriteState() throws Throwable {
        Book b = new Book();
        _Book.title.setter().invoke(b, "Les Misérables");
        Object value = _Book.title.getter().invoke(b);
        assertThat(value).isEqualTo("Les Misérables");
    }
}
