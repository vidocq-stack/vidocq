/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.ravel.spi;

/**
 * Métadonnées statiques de l'implémentation Ravel — utilisé par {@code ConfigSource}
 * pour le tracing et par les benchmarks.
 *
 * <p>Le contenu de la SPI sera étoffé au fil des phases M1..M6 (cf. ROADMAP.md) :
 * sources de configuration, converters tiers, hooks d'observabilité. Cette classe
 * est volontairement minimaliste pour M0.</p>
 */
public final class Ravel {

    /** Nom logique de l'implémentation, exposé via {@code Config.getConfigSources()}. */
    public static final String IMPLEMENTATION_NAME = "ravel";

    /** Version de l'implémentation Ravel. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    /** Version de la spec MicroProfile Config implémentée. */
    public static final String SPEC_VERSION = "3.1";

    private Ravel() {
        // utility class
    }
}
