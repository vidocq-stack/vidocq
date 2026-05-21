package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implémentation minimale de {@link ConfigProperties} backée par une {@link Map}
 * — passée aux providers OTel SDK ({@link io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider})
 * pour qu'ils créent leur {@code SpanExporter}.
 *
 * <p>Sufficient pour le TCK MP Telemetry : la plupart des providers TCK
 * ({@code InMemorySpanExporterProvider}, etc.) n'utilisent que
 * {@code getString()} et ignorent la config.</p>
 */
final class MapConfigProperties implements ConfigProperties {

    private final Map<String, String> props;

    MapConfigProperties(Map<String, String> props) {
        this.props = props;
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
