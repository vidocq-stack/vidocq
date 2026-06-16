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
package io.vidocq.runtime.cli.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildTypeTest {

    @Test
    void nullOrBlankTokenIsPackage() {
        assertEquals(BuildType.PACKAGE, BuildType.fromToken(null));
        assertEquals(BuildType.PACKAGE, BuildType.fromToken(""));
        assertEquals(BuildType.PACKAGE, BuildType.fromToken("   "));
    }

    @Test
    void knownTokensMap() {
        assertEquals(BuildType.PACKAGE, BuildType.fromToken("package"));
        assertEquals(BuildType.JLINK, BuildType.fromToken("jlink"));
        assertEquals(BuildType.JPACKAGE, BuildType.fromToken("jpackage"));
        assertEquals(BuildType.DOCKER, BuildType.fromToken("docker"));
    }

    @Test
    void unknownTokenThrows() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> BuildType.fromToken("native"));
        assertEquals(true, ex.getMessage().contains("native"));
    }

    @Test
    void packageRunsOnlyPackagePhase() {
        assertEquals(List.of("package"), BuildType.PACKAGE.goals());
    }

    @Test
    void flavoursLayerGoalOnPackage() {
        assertEquals(List.of("package", "vidocq:jlink"), BuildType.JLINK.goals());
        assertEquals(List.of("package", "vidocq:jpackage"), BuildType.JPACKAGE.goals());
        assertEquals(List.of("package", "vidocq:docker"), BuildType.DOCKER.goals());
    }

    @Test
    void goalsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> BuildType.JLINK.goals().add("x"));
    }
}
