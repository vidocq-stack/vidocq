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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq doctor} on a project that takes CRaC checkpoints: the JDK must ship jdk.crac, the OS must be Linux. */
class CracDoctorTest {

    @Test
    void aProjectWithoutTheCracExtensionIsNotChecked() {
        assertEquals(Optional.empty(), ProjectDoctor.checkCrac(Set.of("cassini-rest"), false, "Mac OS X"));
    }

    @Test
    void aJdkWithoutJdkCracFailsTheCheck() {
        Diagnostic d = ProjectDoctor.checkCrac(Set.of("crac"), false, "Linux").orElseThrow();

        assertEquals(Diagnostic.Status.FAIL, d.status());
        assertTrue(d.detail().contains("jdk.crac"), d.detail());
        assertTrue(d.hint().contains("Zulu CRaC"), d.hint());
    }

    @Test
    void aCracJdkOnLinuxPasses() {
        assertEquals(Diagnostic.Status.OK,
                ProjectDoctor.checkCrac(Set.of("crac"), true, "Linux").orElseThrow().status());
    }

    @Test
    void aCracJdkOffLinuxWarnsThatCheckpointsNeedLinux() {
        Diagnostic d = ProjectDoctor.checkCrac(Set.of("crac"), true, "Mac OS X").orElseThrow();

        assertEquals(Diagnostic.Status.WARN, d.status());
        assertTrue(d.detail().contains("Linux"), d.detail());
    }
}
