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
package io.vidocq.mansart.data.tests;

import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-027 — an index entry whose generated implementation is gone (deleted, or left behind by an incremental
 * build) must not cost the application its repository: the interface goes to the runtime path, as if it had no entry.
 */
class StaleRepositoryIndexTest {

    @Test
    void aRepositoryWhoseIndexedImplementationIsGoneIsServedAtRunTime(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("META-INF/mansart-repositories.list");
        Files.createDirectories(index.getParent());
        Files.writeString(index, DefaultNoteLog.class.getName() + "=" + DefaultNoteLog.class.getName() + "GoneImpl\n");

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader withStaleIndex = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                StaleRepositoryIndexTest.class.getClassLoader())) {
            thread.setContextClassLoader(withStaleIndex);
            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(io.vidocq.mansart.data.cdi.MansartDataExtension.class)
                    .addBeanClass(io.vidocq.mansart.data.cdi.MansartRuntimeProducer.class)
                    .addBeanClass(RoutingDataSources.class)
                    .addBeanClass(RoutedNote.class)
                    .addBeanClass(DefaultNoteLog.class)
                    .build()) {
                assertThat(container.select(DefaultNoteLog.class)).isNotNull();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
