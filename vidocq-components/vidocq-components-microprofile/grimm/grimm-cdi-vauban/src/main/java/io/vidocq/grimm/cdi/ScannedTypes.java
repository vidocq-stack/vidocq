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
package io.vidocq.grimm.cdi;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Holder of the JAX-RS resource / model classes discovered at deployment time
 * by the {@link GrimmExtension} BCE. Injected into {@link GrimmModelCache} so
 * the pipeline can scan them at startup.
 *
 * <p>Plain immutable record — produced by {@link GrimmConfigProducer} from the
 * class names collected during the BCE {@code @Enhancement} phase.</p>
 */
public record ScannedTypes(List<Class<?>> classes) {

    public ScannedTypes {
        Objects.requireNonNull(classes, "classes");
        classes = List.copyOf(classes);
    }

    /** No classes — used when CDI discovery is unavailable. */
    public static ScannedTypes empty() {
        return new ScannedTypes(List.of());
    }

    /** Convenience factory from a collection. */
    public static ScannedTypes of(Collection<Class<?>> classes) {
        return new ScannedTypes(List.copyOf(classes));
    }
}

