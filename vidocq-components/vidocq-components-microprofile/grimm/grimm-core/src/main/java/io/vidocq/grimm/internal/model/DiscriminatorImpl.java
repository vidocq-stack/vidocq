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

import org.eclipse.microprofile.openapi.models.media.Discriminator;

import java.util.LinkedHashMap;
import java.util.Map;

public class DiscriminatorImpl implements Discriminator {

    private String propertyName;
    private Map<String, String> mapping;
    /** {@code x-} keys read from a static file; {@link Discriminator} is not {@code Extensible}. */
    private Map<String, Object> staticExtensions;

    /** Keeps an {@code x-} key of a static-file discriminator so the serializer can write it back. */
    public void putStaticExtension(String name, Object value) {
        if (staticExtensions == null) {
            staticExtensions = new LinkedHashMap<>();
        }
        staticExtensions.put(name, value);
    }

    /** The {@code x-} keys kept by {@link #putStaticExtension}, never {@code null}. */
    public Map<String, Object> staticExtensions() {
        return staticExtensions == null ? Map.of() : java.util.Collections.unmodifiableMap(staticExtensions);
    }

    @Override public String getPropertyName() { return propertyName; }
    @Override public void setPropertyName(String propertyName) { this.propertyName = propertyName; }

    @Override
    public Discriminator addMapping(String name, String value) {
        if (value == null) return this;
        mapping = ModelCollections.copyOnWriteMap(mapping);
        mapping.put(name, value);
        return this;
    }

    @Override
    public void removeMapping(String name) {
        if (mapping != null) {
            mapping = ModelCollections.copyOnWriteMap(mapping);
            mapping.remove(name);
        }
    }

    @Override public Map<String, String> getMapping() { return ModelCollections.immutableMapView(mapping); }
    @Override public void setMapping(Map<String, String> mapping) { this.mapping = ModelCollections.mutableMap(mapping); }
}

