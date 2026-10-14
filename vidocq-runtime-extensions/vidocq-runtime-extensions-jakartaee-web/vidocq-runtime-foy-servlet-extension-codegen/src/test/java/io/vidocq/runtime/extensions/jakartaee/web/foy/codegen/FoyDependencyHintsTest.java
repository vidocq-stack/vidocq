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
package io.vidocq.runtime.extensions.jakartaee.web.foy.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.foy.processor.DependencyHints;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The bundle's {@code dependency-codegen.properties} must stay in step with the hints foy-processor itself declares.
 */
class FoyDependencyHintsTest {

    private static Properties load() throws IOException {
        try (InputStream in = FoyDependencyHintsTest.class.getClassLoader()
                .getResourceAsStream("META-INF/vidocq/dependency-codegen.properties")) {
            assertNotNull(in, "the bundle must ship META-INF/vidocq/dependency-codegen.properties");
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    private static List<String> list(Properties props, String key) {
        return Arrays.asList(props.getProperty(key, "").split(","));
    }

    @Test
    void declaresTheBrickAndMatchesFoyProcessorHints() throws IOException {
        Properties props = load();
        assertEquals("Foy", props.getProperty("brick"));
        assertEquals(DependencyHints.RESOURCE_HINTS, list(props, "resources"));
        assertEquals(DependencyHints.CLASS_BYTE_HINTS, list(props, "class-bytes"));
        assertTrue(list(props, "markers").contains(DependencyHints.MARKER));
    }

    @Test
    void theClassIndexIsWholeJarNotMerged() throws IOException {
        Properties props = load();
        // The processors see only the hinted classes of a dependency jar: an index of them would be partial, and
        // Foy's run-time scanner skips a jar that ships one.
        assertTrue(list(props, "whole-jar").contains("META-INF/foy/class-index.list"));
        assertTrue(!list(props, "merge").contains("META-INF/foy/class-index.list"));
        assertTrue(list(props, "markers").contains("META-INF/foy/class-index.list"));
    }

    @Test
    void mergedResourcesAreAlsoMarkers() throws IOException {
        Properties props = load();
        assertTrue(list(props, "markers").containsAll(list(props, "merge")));
    }
}
