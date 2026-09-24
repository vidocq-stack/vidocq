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
package io.vidocq.runtime.devservices.extension;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the dev services state file (spec §4.2, {@code vidocq-runtime-devservices-host}'s {@code StateFile}) and
 * reports it as the {@code devservices} section, shown live by the dev console as its own panel.
 *
 * <p>This extension never looks for the file itself: it only reads the path the host handed it through the
 * {@value #STATE_PROPERTY} system property (spec §4.3), passed as {@code -D} to the child JVM under the Maven
 * plugin hosts, or set directly under the JUnit host. A killed host's stale property, or a stray file left in
 * {@code target/} by an earlier run, is never scanned for: without the property this extension reports no dev
 * service, the same as when dev services never ran.
 *
 * <p>{@link #STATE_PROPERTY} is a copy of {@code StateFile.PROPERTY}, not an import: this module must not depend on
 * {@code vidocq-runtime-devservices-host}, which drags in the Maven plugin API and Testcontainers.
 */
public final class DevServicesExtension implements VidocqExtension, DevConsolePanel {

    /** Copy of {@code io.vidocq.runtime.devservices.host.StateFile.PROPERTY} — see the class Javadoc. */
    static final String STATE_PROPERTY = "vidocq.devservices.state";

    private volatile DevServicesSnapshot snapshot = DevServicesSnapshot.NONE;
    private volatile String readProblem;

    @Override
    public String name() {
        return "devservices";
    }

    @Override
    public int priority() {
        return 150;
    }

    @Override
    public void onStart(ExtensionContext context) {
        String path = System.getProperty(STATE_PROPERTY);
        if (path == null || path.isBlank()) {
            return;
        }
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            return;
        }
        try {
            snapshot = StateReader.parse(Files.readString(file));
        } catch (IOException | IllegalArgumentException e) {
            readProblem = e.getClass().getSimpleName();
        }
    }

    @Override
    public void onStop() {
        snapshot = DevServicesSnapshot.NONE;
        readProblem = null;
    }

    @Override
    public String id() {
        return "devservices";
    }

    @Override
    public String title() {
        return "Dev services";
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        DevServicesSection.write(snapshot, readProblem, context, section);
    }

    /**
     * Boot facts only: the application cannot reach Docker, so it cannot sample a container's live figures. Nothing
     * changes between polls, and this panel has no {@linkplain #charts() charts}.
     */
    @Override
    public void sample(PanelSample sample) {
        // intentionally empty
    }
}
