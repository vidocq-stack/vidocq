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

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportView;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The context Vidocq hands the console in {@code onStart}: a configuration, a launch mode, and a startup report that
 * a test writes when it wants, as the core does after every {@code onStart}.
 */
final class FakeExtensionContext implements ExtensionContext {

    private final VidocqConfig config;
    private final LaunchMode launchMode;
    private final AtomicReference<StartupReportView> report = new AtomicReference<>();

    private FakeExtensionContext(VidocqConfig config, LaunchMode launchMode) {
        this.config = config;
        this.launchMode = launchMode;
    }

    /** A context of {@code launchMode}, configured by key and value pairs. */
    static FakeExtensionContext of(LaunchMode launchMode, String... keysAndValues) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new FakeExtensionContext(TestConfig.of(data), launchMode);
    }

    /** Writes the report of this boot: from now on {@link #startupReport()} answers it. */
    void writeReport(StartupReportView view) {
        report.set(view);
    }

    @Override
    public VaubanContainer container() {
        return null;
    }

    @Override
    public VidocqConfiguration configuration() {
        return key -> config.getValue(key);
    }

    @Override
    public VidocqConfig config() {
        return config;
    }

    @Override
    public LaunchMode launchMode() {
        return launchMode;
    }

    @Override
    public Supplier<Optional<StartupReportView>> startupReport() {
        return () -> Optional.ofNullable(report.get());
    }
}
