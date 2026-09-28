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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.List;
import java.util.Optional;

/**
 * The context of the report: what a contributor reads while it writes its section. It knows no bean and no route.
 *
 * @param launchMode the launch mode of the boot
 * @param verbosity  how much the report shows
 */
record ReportContext(LaunchMode launchMode, Verbosity verbosity) implements StartupReportContext {

    /** The context the dev console gives: every row. */
    static ReportContext detailed() {
        return new ReportContext(LaunchMode.DEV, Verbosity.DETAILED);
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
