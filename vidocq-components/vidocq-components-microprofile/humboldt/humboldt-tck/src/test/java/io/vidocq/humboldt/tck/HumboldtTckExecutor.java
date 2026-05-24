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
 * Implémentation {@link Executor} requise par la SPI de "porting" du TCK MicroProfile
 * Telemetry 2.1 ({@code org.eclipse.microprofile.telemetry.tracing.tck.porting.PropertiesBasedConfigurationBuilder}).
 *
 * <p>Le TCK lit la propriété {@code telemetry.tck.executor} (system property ou
 * resource bundle {@code META-INF/microprofile-telemetry-tck.properties}) pour
 * instancier un Executor utilisé par les endpoints {@code JaxRsServerAsyncTestEndpoint}
 * dans leurs méthodes {@code getCompletionStage*} et {@code getSuspend*}. Sans cette
 * classe et la property correspondante, l'instantiation de l'endpoint échoue avec
 * "Cannot find any implementations of Executor".</p>
 *
 * <p>Délègue à {@link Executors#newVirtualThreadPerTaskExecutor()} — aligné sur la
 * philosophie Vidocq Virtual Threads partout.</p>
 */
public final class HumboldtTckExecutor implements Executor {

    private static final Executor DELEGATE = Executors.newVirtualThreadPerTaskExecutor();

    public HumboldtTckExecutor() {}

    @Override
    public void execute(Runnable command) {
        DELEGATE.execute(command);
    }
}
