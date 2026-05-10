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

/**
 * Métadonnées statiques de l'implémentation Knock.
 *
 * <p>Cette classe reste volontairement minimaliste à ce stade (M0).
 * La SPI sera étoffée au fil des milestones : registrar d'extensions,
 * hooks d'observabilité, intégration écosystème Vidocq.</p>
 */
public final class Knock {

    /** Nom logique de l'implémentation. */
    public static final String IMPLEMENTATION_NAME = "knock";

    /** Version de l'implémentation Knock. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    /** Version de la spec MicroProfile Health implémentée. */
    public static final String SPEC_VERSION = "4.0";

    private Knock() {
        // utility class
    }
}
