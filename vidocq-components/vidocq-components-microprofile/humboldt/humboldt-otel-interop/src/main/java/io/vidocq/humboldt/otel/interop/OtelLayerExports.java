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
package io.vidocq.humboldt.otel.interop;

import io.vidocq.humboldt.otel.api.layer.ApiLayerExports;
import io.vidocq.humboldt.otel.context.layer.ContextLayerExports;

/**
 * Lets the OpenTelemetry modules of the layer that defines a discovered provider reach the internal packages
 * that {@code io.opentelemetry.api} and {@code io.opentelemetry.context} export to them by name.
 *
 * <p>Those modules' descriptors export {@code io.opentelemetry.api.internal}, {@code io.opentelemetry.api.impl},
 * {@code io.opentelemetry.api.trace.propagation.internal} and {@code io.opentelemetry.context.internal.shaded} to
 * the OpenTelemetry SDK, exporter and sender modules that use them (BUG-20261004-03). A qualified export reaches
 * only target modules of the same layer or of a parent layer: when the application brings an OTLP exporter in a
 * child layer of Humboldt's modules, nothing reaches it. Only the owning module may add an export at run time,
 * so each one does it for its own packages ({@link ApiLayerExports}, {@link ContextLayerExports}), granting
 * exactly what its descriptor names. A provider on the class path has no layer: nothing is done.</p>
 */
final class OtelLayerExports {

    private OtelLayerExports() {}

    /** Extends the qualified exports of both Humboldt OpenTelemetry modules to the layer that defines {@code type}. */
    static void extendTo(Class<?> type) {
        ModuleLayer layer = type.getModule().getLayer();
        if (layer == null) {
            return;
        }
        ApiLayerExports.extendTo(layer);
        ContextLayerExports.extendTo(layer);
    }
}
