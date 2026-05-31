package io.vidocq.heisenberg.cdi.internal;

import java.lang.reflect.Method;

/**
 * Test utility — reproduces the canonical key format used by
 * {@code PolicyComposer} to identify the shared state of a bulkhead/CB.
 *
 * <p>Must stay in sync with {@code PolicyComposer.invoke(...)}:
 * <pre>
 *   beanKey   = beanClass.getName() + "@" + System.identityHashCode(beanClass.getClassLoader())
 *   methodKey = method.toGenericString()
 * </pre>
 */
final class StateKeys {
    private StateKeys() {}

    static String bean(Class<?> beanClass) {
        return beanClass.getName() + "@" + System.identityHashCode(beanClass.getClassLoader());
    }

    static String method(Method method) {
        return method.toGenericString();
    }
}

