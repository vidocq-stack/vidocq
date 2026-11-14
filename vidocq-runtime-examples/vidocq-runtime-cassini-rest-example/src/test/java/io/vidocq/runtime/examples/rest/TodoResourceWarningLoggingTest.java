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
package io.vidocq.runtime.examples.rest;

import io.vidocq.runtime.junit.VidocqApplication;
import io.vidocq.runtime.junit.VidocqTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An application whose logging configuration keeps WARNING and above starts under {@code @VidocqTest} as any other:
 * the start reads the port from a file the listener writes, not from the INFO line it logs (BUG-20261114-09). The
 * path is relative to the working directory of the tests, the module's directory under Surefire.
 */
@VidocqTest(properties = "java.util.logging.config.file=src/test/resources/warning-logging.properties")
class TodoResourceWarningLoggingTest {

    @Test
    void theApplicationStartsAndAnswersWithoutItsInfoLines(VidocqApplication application) {
        assertFalse(application.log().contains("Chappe listener 'default' started"), application.log());

        assertEquals(200, application.get("/api/todos").status(), application.log());
    }
}
