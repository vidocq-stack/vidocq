/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
