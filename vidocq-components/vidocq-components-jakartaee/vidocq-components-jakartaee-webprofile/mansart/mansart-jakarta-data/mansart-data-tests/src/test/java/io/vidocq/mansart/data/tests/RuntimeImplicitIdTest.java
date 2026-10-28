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

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RuntimeEntityModelBuilder;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.EnumStorage;
import io.vidocq.mansart.data.dialect.attribute.NumericAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the runtime entity model builder picks the id of an entity without {@code @Id} (Jakarta Data 1.0 implicit id)
 * and maps the other fields: explicit {@code @Id} first, then a field named {@code id}, the single field of the
 * repository's key type, and {@code <entity>Id} / {@code <entity>Identifier} / {@code <entity>Key}.
 */
class RuntimeImplicitIdTest {

    static class Explicit {
        @Id String code;
        long id;
        Explicit() { /* JPA no-arg constructor */ }
    }

    static class NamedId {
        String id;
        String label;
        NamedId() { /* JPA no-arg constructor */ }
    }

    static class TypedKey {
        long serial;
        String label;
        TypedKey() { /* JPA no-arg constructor */ }
    }

    static class Parcel {
        long parcelId;
        long weight;
        Parcel() { /* JPA no-arg constructor */ }
    }

    static class Crate {
        String crateIdentifier;
        String label;
        Crate() { /* JPA no-arg constructor */ }
    }

    static class Locker {
        String lockerKey;
        String label;
        Locker() { /* JPA no-arg constructor */ }
    }

    static class Nameless {
        String label;
        String note;
        Nameless() { /* JPA no-arg constructor */ }
    }

    enum Size { SMALL, LARGE }

    static class Mapped {
        @Id long id;
        static final String CONSTANT = "not persisted";
        transient String cache;
        @Transient String computed;
        @Column(name = "full_label", nullable = false, unique = true, length = 40) String label;
        @Enumerated(EnumType.ORDINAL) Size size;
        Size shape;
        @ManyToOne @JoinColumn(name = "owner_fk", referencedColumnName = "code") Explicit owner;
        @ManyToOne NamedId parent;
        Object payload;
        Mapped() { /* JPA no-arg constructor */ }
    }

    private static String idOf(Class<?> entity, Class<?> keyType) {
        return RuntimeEntityModelBuilder.build(entity, keyType).id().name();
    }

    @Test
    void explicitIdWins() {
        assertThat(idOf(Explicit.class, Long.class)).isEqualTo("code");
    }

    @Test
    void fieldNamedIdOfTheKeyType() {
        assertThat(idOf(NamedId.class, String.class)).isEqualTo("id");
        assertThat(idOf(NamedId.class, null)).isEqualTo("id");
    }

    @Test
    void singleFieldOfTheKeyType() {
        assertThat(idOf(TypedKey.class, Long.class)).isEqualTo("serial");
    }

    @Test
    void entityNamePlusIdIdentifierOrKey() {
        assertThat(idOf(Parcel.class, Long.class)).isEqualTo("parcelId");
        assertThat(idOf(Crate.class, String.class)).isEqualTo("crateIdentifier");
        assertThat(idOf(Locker.class, null)).isEqualTo("lockerKey");
    }

    @Test
    void noCandidateIsRefused() {
        assertThatThrownBy(() -> RuntimeEntityModelBuilder.build(Nameless.class, String.class))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("has no @Id field")
                .hasMessageContaining("idTypeHint=java.lang.String");
        assertThatThrownBy(() -> RuntimeEntityModelBuilder.build(Nameless.class))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("idTypeHint=<none>");
    }

    @Test
    void mapsColumnsEnumsReferencesAndSkipsTransientFields() {
        EntityModel<Mapped> model = RuntimeEntityModelBuilder.build(Mapped.class);
        assertThat(model.attributes()).extracting(a -> a.name())
                .containsExactly("id", "label", "size", "shape", "owner", "parent", "payload");

        var label = model.attributes().get(1);
        assertThat(label.columnName()).isEqualTo("full_label");

        assertThat(model.attributes().get(2)).isInstanceOfSatisfying(EnumAttribute.class,
                e -> assertThat(e.storage()).isEqualTo(EnumStorage.ORDINAL));
        assertThat(model.attributes().get(3)).isInstanceOfSatisfying(EnumAttribute.class,
                e -> assertThat(e.storage()).isEqualTo(EnumStorage.STRING));

        assertThat(model.attributes().get(4)).isInstanceOfSatisfying(ReferenceAttribute.class, r -> {
            assertThat(r.columnName()).isEqualTo("owner_fk");
            assertThat(r.referencedColumn()).isEqualTo("code");
        });
        assertThat(model.attributes().get(5)).isInstanceOfSatisfying(ReferenceAttribute.class,
                r -> assertThat(r.referencedColumn()).isEqualTo("id"));
        assertThat(model.attributes().get(6)).isInstanceOf(NumericAttribute.class);
    }
}
