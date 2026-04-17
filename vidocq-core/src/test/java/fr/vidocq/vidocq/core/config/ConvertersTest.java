package fr.vidocq.vidocq.core.config;

import fr.vidocq.vidocq.spi.config.Converter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConvertersTest {

    @Test
    void stringPassThrough() {
        assertEquals("hello", Converters.forType(String.class).convert("hello"));
    }

    @Test
    void booleanTrueAliases() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        for (String v : new String[] {"true", "TRUE", "yes", "on", "1", "y"}) {
            assertEquals(Boolean.TRUE, c.convert(v), v);
        }
    }

    @Test
    void booleanFalseAliases() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        for (String v : new String[] {"false", "NO", "off", "0", "n", ""}) {
            assertEquals(Boolean.FALSE, c.convert(v), v);
        }
    }

    @Test
    void booleanRejectsGarbage() {
        Converter<Boolean> c = Converters.forType(Boolean.class);
        assertThrows(IllegalArgumentException.class, () -> c.convert("maybe"));
    }

    @Test
    void numericConversions() {
        assertEquals(42, Converters.forType(Integer.class).convert("42"));
        assertEquals(42L, Converters.forType(Long.class).convert("42"));
        assertEquals(1.5d, Converters.forType(Double.class).convert("1.5"));
        assertEquals(1.5f, Converters.forType(Float.class).convert("1.5"));
    }

    @Test
    void primitiveTypesShareWrapperConverter() {
        assertEquals(42, Converters.forType(int.class).convert("42"));
        assertEquals(Boolean.TRUE, Converters.forType(boolean.class).convert("true"));
    }

    @Test
    void durationConversion() {
        assertEquals(Duration.ofMinutes(5), Converters.forType(Duration.class).convert("PT5M"));
    }

    @Test
    void uriAndPathConversion() {
        assertEquals(URI.create("https://example.com/x"),
                Converters.forType(URI.class).convert("https://example.com/x"));
        assertEquals(Path.of("/tmp/foo"), Converters.forType(Path.class).convert("/tmp/foo"));
    }

    @Test
    void unsupportedTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Converters.forType(java.math.BigInteger.class));
    }
}
