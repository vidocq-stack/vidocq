/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.runtime;

import io.vidocq.knock.internal.KnockHealthCheckRegistry;
import io.vidocq.knock.spi.HealthCheckRegistry;

/**
 * Factory exportée des {@link HealthCheckRegistry} Knock.
 *
 * <p>Point d'entrée stable pour les modules d'intégration Knock
 * ({@code knock-cdi-vauban}, {@code knock-cassini}) qui doivent obtenir une
 * instance de registry sans dépendre du package interne {@code io.vidocq.knock.internal}
 * (lequel reste non exporté — frontière JPMS du projet).</p>
 *
 * <p>Implémentation : délègue à {@link KnockHealthCheckRegistry} (thread-safe,
 * virtual-thread-friendly).</p>
 */
public final class HealthCheckRegistries {

    private HealthCheckRegistries() {
        // utility class
    }

    /**
     * Crée un nouveau {@link HealthCheckRegistry} Knock vide.
     *
     * @return une instance neuve, thread-safe, prête à l'emploi
     */
    public static HealthCheckRegistry newRegistry() {
        return new KnockHealthCheckRegistry();
    }
}

