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

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link EntityModels#of}: the model Mansart itself uses for an entity, read by tools such as Vidocq's dev console. */
class EntityModelsOfTest {

    @Test
    void anEntityWithAGeneratedMetamodelGetsThatMetamodel() {
        assertThat(EntityModels.of(Book.class)).isSameAs(_Book.$MODEL);
    }

    @Test
    void anEntityWithoutMetamodelGetsAModelBuiltAtRunTimeOnce() throws Exception {
        assertThatThrownBy(() -> Class.forName("io.vidocq.mansart.data.tests._UnscannedGadget"))
                .isInstanceOf(ClassNotFoundException.class);

        EntityModel<UnscannedGadget> model = EntityModels.of(UnscannedGadget.class);

        assertThat(model.entityClass()).isEqualTo(UnscannedGadget.class);
        assertThat(model.tableName()).isEqualTo("unscanned_gadgets");
        assertThat(model.id().name()).isEqualTo("id");
        assertThat(model.attributes()).extracting(Attribute::name).containsExactly("id", "label");
        assertThat(EntityModels.of(UnscannedGadget.class)).isSameAs(model);
    }

    @Test
    void aClassThatIsNoEntityThrowsWhatTheLookupThrows() {
        assertThatThrownBy(() -> EntityModels.of(NotAnEntity.class))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("has no @Id field");
    }
}
