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
package io.vidocq.vauban.classloader;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The prefixes {@value Launch#KEEP_PROPERTY} lists: comma-separated, blanks around them and empty ones dropped. */
@DisplayName("Launch — the extra keep prefixes")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LaunchKeptPrefixesTest {

    @AfterEach
    void clear() {
        System.clearProperty(Launch.KEEP_PROPERTY);
    }

    @Test
    @DisplayName("no property, no prefix")
    void none() {
        assertEquals(List.of(), Launch.extraKeptPrefixes());
    }

    @Test
    @DisplayName("each prefix stripped, the empty ones dropped, a trailing comma included")
    void listed() {
        System.setProperty(Launch.KEEP_PROPERTY, " com.acme , ,org.example,");

        assertEquals(List.of("com.acme", "org.example"), Launch.extraKeptPrefixes());
    }
}
