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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-written plugin.xml must declare each parameter of vidocq:jlink, or Maven never sets it. */
class PluginDescriptorJlinkTest {

    @Test
    void theMergeFlagIsDeclaredWithItsProperty() throws Exception {
        String xml = Files.readString(Path.of("target", "classes", "META-INF", "maven", "plugin.xml"));
        int jlink = xml.indexOf("<goal>jlink</goal>");
        assertTrue(jlink > 0, "no jlink mojo");
        int end = xml.indexOf("</mojo>", jlink);
        String mojo = xml.substring(jlink, end);

        assertTrue(mojo.contains("<name>mergeSharedResources</name>"), "parameter not declared");
        assertTrue(mojo.contains("${vidocq.jlink.mergeSharedResources}</mergeSharedResources>"),
                "property not bound");
    }
}
