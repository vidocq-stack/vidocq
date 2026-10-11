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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServletSectionTest {

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void contributesTheServletSection() {
        FoyExtension extension = new FoyExtension((l, p, h) -> {});

        assertEquals("servlet", extension.id());
        assertEquals("Servlet (Foy)", extension.title());
    }

    @Test
    void inactiveWithoutDeployment() {
        RecordingSection section = new RecordingSection();

        new FoyExtension((l, p, h) -> {}).contribute(null, section);

        assertEquals("inactive: no web component", section.summary);
    }

    @Test
    void summarizesTheLiveDeployments(@TempDir Path root) throws Exception {
        FoyExtension extension = new FoyExtension((l, p, h) -> {});
        extension.configure(new MapConfiguration(Map.of("vidocq.servlet.session-timeout", "120")));
        extension.start(null, FoyExtensionTest.config(Map.of()),
                FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML));
        RecordingSection section = new RecordingSection();

        extension.contribute(null, section);

        assertEquals("1 servlet(s), 0 filter(s) at /", section.summary);
        assertEquals("default", section.rows.get("Listener"));
        assertEquals("2 min", section.rows.get("Session timeout"));
    }
}
