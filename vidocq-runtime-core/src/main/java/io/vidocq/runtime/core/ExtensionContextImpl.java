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

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportView;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implementation of the extension context provided during {@code onStart}.
 *
 * @param container     the built container
 * @param configuration the legacy configuration
 * @param config        the configuration
 * @param launchMode    the launch mode the bootstrap resolved for this boot
 * @param startupReport the startup report of this boot, read-only: empty until the bootstrap has written it, and
 *                      again once it stops; {@code null} reads as always empty
 */
record ExtensionContextImpl(
        VaubanContainer container,
        VidocqConfiguration configuration,
        VidocqConfig config,
        LaunchMode launchMode,
        Supplier<Optional<StartupReportView>> startupReport
) implements ExtensionContext {

    ExtensionContextImpl {
        // A lambda, not Optional::empty: an Eclipse (JDT) build types that reference as Optional<Object>.
        startupReport = startupReport == null ? () -> Optional.empty() : startupReport;
    }
}
