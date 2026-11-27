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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vidocq#831: a link may name a name the pools answer, but a link to a name nothing declares or answers still fails
 * the start, and the failure names both the link and what it links to.
 */
class NamingUnknownLinkModulePathTest {

    private static final String KEY = "vidocq.naming.comp/env/jdbc/unknown";

    private VidocqBootstrap bootstrap;

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @Test
    void a_link_to_a_name_no_pool_answers_fails_the_start() {
        System.setProperty(KEY, "java:comp/env/nothing");
        bootstrap = VidocqBootstrap.create().configure();

        var failure = assertThrows(RuntimeException.class, bootstrap::start);

        String message = messages(failure);
        assertTrue(message.contains("java:comp/env/jdbc/unknown") && message.contains("java:comp/env/nothing"), message);
    }

    /** The messages of a failure and its causes. */
    private static String messages(Throwable failure) {
        var messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
