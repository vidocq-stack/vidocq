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
package io.vidocq.vauban.indexer;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class VaubanIndex {

    /**
     * The classes by name. Not {@code Map.copyOf}: its iteration order changes from one JVM to the next, and so did
     * what a build compatible extension saw and froze, so a frozen record was never the same twice (BUG-20261121-01).
     * Sorted by a {@link TreeMap} on {@link DotName}'s natural order, then copied for constant-time lookups: no lambda,
     * so no hidden class at the container start (StartLoadsFewClassesModulePathTest).
     */
    private final Map<DotName, ClassInfo> classes;

    VaubanIndex(Map<DotName, ClassInfo> classes) {
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(classes)));
    }

    public Optional<ClassInfo> getClassByName(DotName name) {
        return Optional.ofNullable(classes.get(name));
    }

    public Collection<ClassInfo> getKnownClasses() {
        return classes.values();
    }

    public Collection<ClassInfo> getClassesWithAnnotation(DotName annotationName) {
        return classes.values().stream().filter(c -> c.hasAnnotation(annotationName)).toList();
    }

    public Collection<ClassInfo> getImplementors(DotName interfaceName) {
        return classes.values().stream().filter(c -> c.interfaces().contains(interfaceName)).toList();
    }

    public Collection<ClassInfo> getSubclasses(DotName className) {
        return classes.values().stream().filter(c -> className.equals(c.superName())).toList();
    }

    public boolean containsClass(DotName name) {
        return classes.containsKey(name);
    }

    public int size() {
        return classes.size();
    }
}
