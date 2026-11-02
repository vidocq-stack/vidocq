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
package io.vidocq.ravel.cdi.internal;

import io.vidocq.ravel.spi.GeneratedConfigProperties;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The creator hands a generated factory the prefix MicroProfile Config 3.1 §6.4 defines. */
class GeneratedConfigPropertiesTest {

    private static GeneratedConfigProperties factory(String classPrefix) {
        return new GeneratedConfigProperties() {
            @Override public String typeName() { return "com.example.Settings"; }
            @Override public String classPrefix() { return classPrefix; }
            @Override public Object create(String prefix, Config config) { return "prefix=" + prefix; }
        };
    }

    @Test
    void theInjectionPointPrefixWins() {
        assertEquals("prefix=ip", ConfigPropertiesSyntheticCreator.createWith(factory("cls"), "ip", null));
    }

    @Test
    void theClassPrefixAppliesWhenTheInjectionPointHasNone() {
        assertEquals("prefix=cls", ConfigPropertiesSyntheticCreator.createWith(
                factory("cls"), ConfigProperties.UNCONFIGURED_PREFIX, null));
    }

    @Test
    void noPrefixAtAllMeansAnEmptyOne() {
        assertEquals("prefix=", ConfigPropertiesSyntheticCreator.createWith(
                factory(ConfigProperties.UNCONFIGURED_PREFIX), ConfigProperties.UNCONFIGURED_PREFIX, null));
    }
}
