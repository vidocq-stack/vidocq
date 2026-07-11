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
package io.vidocq.humboldt.tck;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * {@link Executor} implementation required by the MicroProfile Telemetry 2.1 TCK
 * porting SPI ({@code org.eclipse.microprofile.telemetry.tracing.tck.porting.PropertiesBasedConfigurationBuilder}).
 *
 * <p>The TCK reads the {@code telemetry.tck.executor} property (system property or
 * {@code META-INF/microprofile-telemetry-tck.properties} resource bundle) to
 * instantiate an Executor used by the {@code JaxRsServerAsyncTestEndpoint}
 * endpoints in their {@code getCompletionStage*} and {@code getSuspend*} methods. Without this
 * class and the matching property, endpoint instantiation fails with
 * "Cannot find any implementations of Executor".</p>
 *
 * <p>Delegates to {@link Executors#newVirtualThreadPerTaskExecutor()} — aligned with the
 * Vidocq philosophy of virtual threads everywhere.</p>
 */
public final class HumboldtTckExecutor implements Executor {

    private static final Executor DELEGATE = Executors.newVirtualThreadPerTaskExecutor();

    public HumboldtTckExecutor() {}

    @Override
    public void execute(Runnable command) {
        DELEGATE.execute(command);
    }
}
