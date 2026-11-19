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

import io.vidocq.mansart.data.cdi.MansartDataExtension;
import io.vidocq.mansart.data.cdi.MansartRuntimeProducer;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code @Repository(provider = "…")} declared for another provider is not implemented by Mansart's run-time path
 * either; when nothing else implements it, the deployment error says which provider is missing (mansart#85).
 */
class ForeignProviderRepositoryTest {

    @Repository(provider = "Hibernate")
    public interface ForeignAuthors extends BasicRepository<Author, Long> {
    }

    @Singleton
    public static class Reader {
        @Inject
        ForeignAuthors authors;
    }

    @Test
    void aRepositoryOfAnotherProviderIsNotServedByMansart() {
        assertThatThrownBy(() -> VaubanContainer.builder()
                .addBeanClass(MansartDataExtension.class)
                .addBeanClass(MansartRuntimeProducer.class)
                .addBeanClass(RoutingDataSources.class)
                .addBeanClass(Author.class)
                .addBeanClass(ForeignAuthors.class)
                .addBeanClass(Reader.class)
                .build()
                .close())
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining(ForeignAuthors.class.getName())
                .hasMessageContaining("provider \"Hibernate\"")
                .hasMessageNotContaining("vidocq:generate");
    }
}
