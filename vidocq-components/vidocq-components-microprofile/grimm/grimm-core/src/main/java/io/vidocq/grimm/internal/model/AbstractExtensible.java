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

import org.eclipse.microprofile.openapi.models.Extensible;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class providing the {@link Extensible} contract for all model POJOs.
 *
 * @param <T> the self-type, returned by {@link #addExtension(String, Object)}
 */
public abstract class AbstractExtensible<T extends Extensible<T>> implements Extensible<T> {

    private Map<String, Object> extensions;

    @Override
    public Map<String, Object> getExtensions() {
        return ModelCollections.immutableMapView(extensions);
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = ModelCollections.mutableMap(extensions);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T addExtension(String name, Object value) {
        if (value == null) {
            return (T) this;
        }
        extensions = ModelCollections.copyOnWriteMap(extensions);
        extensions.put(name, value);
        return (T) this;
    }

    @Override
    public void removeExtension(String name) {
        if (extensions != null) {
            extensions = ModelCollections.copyOnWriteMap(extensions);
            extensions.remove(name);
        }
    }
}

