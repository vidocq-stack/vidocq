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
package io.vidocq.dirac.cdi.internal;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.util.AnnotationLiteral;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Deprecated {@code @RegistryType} selections alias the registry of the
 * same-named scope (MP Metrics 5.1 TCK
 * {@code testMetricRegistryScopeDeprecatedRegistryType} asserts
 * {@code getScope()}), and never return {@code null} even when the legacy
 * qualifier is the first to touch a scope — the previous plain {@code .get()}
 * returned {@code null} until something else created the registry. Selection
 * with an explicit qualifier is exercised programmatically, the exact path the
 * Fault Tolerance TCK's {@code MetricRegistryProvider} uses.
 */
@SuppressWarnings("deprecation")
class LegacyRegistryTypeUnificationTest {

    static final class RegistryTypeLiteral extends AnnotationLiteral<RegistryType> implements RegistryType {
        private final MetricRegistry.Type type;

        RegistryTypeLiteral(MetricRegistry.Type type) {
            this.type = type;
        }

        @Override
        public MetricRegistry.Type type() {
            return type;
        }
    }

    @Test
    void legacyRegistryTypeSelectionsAliasTheSameNamedScopes() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(MetricRegistryProducerBean.class)
                .initialize()) {

            MetricRegistry byDefault = container.select(MetricRegistry.class).get();
            MetricRegistry byBase = container.select(MetricRegistry.class,
                    new RegistryTypeLiteral(MetricRegistry.Type.BASE)).get();
            MetricRegistry byApplication = container.select(MetricRegistry.class,
                    new RegistryTypeLiteral(MetricRegistry.Type.APPLICATION)).get();
            MetricRegistry byVendor = container.select(MetricRegistry.class,
                    new RegistryTypeLiteral(MetricRegistry.Type.VENDOR)).get();

            assertNotNull(byBase, "legacy BASE selection must never resolve to a null registry");
            assertNotNull(byVendor, "legacy VENDOR selection must never resolve to a null registry");
            assertEquals(MetricRegistry.BASE_SCOPE, byBase.getScope());
            assertEquals(MetricRegistry.VENDOR_SCOPE, byVendor.getScope());
            assertEquals(MetricRegistry.APPLICATION_SCOPE, byApplication.getScope());
            assertSame(byDefault, byApplication,
                    "legacy APPLICATION selection must alias the @Default application registry");

            var producer = container.select(MetricRegistryProducerBean.class).get();
            assertSame(byBase, producer.baseRegistry(),
                    "legacy BASE selection must alias the base-scope registry");
        }
    }
}
