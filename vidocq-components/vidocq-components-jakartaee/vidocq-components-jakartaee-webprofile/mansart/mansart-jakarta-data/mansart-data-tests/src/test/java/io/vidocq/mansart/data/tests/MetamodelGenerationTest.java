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
