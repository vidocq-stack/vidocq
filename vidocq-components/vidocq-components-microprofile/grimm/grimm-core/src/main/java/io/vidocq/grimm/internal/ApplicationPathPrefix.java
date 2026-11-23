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
package io.vidocq.grimm.internal;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code @ApplicationPath} of the JAX-RS application, put in front of the paths the annotations describe.
 *
 * <p>MicroProfile OpenAPI 4.1 §5.4, "Context root behavior": each global server and each path item must resolve to
 * the absolute URL of the path. The resources of an {@code @ApplicationPath("/api")} application are served under
 * {@code /api} (Jakarta REST §2.3.2), and the document has no server of its own by default, so the paths carry the
 * application path, as the other MicroProfile implementations write them. The context root of a deployment stays
 * out: the specification wants it in the servers ({@code mp.openapi.servers}).</p>
 *
 * <p>With several {@code Application} subclasses that carry one, the first by class name wins, as the Vidocq
 * runtime mounts it (BUG-20261123-49). {@link ModelBuilder#applicationPath} reads the annotation, in the annotation
 * scan that is the documented reflective fallback; this class only works on strings and the model.</p>
 */
final class ApplicationPathPrefix {

    private ApplicationPathPrefix() {
    }

    /** {@code api/}, {@code /api} and {@code /api/} give {@code /api}; {@code /} and a blank value give nothing. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String path = value.strip();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Puts {@code prefix} in front of every path of {@code model}, in their order; nothing when it is empty. */
    static void applyTo(OpenAPI model, String prefix) {
        Paths paths = model.getPaths();
        if (prefix.isEmpty() || paths == null || paths.getPathItems() == null) {
            return;
        }
        Map<String, PathItem> prefixed = new LinkedHashMap<>();
        paths.getPathItems().forEach((path, item) -> prefixed.put(prefixed(prefix, path), item));
        paths.setPathItems(prefixed);
    }

    private static String prefixed(String prefix, String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return prefix;
        }
        return path.startsWith("/") ? prefix + path : prefix + "/" + path;
    }
}
