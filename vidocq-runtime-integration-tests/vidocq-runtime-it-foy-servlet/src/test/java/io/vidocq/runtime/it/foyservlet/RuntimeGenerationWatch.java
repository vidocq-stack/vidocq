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
package io.vidocq.runtime.it.foyservlet;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The warnings Vauban gives when it generates a class at run time instead of at build: a client proxy or an
 * intercepted subclass the build did not generate. Vauban's {@code RuntimeGeneration} announces each through the
 * {@code System.Logger} of its class name, which the JDK routes to the JUL logger of that name.
 * {@link RuntimeGenerationWatchTest} forces such a class, so a test that finds no warning here has looked in the
 * right place.
 */
final class RuntimeGenerationWatch extends Handler implements AutoCloseable {

    /** The title of Vauban's warning. */
    static final String FALLBACK = "RUNTIME CODE GENERATION FALLBACK";

    private static final String RUNTIME_GENERATION_LOGGER = "io.vidocq.vauban.core.container.RuntimeGeneration";

    /** Kept strongly: JUL holds its loggers weakly, and the handler would go with a collected one. */
    private final Logger logger = Logger.getLogger(RUNTIME_GENERATION_LOGGER);
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    private RuntimeGenerationWatch() {
        logger.addHandler(this);
    }

    /** Listens to Vauban's run-time generation warnings: call it before the boot. */
    static RuntimeGenerationWatch watch() {
        return new RuntimeGenerationWatch();
    }

    /** The warnings given since {@link #watch()}, in order. */
    List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public void publish(LogRecord logRecord) {
        if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
            warnings.add(logRecord.getMessage());
        }
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
