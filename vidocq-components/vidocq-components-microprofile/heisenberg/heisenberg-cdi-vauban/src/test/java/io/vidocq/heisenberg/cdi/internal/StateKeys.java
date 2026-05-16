package io.vidocq.heisenberg.cdi.internal;

import java.lang.reflect.Method;

/**
 * Utilitaire de tests — reproduit le format de clé canonique utilisé par
 * {@code PolicyComposer} pour identifier l'état partagé d'un bulkhead/CB.
 *
 * <p>Doit rester synchrone avec {@code PolicyComposer.invoke(...)} :
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

