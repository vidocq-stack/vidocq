package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.DiracException;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M2 — gauge via MethodHandle, spec MicroProfile Metrics 5.1.1 §3.2.
 */
class GaugeImplTest {

    @Test
    void readsInstanceMethodValue() throws Exception {
        var sample = new SampleGaugeSource();
        var lookup = MethodHandles.lookup();
        var handle = lookup.findVirtual(SampleGaugeSource.class, "current", MethodType.methodType(Integer.class));
        var gauge = new GaugeImpl<Integer>(() -> sample, handle);

        assertEquals(1, gauge.getValue());
        sample.setCurrent(9);
        assertEquals(9, gauge.getValue());
    }

    @Test
    void readsStaticMethodValue() throws Exception {
        var lookup = MethodHandles.lookup();
        var handle = lookup.findStatic(SampleGaugeSource.class, "global", MethodType.methodType(Long.class));
        var gauge = new GaugeImpl<Long>(handle);

        assertEquals(7L, gauge.getValue());
    }

    @Test
    void wrapsInvocationFailuresInDiracException() throws Exception {
        var lookup = MethodHandles.lookup();
        var handle = lookup.findVirtual(SampleGaugeSource.class, "current", MethodType.methodType(Integer.class));
        var gauge = new GaugeImpl<Integer>(() -> null, handle);

        assertThrows(DiracException.class, gauge::getValue);
    }

    static final class SampleGaugeSource {
        private int current = 1;

        Integer current() {
            return current;
        }

        void setCurrent(int value) {
            this.current = value;
        }

        static Long global() {
            return 7L;
        }
    }
}

