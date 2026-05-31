package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

class AnnotationReaderTest {

    @Test
    void usesMethodAnnotationOverClassAnnotation() throws Exception {
        // MP FT 4.1 §2: method > class precedence.
        Method method = MethodAnnotatedService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNotNull(annotations.retry());
        assertEquals(1, annotations.retry().maxRetries());
    }

    @Test
    void fallsBackToClassAnnotationWhenMethodHasNone() throws Exception {
        // MP FT 4.1 §2: class annotations apply when the method declares none.
        Method method = ClassAnnotatedService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNotNull(annotations.timeout());
        assertEquals(250, annotations.timeout().value());
    }

    @Test
    void returnsNullForPoliciesThatAreNotDeclared() throws Exception {
        Method method = NoAnnotationService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNull(annotations.retry());
        assertNull(annotations.timeout());
        assertNull(annotations.circuitBreaker());
        assertNull(annotations.bulkhead());
        assertNull(annotations.fallback());
        assertNull(annotations.asynchronous());
    }

    @Retry(maxRetries = 5)
    static class MethodAnnotatedService {
        @Retry(maxRetries = 1)
        void call() {}
    }

    @Timeout(250)
    static class ClassAnnotatedService {
        void call() {}
    }

    static class NoAnnotationService {
        void call() {}
    }
}

