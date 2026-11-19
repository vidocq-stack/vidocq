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
package io.vidocq.runtime.examples.mansart;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.data.repository.BasicRepository;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANSART-068 on frozen extensions: a repository on a named data store is found by its parameterized supertype,
 * {@code BasicRepository<AuditEntry, Long>}, when the start loads the record {@code vidocq:generate} wrote instead
 * of running the build compatible extensions.
 *
 * <p>{@link AuditEntryRepository} is on the {@code audit} data store, so it is a synthetic bean of Mansart Data's
 * extension, not a class bean. Its bean types are what the extension declared at build; the record must keep the
 * parameterized ones. Mansart's own tests cover the extensions run at start.
 *
 * <p>The start runs under {@code vidocq.startup.require-frozen-extensions=true}: it fails if the record is refused
 * or missing, so this test cannot pass on extensions that ran at start. The lookup is programmatic: a bean declared
 * in the test sources is not in the registry of the main compilation (see {@link TransactionalRollbackTest}).
 *
 * <p>Launch mode and port: as {@link TransactionalRollbackTest} does, and for the same reasons.
 */
class RoutedRepositorySupertypeFrozenTest {

    private static final String LAUNCH_MODE_KEY = "vidocq.launch.mode";
    private static final String PORT_KEY = "vidocq.chappe.listener.default.port";
    private static final String REQUIRE_FROZEN_KEY = "vidocq.startup.require-frozen-extensions";

    @Test
    void aRoutedRepositoryIsFoundByItsParameterizedSupertypeOnFrozenExtensions() {
        String previousLaunchMode = System.getProperty(LAUNCH_MODE_KEY);
        String previousPort = System.getProperty(PORT_KEY);
        String previousRequire = System.getProperty(REQUIRE_FROZEN_KEY);
        System.setProperty(LAUNCH_MODE_KEY, "test");
        System.setProperty(PORT_KEY, "0");
        System.setProperty(REQUIRE_FROZEN_KEY, "true");
        VidocqBootstrap bootstrap;
        try {
            bootstrap = VidocqBootstrap.create().configure().start();
        } finally {
            restore(LAUNCH_MODE_KEY, previousLaunchMode);
            restore(PORT_KEY, previousPort);
            restore(REQUIRE_FROZEN_KEY, previousRequire);
        }
        try {
            Instance<BasicRepository<AuditEntry, Long>> bySupertype =
                    CDI.current().select(new TypeLiteral<BasicRepository<AuditEntry, Long>>() {});

            assertTrue(bySupertype.isResolvable(),
                    "the routed repository must be a bean of type BasicRepository<AuditEntry, Long> (MANSART-068)");
            BasicRepository<AuditEntry, Long> audit = bySupertype.get();
            long before = CDI.current().select(AuditEntryRepository.class).get().count();
            audit.save(new AuditEntry("frozen-supertype-lookup"));

            assertEquals(before + 1, CDI.current().select(AuditEntryRepository.class).get().count(),
                    "a row saved through the supertype must reach the audit data store");
        } finally {
            bootstrap.shutdown();
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
