package io.vidocq.humboldt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumboldtTest {

    @Test
    void version_is_published() {
        String v = Humboldt.version();
        assertFalse(v.isBlank(), "version() doit retourner une chaîne non-vide");
        assertTrue(v.startsWith("0.1.0"), "M0 doit publier 0.1.0-* : " + v);
    }

    @Test
    void start_does_not_throw() {
        Humboldt.start();
    }
}
