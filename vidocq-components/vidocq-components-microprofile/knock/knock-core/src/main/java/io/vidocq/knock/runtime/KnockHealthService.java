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

import io.vidocq.knock.internal.HealthSnapshot;
import io.vidocq.knock.internal.KnockAggregator;
import io.vidocq.knock.internal.KnockJsonSerializer;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;

import java.util.Objects;

/**
 * Façade runtime Knock — orchestre {@link KnockAggregator} et {@link KnockJsonSerializer}
 * pour produire un {@link HealthReport} (code HTTP + corps JSON) prêt à servir à un
 * transport HTTP (Jakarta REST via {@code knock-cassini}, ou autre).
 *
 * <p>Spec MicroProfile Health 4.0 §3 : « The result of executing a health check procedure
 * is a HealthCheckResponse. The result of the aggregation of these responses is reported
 * via an HTTP endpoint. »</p>
 *
 * <p>Cette classe est exportée (package {@code io.vidocq.knock.runtime}) et constitue
 * le point d'entrée stable pour les adaptateurs de transport. Les modules d'intégration
 * ne doivent pas dépendre directement de {@code io.vidocq.knock.internal}.</p>
 *
 * <p>Thread-safe et virtual-thread-friendly : {@link KnockAggregator} parallélise les
 * appels {@code .call()} sur un {@code VirtualThreadPerTaskExecutor}.</p>
 */
public final class KnockHealthService {

    private final HealthCheckRegistry registry;
    private final KnockAggregator aggregator;
    private final KnockJsonSerializer serializer;

    /**
     * Crée un service adossé au registry donné.
     *
     * @param registry registry source (non null)
     */
    public KnockHealthService(HealthCheckRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.aggregator = new KnockAggregator();
        this.serializer = new KnockJsonSerializer();
    }

    /**
     * Produit le rapport pour le {@link ProbeType} demandé.
     *
     * <p>Pour {@link ProbeType#ALL}, agrège LIVENESS + READINESS + STARTUP (spec §3).
     * HTTP 200 si statut UP, 503 si DOWN (spec §3).</p>
     *
     * @param type type de probe à interroger
     * @return rapport (httpStatus + JSON spec §3.1)
     */
    public HealthReport report(ProbeType type) {
        Objects.requireNonNull(type, "type");
        HealthSnapshot snapshot = aggregator.aggregate(registry, type);
        return new HealthReport(serializer.httpStatus(snapshot), serializer.serialize(snapshot));
    }
}

