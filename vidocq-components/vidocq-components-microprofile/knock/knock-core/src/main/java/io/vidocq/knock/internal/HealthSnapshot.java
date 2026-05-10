/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.internal;

import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.util.List;

/**
 * Résultat agrégé d'un appel au registry pour un {@link ProbeType} donné.
 *
 * <p>Produit par {@link KnockAggregator} ; consommé par {@link KnockJsonSerializer}
 * pour produire la réponse JSON spec §3.1.</p>
 *
 * @param type   le type de probe agrégé
 * @param status le statut global (DOWN si ≥ 1 check DOWN)
 * @param checks la liste immuable des réponses individuelles
 */
public record HealthSnapshot(
        ProbeType type,
        HealthCheckResponse.Status status,
        List<HealthCheckResponse> checks) {

    public HealthSnapshot {
        checks = List.copyOf(checks);
    }
}
