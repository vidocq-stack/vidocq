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
package io.vidocq.runtime.it.naming;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NoInitialContextException;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * vidocq#297: a context holds no container. One taken from a running Vidocq answers from the Vidocq running
 * at each lookup, fails cleanly while none runs, and the stopped runtime leaves the JVM default as it found it.
 */
class NamingRestartModulePathTest {

    private VidocqBootstrap bootstrap;

    @AfterEach
    void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    private static DataSource auditBean() {
        return CDI.current().select(DataSource.class, NamedLiteral.of("audit")).get();
    }

    @Test
    void a_context_answers_from_the_runtime_running_at_each_lookup() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
        Context context = new InitialContext();
        Object first = context.lookup("java:comp/env/jdbc/audit");
        bootstrap.shutdown();

        assertThrows(NoInitialContextException.class, () -> context.lookup("java:comp/env/jdbc/audit"));
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY), "the stopped runtime removed its default");

        bootstrap = VidocqBootstrap.create().configure().start();
        Object second = context.lookup("java:comp/env/jdbc/audit");

        assertSame(auditBean(), second);
        assertNotSame(first, second, "the pool of the new runtime");
    }
}
