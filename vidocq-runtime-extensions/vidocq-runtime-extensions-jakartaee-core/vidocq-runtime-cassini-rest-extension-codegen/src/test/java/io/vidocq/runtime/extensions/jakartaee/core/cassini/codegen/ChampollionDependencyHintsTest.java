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
package io.vidocq.runtime.extensions.jakartaee.core.cassini.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The bundle's {@code dependency-codegen.properties}: {@code vidocq:generate} binds, in the dependency jars that hold
 * them, the types the application's REST resources exchange, as Champollion's processor lists and takes them.
 */
class ChampollionDependencyHintsTest {

    @Test
    void declaresTheApplicationClassesChampollionListsAndTakes() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ChampollionDependencyHintsTest.class.getClassLoader()
                .getResourceAsStream("META-INF/vidocq/dependency-codegen.properties")) {
            assertNotNull(in, "the bundle must ship META-INF/vidocq/dependency-codegen.properties");
            props.load(in);
        }
        assertEquals("Champollion", props.getProperty("brick"));
        assertEquals("META-INF/champollion/dependency-types", props.getProperty("application-classes"));
        assertEquals("champollion.jsonb.types", props.getProperty("application-classes-option"));
        assertEquals("META-INF/champollion/rest-bindings", props.getProperty("markers"));
        assertEquals("META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding", props.getProperty("merge"));
    }
}
