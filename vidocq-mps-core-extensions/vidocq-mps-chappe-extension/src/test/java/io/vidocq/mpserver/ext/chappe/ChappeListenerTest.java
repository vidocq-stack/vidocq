package io.vidocq.mpserver.ext.chappe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChappeListenerTest {

    @Test
    void httpFactoryBuildsNonTlsListener() {
        ChappeListener l = ChappeListener.http("admin", "127.0.0.1", 9090);
        assertEquals("admin", l.name());
        assertEquals("127.0.0.1", l.host());
        assertEquals(9090, l.port());
        assertFalse(l.tls());
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener(" ", "0.0.0.0", 8080, false));
    }

    @Test
    void nullNameRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChappeListener(null, "0.0.0.0", 8080, false));
    }

    @Test
    void nullHostRejected() {
        assertThrows(NullPointerException.class,
                () -> new ChappeListener("default", null, 8080, false));
    }

    @Test
    void negativePortRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener("default", "0.0.0.0", -1, false));
    }

    @Test
    void portAboveMaxRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChappeListener("default", "0.0.0.0", 70_000, false));
    }

    @Test
    void defaultConstantExposedAsDefault() {
        assertEquals("default", ChappeListener.DEFAULT);
    }
}
