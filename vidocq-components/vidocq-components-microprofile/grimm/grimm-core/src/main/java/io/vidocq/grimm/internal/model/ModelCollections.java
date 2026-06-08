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
package io.vidocq.grimm.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModelCollections {

    private ModelCollections() {
    }

    static <K, V> Map<K, V> mutableMap(Map<K, V> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }

    static <T> List<T> mutableList(List<T> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    static <K, V> Map<K, V> ensureMutableMap(Map<K, V> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        if (source instanceof LinkedHashMap<?, ?>) {
            return source;
        }
        return new LinkedHashMap<>(source);
    }

    static <K, V> Map<K, V> copyOnWriteMap(Map<K, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    static <T> List<T> ensureMutableList(List<T> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        if (source instanceof ArrayList<?>) {
            return source;
        }
        return new ArrayList<>(source);
    }

    static <T> List<T> copyOnWriteList(List<T> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    static <K, V> Map<K, V> immutableMapView(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <T> List<T> immutableListView(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }
}

