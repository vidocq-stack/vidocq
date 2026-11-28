/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */

package io.vidocq.bodoni.core.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.naming.spi.InitialContextFactory;

/**
 * A test-only naming provider: an {@code InitialContextFactory} answering {@code lookup} from a
 * static map. Install it before a test, call {@link #uninstall()} after.
 */
public final class TestNaming {

    private static final Map<String, Object> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicInteger CLOSED = new AtomicInteger();
    private static String previous;
    private static boolean installed;
    private static volatile boolean failOnClose;

    private TestNaming() {
    }

    /** Makes {@code new InitialContext()} use {@link Factory}, remembering the previous setting. */
    public static void install() {
        if (!installed) {
            previous = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
            installed = true;
        }
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, Factory.class.getName());
    }

    /** Restores the previous factory setting and clears the bindings and the close count. */
    public static void uninstall() {
        if (previous == null) {
            System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
        } else {
            System.setProperty(Context.INITIAL_CONTEXT_FACTORY, previous);
        }
        previous = null;
        installed = false;
        failOnClose = false;
        BINDINGS.clear();
        CLOSED.set(0);
    }

    /** Binds a value under the full name a lookup will use. */
    public static void bind(String name, Object value) {
        BINDINGS.put(name, value);
    }

    /** Makes every context's {@code close()} throw a {@code NamingException} until {@link #uninstall()}. */
    public static void failOnClose() {
        failOnClose = true;
    }

    /** The number of contexts closed since the last {@link #uninstall()}. */
    public static int closedContexts() {
        return CLOSED.get();
    }

    /** The factory named by {@code java.naming.factory.initial}. */
    public static final class Factory implements InitialContextFactory {

        /** Public no-argument constructor, instantiated by name by {@code java.naming}. */
        public Factory() {
            // instantiated reflectively by java.naming
        }

        @Override
        // Context.getEnvironment returns a Hashtable, and a proxy equals only itself.
        @SuppressWarnings({"JdkObsolete", "ReferenceEquality"})
        public Context getInitialContext(Hashtable<?, ?> environment) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "lookup" -> lookup(args[0]);
                case "close" -> {
                    CLOSED.incrementAndGet();
                    if (failOnClose) {
                        throw new NamingException("close failed");
                    }
                    yield null;
                }
                case "getEnvironment" -> new Hashtable<>();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "TestNaming.Context@" + Integer.toHexString(System.identityHashCode(proxy));
                default -> throw new OperationNotSupportedException(method.getName());
            };
            return (Context) Proxy.newProxyInstance(Factory.class.getClassLoader(),
                    new Class<?>[] {Context.class}, handler);
        }

        private static Object lookup(Object name) throws NamingException {
            String key = name instanceof Name n ? n.toString() : String.valueOf(name);
            Object value = BINDINGS.get(key);
            if (value == null) {
                throw new NameNotFoundException(key);
            }
            return value;
        }
    }
}
