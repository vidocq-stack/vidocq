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

import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@link ConfigProperties} implementation backed by a {@link Map}
 * of {@code lower.dot.case} keys — passed to OTel SDK autoconfigure providers
 * ({@link io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider},
 * etc.) so they can create their exporter/sampler/propagator.
 *
 * <p>Sufficient for typical SPI providers (for example the MP Telemetry TCK
 * {@code InMemorySpanExporterProvider}): most only use {@code getString()} or
 * ignore the config entirely.</p>
 *
 * <p>{@link #getComponentLoader()} — through which OpenTelemetry components (exporters among them) load
 * their own services — returns a loader owned by this module, which works on the module path where the
 * OpenTelemetry default does not (see {@link InteropComponentLoader}).</p>
 */
public final class MapConfigProperties implements ConfigProperties {

    private final Map<String, String> props;
    private final ComponentLoader componentLoader;

    /**
     * Properties whose component loader searches the class loader of the OpenTelemetry autoconfigure SPI,
     * as the {@link ConfigProperties#getComponentLoader()} default does.
     */
    public MapConfigProperties(Map<String, String> props) {
        this(props, ConfigProperties.class.getClassLoader());
    }

    /**
     * Properties whose component loader searches {@code classLoader} for services.
     *
     * @param props       the {@code lower.dot.case} properties
     * @param classLoader the class loader whose services {@link #getComponentLoader()} loads
     */
    public MapConfigProperties(Map<String, String> props, ClassLoader classLoader) {
        this.props = props;
        this.componentLoader = new InteropComponentLoader(classLoader);
    }

    @Override
    public ComponentLoader getComponentLoader() {
        return componentLoader;
    }

    @Override
    public String getString(String name) {
        return props.get(name);
    }

    @Override
    public Boolean getBoolean(String name) {
        String v = getString(name);
        return v != null ? Boolean.parseBoolean(v) : null;
    }

    @Override
    public Integer getInt(String name) {
        String v = getString(name);
        return v != null ? Integer.parseInt(v) : null;
    }

    @Override
    public Long getLong(String name) {
        String v = getString(name);
        return v != null ? Long.parseLong(v) : null;
    }

    @Override
    public Double getDouble(String name) {
        String v = getString(name);
        return v != null ? Double.parseDouble(v) : null;
    }

    @Override
    public Duration getDuration(String name) {
        String v = getString(name);
        return v != null ? Duration.parse(v) : null;
    }

    @Override
    public List<String> getList(String name) {
        String v = getString(name);
        if (v == null || v.isEmpty()) return List.of();
        return Arrays.asList(v.split(","));
    }

    @Override
    public Map<String, String> getMap(String name) {
        String v = getString(name);
        if (v == null || v.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : v.split(",")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                out.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
        return out;
    }
}
