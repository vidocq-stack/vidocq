/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.spi;

import org.eclipse.microprofile.health.HealthCheck;

import java.util.List;

/**
 * Registre des {@link HealthCheck} Knock.
 *
 * <p>Chaque check est associé à un {@link ProbeType} (LIVENESS, READINESS, STARTUP) et
 * identifié par un nom unique. L'agrégat ALL expose l'ensemble des checks enregistrés.</p>
 *
 * <p>Les implémentations doivent être thread-safe — le registry est accédé concurremment
 * par l'intégration CDI (enregistrement) et les endpoints JAX-RS (lecture). Aucun
 * {@code synchronized} ni {@code ThreadLocal} ne doit être utilisé — virtual-thread-friendly.</p>
 */
public interface HealthCheckRegistry {

    /**
     * Enregistre un {@link HealthCheck} pour le {@link ProbeType} donné.
     *
     * <p>Le nom est dérivé de {@code check.getClass().getName()} et sert de clé unique.
     * Un second enregistrement avec le même nom remplace le précédent.</p>
     *
     * @param type  le type de probe
     * @param name  le nom unique du check (ex. : nom du bean CDI ou classe)
     * @param check l'implémentation du check
     */
    void register(ProbeType type, String name, HealthCheck check);

    /**
     * Supprime le check identifié par {@code name}.
     *
     * <p>Sans effet si aucun check portant ce nom n'est enregistré.</p>
     *
     * @param name le nom unique du check à supprimer
     */
    void unregister(String name);

    /**
     * Retourne tous les checks associés au {@link ProbeType} donné.
     *
     * <p>Pour {@link ProbeType#ALL}, retourne l'union de LIVENESS, READINESS et STARTUP.</p>
     *
     * @param type le type de probe
     * @return liste immuable des checks enregistrés pour ce type
     */
    List<HealthCheck> getChecks(ProbeType type);
}
