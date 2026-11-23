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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-memory identity store keeps its callers and their passwords in the code of the application: the start says so
 * once, at WARNING, so that it does not reach production unnoticed (PLAN.md section 6, BUG-20261123-62).
 */
class InMemoryStoreWarningTest {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(InMemoryIdentityStoreCreator.LOGGER_NAME);
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @BeforeEach
    void listen() {
        logger.addHandler(capture);
    }

    @AfterEach
    void stopListening() {
        logger.removeHandler(capture);
    }

    private static VaubanContainer start(Class<?>... beans) {
        VaubanContainerBuilder builder = VaubanContainer.builder().addBeanClass(KerckhoffsExtension.class)
                .addBeanClass(IdentityStoreStartCheck.class);
        for (Class<?> bean : beans) builder.addBeanClass(bean);
        return builder.build();
    }

    private List<String> warnings() {
        return records.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    @Test
    void an_application_without_in_memory_store_is_not_warned() {
        try (var _ = start(BasicApplication.class)) {
            assertTrue(warnings().isEmpty(), warnings().toString());
        }
    }

    @Test
    void the_start_warns_once_of_each_in_memory_store_and_names_its_declaration() {
        try (var _ = start(MemoryApplication.class)) {
            var warnings = warnings();

            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains(MemoryApplication.class.getName()), warnings.getFirst());
            assertTrue(warnings.getFirst().contains("production"), warnings.getFirst());
        }
    }
}
