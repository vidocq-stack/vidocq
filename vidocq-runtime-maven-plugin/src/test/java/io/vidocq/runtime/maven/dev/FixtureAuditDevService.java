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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;

import java.util.Map;

/**
 * A {@link DevService} test fixture, registered via {@code META-INF/services} so
 * {@code VidocqDevMojoTest#marksEveryKeyWhoseValueADevServiceProvided()} can exercise the real
 * {@code ServiceLoader} path {@code VidocqDevMojo#execute()} uses — {@code DevServiceManager}'s
 * package-private {@code start(List, ...)} overload is not reachable from this package any more, now that
 * {@code DevServiceManager} lives in {@code vidocq-runtime-devservices-host}.
 */
public final class FixtureAuditDevService implements DevService {

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return true;
    }

    @Override
    public Map<String, String> start(DevServiceContext ctx) {
        return Map.of(
                "vidocq.pool.audit.url", "jdbc:postgresql://localhost:54219/audit",
                "vidocq.pool.audit.username", "vidocq",
                "vidocq.pool.audit.password", "vidocq");
    }

    @Override
    public void stop() {
    }
}
