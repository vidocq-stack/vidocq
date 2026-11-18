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
package io.vidocq.cervantes.tck.arquillian;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import java.util.Set;
import org.testng.annotations.Test;

import static org.testng.Assert.assertSame;

/**
 * The harness's bean provider hands Cassini the release of a resource for a request as its delegate gives it, so a
 * {@code @Dependent} resource of the TCK ends with its request (CERV-013, Cassini's CASSINI-062).
 */
public class CervantesHarnessBeanProviderTest {

    /** A delegate that serves {@link #RESOURCE} for a request with {@link #RELEASE}. */
    private static final Object RESOURCE = new Object();
    private static final Runnable RELEASE = () -> { };

    @Test
    public void aResourceForARequestIsTheDelegatesWithItsRelease() {
        var harness = new CervantesJwtDeployableContainer.CervantesHarnessBeanProvider(new Releasing(),
                new JsonWebTokenContext());

        var served = harness.getRequestBean(Object.class);

        assertSame(served.instance(), RESOURCE, "the resource served");
        assertSame(served.release(), RELEASE, "what ends it");
    }

    private static final class Releasing implements BeanProvider {
        @Override
        public <T> T getBean(Class<T> type) {
            return type.cast(RESOURCE);
        }

        @Override
        public Set<Class<?>> getResourceClasses() {
            return Set.of();
        }

        @Override
        public <T> RequestInstance<T> getRequestBean(Class<T> type) {
            return new RequestInstance<>(type.cast(RESOURCE), RELEASE);
        }
    }
}
