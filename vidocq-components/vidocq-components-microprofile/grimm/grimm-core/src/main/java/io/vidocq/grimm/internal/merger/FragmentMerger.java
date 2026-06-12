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
package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;

import java.util.Map;

/**
 * Additive merge of per-class compile-time fragments ({@code $$GrimmModel}
 * contributions, CG-06) into the annotation-source model. Unlike
 * {@link ModelMerger} (which arbitrates between the three spec sources), this
 * merge is same-priority and purely additive: two resource classes sharing a
 * path template contribute distinct operations to the same {@link PathItem},
 * exactly as a single {@code AnnotationScanner} pass over both classes would.
 */
public final class FragmentMerger {

    private FragmentMerger() {
        // utility
    }

    /** Merges {@code fragment} into {@code target} (in place) and returns {@code target}. */
    public static OpenAPI mergeInto(OpenAPI target, OpenAPI fragment) {
        if (fragment == null) return target;
        mergePaths(target, fragment);
        mergeComponents(target, fragment);
        return target;
    }

    private static void mergePaths(OpenAPI target, OpenAPI fragment) {
        Paths fragmentPaths = fragment.getPaths();
        if (fragmentPaths == null || fragmentPaths.getPathItems() == null) return;
        Paths targetPaths = target.getPaths();
        if (targetPaths == null) {
            targetPaths = OASFactory.createObject(Paths.class);
            target.setPaths(targetPaths);
        }
        for (Map.Entry<String, PathItem> e : fragmentPaths.getPathItems().entrySet()) {
            PathItem existing = targetPaths.getPathItem(e.getKey());
            if (existing == null) {
                targetPaths.addPathItem(e.getKey(), e.getValue());
            } else {
                mergeOperations(existing, e.getValue());
            }
        }
    }

    private static void mergeOperations(PathItem into, PathItem from) {
        if (from.getGET() != null) into.setGET(from.getGET());
        if (from.getPUT() != null) into.setPUT(from.getPUT());
        if (from.getPOST() != null) into.setPOST(from.getPOST());
        if (from.getDELETE() != null) into.setDELETE(from.getDELETE());
        if (from.getOPTIONS() != null) into.setOPTIONS(from.getOPTIONS());
        if (from.getHEAD() != null) into.setHEAD(from.getHEAD());
        if (from.getPATCH() != null) into.setPATCH(from.getPATCH());
        if (from.getTRACE() != null) into.setTRACE(from.getTRACE());
        if (from.getParameters() != null) {
            from.getParameters().forEach(into::addParameter);
        }
    }

    private static void mergeComponents(OpenAPI target, OpenAPI fragment) {
        Components fragmentComponents = fragment.getComponents();
        if (fragmentComponents == null || fragmentComponents.getSchemas() == null) return;
        Components targetComponents = target.getComponents();
        if (targetComponents == null) {
            targetComponents = OASFactory.createObject(Components.class);
            target.setComponents(targetComponents);
        }
        for (var e : fragmentComponents.getSchemas().entrySet()) {
            if (targetComponents.getSchemas() == null
                    || !targetComponents.getSchemas().containsKey(e.getKey())) {
                targetComponents.addSchema(e.getKey(), e.getValue());
            }
        }
    }
}
