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

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code devservices} section (spec §4.4) from a {@link DevServicesSnapshot} read once at
 * {@link DevServicesExtension#onStart}: no I/O here, only what the snapshot already holds.
 *
 * <p>A secret's value is shown only as {@link StartupReportSection#secret(String, boolean) configured}, whatever the
 * launch mode: the snapshot never carries one. A non-secret value, such as a JDBC URL with its credentials already
 * stripped by the host, is still shown as a row only in a {@link LaunchMode#DEV DEV} launch; outside development the
 * section lists the injected keys, never their values.
 */
public final class DevServicesSection {

    /** Logged when the state file exists but could not be parsed; the boot goes on. */
    static final String UNREADABLE_STATE_FILE = "VIDOCQ-DEVS-001";

    private DevServicesSection() {}

    /**
     * Writes the section.
     *
     * @param snapshot    the parsed state file, or {@link DevServicesSnapshot#NONE} when there is none
     * @param readProblem the simple name of the exception the extension caught while reading the file, or
     *                    {@code null} when it read cleanly (or found none)
     * @param context     what the report lets a contributor read
     * @param section     where the section is written
     */
    public static void write(DevServicesSnapshot snapshot, String readProblem, StartupReportContext context,
            StartupReportSection section) {
        if (readProblem != null) {
            section.anomaly(UNREADABLE_STATE_FILE,
                    "The dev services state file could not be read: " + readProblem + ".",
                    "Rerun the goal: the Vidocq Maven plugin writes it.");
            section.summary("state file unreadable");
            return;
        }

        List<DevServicesSnapshot.Service> services = snapshot.services();
        if (services.isEmpty()) {
            section.summary("no dev service: not started by vidocq:dev, vidocq:run or the test launcher");
            return;
        }

        section.summary(summaryLine(snapshot, services));
        section.row("started", snapshot.startedAt() + " by " + snapshot.host());

        if (context.verbosity() != Verbosity.DETAILED) {
            return;
        }
        boolean dev = context.launchMode() == LaunchMode.DEV;
        for (DevServicesSnapshot.Service service : services) {
            section.row(service.id(), service.image() + ", " + joinEndpoints(service.endpoints()));
            if (dev) {
                writeInjectedValues(service, section);
            } else {
                section.list(service.id() + " keys", keysOf(service));
            }
        }
    }

    private static String summaryLine(DevServicesSnapshot snapshot, List<DevServicesSnapshot.Service> services) {
        StringBuilder summary = new StringBuilder()
                .append(services.size())
                .append(services.size() == 1 ? " service: " : " services: ");
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) {
                summary.append(", ");
            }
            DevServicesSnapshot.Service service = services.get(i);
            summary.append(service.id()).append(" (").append(service.image()).append(" at ")
                    .append(firstEndpoint(service)).append(')');
        }
        summary.append(" — ").append(snapshot.host());
        if ("stopped".equals(snapshot.state())) {
            summary.append(", stopped");
        }
        return summary.toString();
    }

    private static void writeInjectedValues(DevServicesSnapshot.Service service, StartupReportSection section) {
        for (DevServicesSnapshot.Injected injected : service.injected()) {
            if (injected.configured()) {
                section.secret(service.id() + " " + injected.key(), true);
            } else {
                section.row(service.id() + " " + injected.key(), injected.value());
            }
        }
    }

    private static List<String> keysOf(DevServicesSnapshot.Service service) {
        List<String> keys = new ArrayList<>();
        for (DevServicesSnapshot.Injected injected : service.injected()) {
            keys.add(injected.key());
        }
        return keys;
    }

    private static String firstEndpoint(DevServicesSnapshot.Service service) {
        for (String value : service.endpoints().values()) {
            return value;
        }
        return "";
    }

    private static String joinEndpoints(Map<String, String> endpoints) {
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            if (!joined.isEmpty()) {
                joined.append(", ");
            }
            joined.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return joined.toString();
    }
}
