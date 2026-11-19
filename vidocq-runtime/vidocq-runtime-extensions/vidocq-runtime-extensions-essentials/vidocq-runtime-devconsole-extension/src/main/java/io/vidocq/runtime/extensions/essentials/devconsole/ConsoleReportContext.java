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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the console's own panels read while they write their boot facts into a {@link RecordingSection}: every row,
 * as the console always shows them, and the launch mode. They read the JVM, not the application: no bean, no route.
 *
 * @param launchMode the launch mode of the boot
 */
record ConsoleReportContext(LaunchMode launchMode) implements StartupReportContext {

    ConsoleReportContext {
        Objects.requireNonNull(launchMode, "launchMode");
    }

    @Override
    public Verbosity verbosity() {
        return Verbosity.DETAILED;
    }

    @Override
    public boolean hasBeanOfType(String typeName) {
        return false;
    }

    @Override
    public <T> Optional<T> lookup(Class<T> type) {
        return Optional.empty();
    }

    @Override
    public List<String> routeUrls(String handlerClassName) {
        return List.of();
    }
}
