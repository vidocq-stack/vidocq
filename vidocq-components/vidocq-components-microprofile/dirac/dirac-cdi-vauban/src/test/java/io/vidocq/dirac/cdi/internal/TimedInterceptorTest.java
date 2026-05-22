package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M4 — interception {@code @Timed}, spec MicroProfile Metrics 5.1.1 §4.4.
 */
class TimedInterceptorTest {

    @Test
    void recordsDurationInApplicationScopeAndProceeds() throws Exception {
        var registries = new MetricRegistryProducerBean();
        var interceptor = new TimedInterceptor(registries);
        var target = new SampleService();
        var method = SampleService.class.getDeclaredMethod("work");
        interceptor.preRegisterTimers(SampleService.class);

        var result = interceptor.aroundInvoke(new FakeInvocationContext(target, method, new Object[0], () -> {
            Thread.sleep(2);
            return "ok";
        }));

        assertEquals("ok", result);
        var metricId = new MetricID(MetricRegistry.name(SampleService.class, "work"), new Tag("kind", "sync"));
        var timer = registries.applicationRegistry().getTimer(metricId);
        assertEquals(1, timer.getCount());
        assertFalse(timer.getElapsedTime().isNegative());
    }

    @Test
    void supportsAbsoluteMetricNames() throws Exception {
        var registries = new MetricRegistryProducerBean();
        var interceptor = new TimedInterceptor(registries);
        var target = new AbsoluteService();
        var method = AbsoluteService.class.getDeclaredMethod("ping");
        interceptor.preRegisterTimers(AbsoluteService.class);

        interceptor.aroundInvoke(new FakeInvocationContext(target, method, new Object[0], () -> null));

        assertEquals(1, registries.applicationRegistry().getTimer(new MetricID("absolute.timing")).getCount());
    }

    @Test
    void routesTimerToTheRequestedScope() throws Exception {
        var registries = new MetricRegistryProducerBean();
        var interceptor = new TimedInterceptor(registries);
        var target = new VendorScopedService();
        var method = VendorScopedService.class.getDeclaredMethod("emit");
        interceptor.preRegisterTimers(VendorScopedService.class);

        interceptor.aroundInvoke(new FakeInvocationContext(target, method, new Object[0], () -> null));

        assertEquals(1, registries.vendorRegistry().getTimer(new MetricID("vendor.timing")).getCount());
    }

    @Test
    void rejectsMalformedTags() throws Exception {
        var registries = new MetricRegistryProducerBean();
        var interceptor = new TimedInterceptor(registries);
        var target = new MalformedTagService();
        var method = MalformedTagService.class.getDeclaredMethod("boom");

        assertThrows(DiracException.class,
                () -> interceptor.aroundInvoke(new FakeInvocationContext(target, method, new Object[0], () -> null)));
    }

    static final class FakeInvocationContext implements InvocationContext {
        private final Object target;
        private final Method method;
        private Object[] parameters;
        private final ProceedAction action;
        private final Map<String, Object> contextData = new HashMap<>();

        FakeInvocationContext(Object target, Method method, Object[] parameters, ProceedAction action) {
            this.target = target;
            this.method = method;
            this.parameters = parameters.clone();
            this.action = action;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return parameters.clone();
        }

        @Override
        public void setParameters(Object[] params) {
            this.parameters = params.clone();
        }

        @Override
        public Map<String, Object> getContextData() {
            return contextData;
        }

        @Override
        public Object proceed() throws Exception {
            return action.proceed();
        }
    }

    @FunctionalInterface
    interface ProceedAction {
        Object proceed() throws Exception;
    }

    static class SampleService {
        @Timed(tags = {"kind=sync"})
        String work() {
            return "ok";
        }
    }

    static class AbsoluteService {
        @Timed(name = "absolute.timing", absolute = true)
        void ping() {
        }
    }

    static class MalformedTagService {
        @Timed(tags = {"missing-separator"})
        void boom() {
        }
    }

    static class VendorScopedService {
        @Timed(name = "vendor.timing", absolute = true, scope = MetricRegistry.VENDOR_SCOPE)
        void emit() {
        }
    }
}



