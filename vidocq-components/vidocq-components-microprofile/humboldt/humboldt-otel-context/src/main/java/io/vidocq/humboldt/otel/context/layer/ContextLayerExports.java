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
package io.vidocq.humboldt.otel.context.layer;

import java.lang.module.ModuleDescriptor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Extends the qualified exports of the module {@code io.opentelemetry.context} to the modules of another module
 * layer.
 *
 * <p>A qualified export in a module descriptor reaches only the target modules of the same layer or of a parent
 * layer, never those of a child layer. When an application brings OpenTelemetry jars (an OTLP exporter) in a child
 * layer of Humboldt's modules, the internal packages this module exports to them by name do not reach them, and
 * their first access fails with an {@code IllegalAccessError}. {@link Module#addExports(String, Module)} can add
 * those exports at run time, but only when called from this module itself: hence this class, inside
 * humboldt-otel-context, which humboldt-otel-interop calls for the layer of each OpenTelemetry provider it
 * discovers. (humboldt-otel-api has the same class for its own module.)</p>
 *
 * <p>Only what the descriptor already grants by name is added: a package is exported to a module if and only if
 * one of the descriptor's qualified exports names that module as a target. On the class path this module is
 * unnamed and everything is accessible: nothing is done.</p>
 */
public final class ContextLayerExports {

    private ContextLayerExports() {}

    /**
     * Exports to each module of {@code layer} and of its parent layers the packages that the descriptor of
     * {@code io.opentelemetry.context} exports to that module's name and that do not reach it yet.
     *
     * @param layer the layer of OpenTelemetry modules, typically a child layer of this module's own
     */
    public static void extendTo(ModuleLayer layer) {
        Module self = ContextLayerExports.class.getModule();
        ModuleDescriptor descriptor = self.getDescriptor();
        if (descriptor == null) {
            return;
        }
        Deque<ModuleLayer> pending = new ArrayDeque<>();
        pending.push(layer);
        Set<ModuleLayer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            ModuleLayer current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            for (Module target : current.modules()) {
                for (ModuleDescriptor.Exports export : descriptor.exports()) {
                    if (export.isQualified() && export.targets().contains(target.getName())
                            && !self.isExported(export.source(), target)) {
                        self.addExports(export.source(), target);
                    }
                }
            }
            current.parents().forEach(pending::push);
        }
    }
}
