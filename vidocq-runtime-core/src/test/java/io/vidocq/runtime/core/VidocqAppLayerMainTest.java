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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code -Dvidocq.app.main} does to the caller: the application's own failure must reach
 * it as the application threw it, and a launch problem must be told apart from one (vidocq#88).
 *
 * <p>No layer is installed here, so the main classes are loaded from the test class path, in
 * the unnamed module. The named-module path — where the package has to be opened — is covered
 * by {@code VidocqAppLayerMainProcessTest}, in a real JVM.
 */
@DisplayName("vidocq#88 — what the layer reports when it runs the application main")
class VidocqAppLayerMainTest {

    static final List<String> CALLS = new ArrayList<>();

    static final RuntimeException UNCHECKED = new IllegalStateException("the application said no");

    @AfterEach
    void clear() {
        System.clearProperty(VidocqAppLayer.APP_MAIN_PROPERTY);
        CALLS.clear();
    }

    public static final class Runs {
        public static void main(String[] args) {
            CALLS.add("ran with " + String.join(" ", args));
        }
    }

    public static final class ThrowsUnchecked {
        public static void main(String[] args) {
            throw UNCHECKED;
        }
    }

    public static final class ThrowsChecked {
        public static void main(String[] args) throws Exception {
            throw new Exception("checked, from the application");
        }
    }

    public static final class NoMain {}

    @Test
    @DisplayName("without the property there is nothing to run, and the boot goes on")
    void noPropertyNoRun() {
        assertFalse(VidocqAppLayer.runAppMainIfConfigured(new String[0]),
                "an absent property must not be an error: the runtime boots on its own");

        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, "   ");
        assertFalse(VidocqAppLayer.runAppMainIfConfigured(new String[0]), "a blank property is an absent one");
        assertEquals(List.of(), CALLS);
    }

    @Test
    @DisplayName("the configured main runs, with the arguments")
    void theMainRuns() {
        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, Runs.class.getName());

        assertTrue(VidocqAppLayer.runAppMainIfConfigured(new String[] {"--port", "9090"}));
        assertEquals(List.of("ran with --port 9090"), CALLS);
    }

    @Test
    @DisplayName("an unchecked failure of the application is the exception it threw, not a wrapper")
    void theApplicationsUncheckedFailureIsItsOwn() {
        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, ThrowsUnchecked.class.getName());

        var thrown = assertThrows(IllegalStateException.class,
                () -> VidocqAppLayer.runAppMainIfConfigured(new String[0]));

        assertSame(UNCHECKED, thrown, "the application's own exception must not be re-wrapped");
    }

    @Test
    @DisplayName("a checked failure keeps its cause under a message that names the class")
    void theApplicationsCheckedFailureKeepsItsCause() {
        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, ThrowsChecked.class.getName());

        var thrown = assertThrows(IllegalStateException.class,
                () -> VidocqAppLayer.runAppMainIfConfigured(new String[0]));

        assertTrue(thrown.getMessage().startsWith("Cannot run application main "), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(ThrowsChecked.class.getName()), thrown.getMessage());
        assertEquals("checked, from the application", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("a launch problem says what is wrong with the main, not that the class could not be run")
    void aLaunchProblemIsToldApartFromAnApplicationFailure() {
        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, NoMain.class.getName());
        var noMain = assertThrows(IllegalStateException.class,
                () -> VidocqAppLayer.runAppMainIfConfigured(new String[0]));
        assertTrue(noMain.getMessage().contains("it has no main method"), noMain.getMessage());
        assertTrue(noMain.getMessage().contains("Java 25 accepts main(String[]) or main()"), noMain.getMessage());

        System.setProperty(VidocqAppLayer.APP_MAIN_PROPERTY, "acme.NotOnTheClassPath");
        var missingClass = assertThrows(IllegalStateException.class,
                () -> VidocqAppLayer.runAppMainIfConfigured(new String[0]));
        assertTrue(missingClass.getMessage().contains("acme.NotOnTheClassPath"), missingClass.getMessage());
        assertTrue(missingClass.getCause() instanceof ClassNotFoundException,
                "a class that is not there is reported as such: " + missingClass.getCause());
    }
}
