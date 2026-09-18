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
package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportView;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Context provided to extensions during the {@link VidocqExtension#onStart} phase.
 *
 * <p>Provides access to the CDI container and configuration.</p>
 */
public interface ExtensionContext {

    /**
     * The initialized Vauban CDI container.
     */
    VaubanContainer container();

    /**
     * The legacy Vidocq configuration.
     *
     * <p>Prefer {@link #config()} for the modern typed API.</p>
     */
    VidocqConfiguration configuration();

    /**
     * The modern Vidocq configuration.
     *
     * <p>Uses typed sources and aligns with MicroProfile Config concepts.</p>
     */
    VidocqConfig config();

    /**
     * Shortcut to the CDI {@link BeanManager}.
     */
    default BeanManager beanManager() {
        return container().getBeanManager();
    }

    /**
     * How this JVM was launched, as Vidocq resolved it for this boot: what an extension reads to
     * offer what only makes sense in development, rather than guessing from {@code vidocq.profile}.
     *
     * <p>The context Vidocq passes to {@link VidocqExtension#onStart} returns the resolved mode; this
     * default, {@link LaunchMode#PROD}, only answers for contexts that do not know it, such as a
     * test double written before this method existed.
     *
     * @return the launch mode, never {@code null}
     */
    default LaunchMode launchMode() {
        return LaunchMode.PROD;
    }

    /**
     * The startup report of this boot, read-only, for an extension that shows it, such as the dev
     * console.
     *
     * <p>Vidocq writes the report after every {@link VidocqExtension#onStart}, so the view is empty until
     * then, {@code onStart} included, and again once Vidocq has stopped: an extension keeps the supplier
     * and asks it when it needs the report, on a request for instance. The supplier may be asked from any
     * thread.
     *
     * <p>The context Vidocq passes to {@link VidocqExtension#onStart} answers the report of its boot; this
     * default always answers empty, for contexts that have no report, such as a test double written
     * before this method existed.
     *
     * @return the supplier of the view, never {@code null}
     */
    default Supplier<Optional<StartupReportView>> startupReport() {
        return Optional::empty;
    }
}
