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
package io.vidocq.runtime.arquillian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeploymentApplicationLayerTest {

    private final DeploymentApplicationLayer layer = new DeploymentApplicationLayer();

    @AfterEach
    void clear() {
        DeploymentApplicationLayer.clear();
    }

    @Test
    void noArchiveDeployedNamesNoRoot() {
        assertEquals(List.of(), layer.roots());
    }

    @Test
    void theRootsOfTheDeployedArchiveAreACopy() throws Exception {
        URL classes = URI.create("file:/tmp/war/WEB-INF/classes/").toURL();
        List<URL> roots = new ArrayList<>(List.of(classes));

        DeploymentApplicationLayer.roots(roots);
        roots.clear();

        List<URL> deployed = layer.roots();
        assertEquals(List.of(classes), deployed);
        assertThrows(UnsupportedOperationException.class, deployed::clear);
    }

    @Test
    void anUndeployedArchiveLeavesNoRoot() throws Exception {
        DeploymentApplicationLayer.roots(List.of(URI.create("file:/tmp/war/WEB-INF/classes/").toURL()));

        DeploymentApplicationLayer.clear();

        assertEquals(List.of(), layer.roots());
    }
}
