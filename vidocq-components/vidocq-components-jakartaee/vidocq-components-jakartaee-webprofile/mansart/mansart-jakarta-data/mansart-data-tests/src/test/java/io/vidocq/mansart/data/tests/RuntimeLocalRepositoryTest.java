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
import io.vidocq.mansart.data.core.MansartDataException;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository declared inside a method (mansart#85, case e): no annotation processor is ever handed a local type,
 * so no implementation can be generated, and the run-time path refuses it with a message that says so instead of
 * failing on an access check deep in the generator.
 */
class RuntimeLocalRepositoryTest {

    @Test
    void aLocalRepositoryIsRefusedWithAClearMessage() {
        @Repository
        interface Pets extends BasicRepository<Author, Long> {
        }
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-local-repository;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        MansartData data = MansartData.builder().dataSource(ds).build();

        assertThatThrownBy(() -> data.runtimeRepository(Pets.class))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining(Pets.class.getName())
                .hasMessageContaining("is a local interface")
                .hasMessageContaining("no annotation processor can see it")
                .hasMessageContaining("declare it as a member or a top-level interface");
    }
}
