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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.container.ContainerScanner.BeanDiscoveryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** How the {@code bean-discovery-mode} attribute of a {@code beans.xml} is read. */
class BeanDiscoveryModeAttributeTest {

    @Test
    @DisplayName("the value is read between double or single quotes, the spaces around it ignored")
    void quotesAndSpaces() {
        assertEquals(BeanDiscoveryMode.ALL, BeanDiscoveryMode.of("<beans bean-discovery-mode=\"all\"/>"));
        assertEquals(BeanDiscoveryMode.NONE, BeanDiscoveryMode.of("<beans bean-discovery-mode = 'none' />"));
        assertEquals(BeanDiscoveryMode.ALL, BeanDiscoveryMode.of("<beans bean-discovery-mode=\"  all \t\"/>"));
        assertEquals(BeanDiscoveryMode.ANNOTATED, BeanDiscoveryMode.of("<beans bean-discovery-mode='annotated'/>"));
    }

    @Test
    @DisplayName("an unknown value is refused, quotes that do not pair leave the default")
    void unknownAndUnpaired() {
        var wrong = assertThrows(IllegalArgumentException.class,
                () -> BeanDiscoveryMode.of("<beans bean-discovery-mode=\" sometimes \"/>"));
        assertEquals("invalid bean-discovery-mode \"sometimes\" (expected all, annotated or none)", wrong.getMessage());
        assertEquals(BeanDiscoveryMode.ANNOTATED, BeanDiscoveryMode.of("<beans bean-discovery-mode=\"all'/>"));
        assertEquals(BeanDiscoveryMode.ANNOTATED, BeanDiscoveryMode.of("<beans/>"));
    }

    @Test
    @DisplayName("a long run of spaces after an unclosed quote is read in linear time (Sonar java:S5852)")
    void noPolynomialBacktracking() {
        var beansXml = "<beans bean-discovery-mode=\"" + " ".repeat(50_000) + "/>";
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertEquals(BeanDiscoveryMode.ANNOTATED, BeanDiscoveryMode.of(beansXml)));
    }
}
