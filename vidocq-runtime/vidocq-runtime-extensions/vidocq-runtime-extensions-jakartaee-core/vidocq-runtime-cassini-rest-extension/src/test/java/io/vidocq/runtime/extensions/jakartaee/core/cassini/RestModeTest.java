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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the application's REST resources are served: by Chappe, or as a Servlet of Foy. */
class RestModeTest {

    @Test
    void withFoyAndTheServletAdapterTheAutomaticModeIsTheServlet() {
        assertEquals(RestMode.SERVLET, RestMode.resolve(null, true, true));
        assertEquals(RestMode.SERVLET, RestMode.resolve("", true, true));
        assertEquals(RestMode.SERVLET, RestMode.resolve(" auto ", true, true));
    }

    @Test
    void withoutFoyOrWithoutTheAdapterTheAutomaticModeIsChappe() {
        assertEquals(RestMode.CHAPPE, RestMode.resolve("auto", true, false));
        assertEquals(RestMode.CHAPPE, RestMode.resolve("auto", false, true));
        assertEquals(RestMode.CHAPPE, RestMode.resolve(null, false, false));
    }

    @Test
    void chappeCanBeAskedEvenWhereTheServletWouldDo() {
        assertEquals(RestMode.CHAPPE, RestMode.resolve("chappe", true, true));
        assertEquals(RestMode.CHAPPE, RestMode.resolve("CHAPPE", false, false));
    }

    @Test
    void theServletAskedAndPossibleIsTheServlet() {
        assertEquals(RestMode.SERVLET, RestMode.resolve("servlet", true, true));
    }

    @Test
    void theServletAskedWhereItCannotRunStopsTheStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RestMode.resolve("servlet", true, false));
        assertTrue(e.getMessage().contains("vidocq.rest.mode=servlet"), e.getMessage());
        assertTrue(e.getMessage().contains("Foy"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> RestMode.resolve("servlet", false, true));
        assertTrue(e.getMessage().contains("cassini-servlet"), e.getMessage());
    }

    @Test
    void anUnknownModeIsRefusedAndNamesTheOnesThatExist() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RestMode.resolve("jersey", true, true));
        assertTrue(e.getMessage().contains("auto") && e.getMessage().contains("chappe") && e.getMessage().contains("servlet"),
                e.getMessage());
    }

    @Test
    void aClassTheLoaderSeesIsPresentAndAnotherIsNot() {
        ClassLoader loader = RestModeTest.class.getClassLoader();
        assertTrue(RestMode.present(loader, "java.lang.String"));
        assertTrue(RestMode.present(loader, RestMode.class.getName()));
        assertFalse(RestMode.present(loader, "io.vidocq.nothing.Here"));
    }
}
