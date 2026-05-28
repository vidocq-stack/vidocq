package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide la sémantique de remplacement de {@code ConfigSourceProvider} : quand
 * un provider est enregistré, ses sources remplacent intégralement les sources
 * natives ServiceLoader-découvertes. Test unitaire qui injecte une liste
 * explicite via le constructeur {@code (List)} pour éviter les effets de
 * classpath de ServiceLoader.
 *
 * <p>Le scénario "vrai provider via ServiceLoader" est validé en E2E par
 * {@code RavelConfigSourceProviderTest} dans l'extension Ravel.</p>
 */
@DisplayName("VidocqConfigImpl — sémantique remplacement vs natif")
class VidocqConfigImplProviderTest {

    @Test
    @DisplayName("Liste explicite remplace toute découverte ServiceLoader")
    void explicitListBypassesServiceLoader() {
        ConfigSource s = source("mock", 999, Map.of("vidocq.test.key", "from-mock"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals("from-mock", cfg.getValue("vidocq.test.key").orElseThrow());

        // Une éventuelle source native (SystemPropertiesConfigSource ordinal 400)
        // n'a pas été chargée : seule la source explicite est utilisée.
        boolean foundOnlyMock = false;
        int count = 0;
        for (ConfigSource src : cfg.getConfigSources()) {
            count++;
            if ("mock".equals(src.getName())) foundOnlyMock = true;
        }
        assertEquals(1, count, "La liste explicite remplace toute autre source");
        assertTrue(foundOnlyMock);
    }

    @Test
    @DisplayName("Liste vide explicite → aucune source (ne tombe pas sur natif)")
    void emptyExplicitListIsEmpty() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of());
        assertFalse(cfg.getValue("vidocq.any").isPresent());
        int count = 0;
        for (ConfigSource ignored : cfg.getConfigSources()) count++;
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Sources explicites triées par ordinal décroissant")
    void sortsByDescendingOrdinal() {
        ConfigSource low = source("low", 50, Map.of("k", "low"));
        ConfigSource high = source("high", 500, Map.of("k", "high"));
        ConfigSource mid = source("mid", 200, Map.of("k", "mid"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(low, high, mid));

        assertEquals("high", cfg.getValue("k").orElseThrow());

        java.util.List<String> names = new java.util.ArrayList<>();
        cfg.getConfigSources().forEach(s -> names.add(s.getName()));
        assertEquals(List.of("high", "mid", "low"), names);
    }

    // ---- helpers ----

    private static ConfigSource source(String name, int ordinal, Map<String, String> data) {
        return new ConfigSource() {
            @Override public String getName() { return name; }
            @Override public int getOrdinal() { return ordinal; }
            @Override public String getValue(String key) { return data.get(key); }
            @Override public Set<String> getPropertyNames() { return data.keySet(); }
        };
    }
}
