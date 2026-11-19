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
package io.vidocq.humboldt.cdi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code @WithSpan} interceptor and the telemetry producers are CDI beans. The jar is an
 * explicit bean archive so a container that does not scan implicit archives (Weld SE by default)
 * still discovers them (humboldt#23).
 */
class BeanArchiveTest {

    @Test
    void isAnAnnotatedBeanArchive() throws IOException {
        Path beansXml = Path.of("target/classes/META-INF/beans.xml");
        assertTrue(Files.isRegularFile(beansXml), () -> "missing " + beansXml);
        assertTrue(Files.readString(beansXml).contains("bean-discovery-mode=\"annotated\""),
                () -> beansXml + " must declare bean-discovery-mode=\"annotated\"");
    }
}
