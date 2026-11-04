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
package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.Suspendable;
import io.vidocq.vauban.classloader.VaubanClassLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

/**
 * The application layer as a {@link Suspendable}: its class loader keeps the application jar open, and a process
 * snapshot (CRaC checkpoint) refuses an open file. {@link #suspend()} lets go of it, {@link #resume()} opens it again.
 * The first to be resumed and the last to be suspended, so that every other suspendable can still load a class.
 * Nothing to do without an application layer (the application is then on the module path, where the JDK closes and
 * reopens its jars itself).
 */
final class AppLayerSuspendable implements Suspendable {

    private final Supplier<VaubanClassLoader> loader;

    AppLayerSuspendable(Supplier<VaubanClassLoader> loader) {
        this.loader = loader;
    }

    @Override
    public void suspend() {
        var current = loader.get();
        if (current == null) {
            return;
        }
        try {
            current.releaseArchives();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot let go of the application jars", e);
        }
    }

    @Override
    public void resume() {
        var current = loader.get();
        if (current == null) {
            return;
        }
        try {
            current.reopenArchives();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open the application jars again", e);
        }
    }
}
