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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What Eclipse's Maven integration (m2e: Eclipse, VS Code's Java extension) does with the goals
 * {@code vidocq-runtime-parent} binds before {@code package}. Without a mapping it flags each of them on the pom,
 * "Plugin execution not covered by lifecycle configuration"; both are command-line checks, so the IDE ignores them.
 */
class LifecycleMappingMetadataTest {

    @Test
    void m2eIgnoresTheChecksTheApplicationParentBinds() throws Exception {
        Document metadata;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/m2e/lifecycle-mapping-metadata.xml")) {
            assertNotNull(in, "the plugin carries no m2e lifecycle mapping");
            metadata = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        List<String> ignored = new ArrayList<>();
        NodeList executions = metadata.getElementsByTagName("pluginExecution");
        for (int i = 0; i < executions.getLength(); i++) {
            Element execution = (Element) executions.item(i);
            if (execution.getElementsByTagName("ignore").getLength() == 0) {
                continue;
            }
            NodeList goals = execution.getElementsByTagName("goal");
            for (int g = 0; g < goals.getLength(); g++) {
                ignored.add(goals.item(g).getTextContent().strip());
            }
        }
        assertEquals(List.of("checkpom", "check-module-info"), ignored);
    }
}
