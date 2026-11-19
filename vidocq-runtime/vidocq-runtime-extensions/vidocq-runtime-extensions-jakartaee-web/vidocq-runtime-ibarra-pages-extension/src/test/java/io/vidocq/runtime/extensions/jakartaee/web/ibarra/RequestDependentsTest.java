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
package io.vidocq.runtime.extensions.jakartaee.web.ibarra;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.servlet.ServletRequestListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RequestDependentsTest {

    private final Map<String, Object> attributes = new HashMap<>();
    private final RequestDependents.Attributes request = new RequestDependents.Attributes() {
        @Override public Object get(String name) { return attributes.get(name); }
        @Override public void set(String name, Object value) { attributes.put(name, value); }
        @Override public void remove(String name) { attributes.remove(name); }
    };

    private static final class Counting implements CreationalContext<Object> {
        final AtomicInteger released = new AtomicInteger();
        private final RuntimeException failure;
        Counting(RuntimeException failure) { this.failure = failure; }
        @Override public void push(Object incompleteInstance) {
            // Not used: the instances are complete.
        }
        @Override public void release() {
            released.incrementAndGet();
            if (failure != null) throw failure;
        }
    }

    @Test
    void theDependentsOfARequestAreReleasedOnceWhenItEnds() {
        var first = new Counting(null);
        var second = new Counting(null);
        RequestDependents.track(request, first);
        RequestDependents.track(request, second);
        assertEquals(0, first.released.get());

        RequestDependents.release(request);
        RequestDependents.release(request);

        assertEquals(1, first.released.get());
        assertEquals(1, second.released.get());
        assertFalse(attributes.containsKey(RequestDependents.ATTRIBUTE));
    }

    @Test
    void aFailingDestructionDoesNotKeepTheOthersAlive() {
        var failing = new Counting(new IllegalStateException("first"));
        var failingToo = new Counting(new IllegalStateException("second"));
        var healthy = new Counting(null);
        RequestDependents.track(request, failing);
        RequestDependents.track(request, failingToo);
        RequestDependents.track(request, healthy);

        var thrown = assertThrows(IllegalStateException.class, () -> RequestDependents.release(request));

        assertEquals("first", thrown.getMessage());
        assertEquals("second", thrown.getSuppressed()[0].getMessage());
        assertEquals(1, healthy.released.get());
    }

    @Test
    void outsideAPageNothingIsTrackedAndARequestWithoutDependentsReleasesNothing() {
        var instance = new Counting(null);
        RequestDependents.track(new StandardELContext(ExpressionFactory.newInstance()), instance);
        RequestDependents.release(request);
        assertEquals(0, instance.released.get());
    }

    @Test
    void foyGetsOneRequestListener() {
        var listeners = new RequestDependents().listeners(null);
        assertEquals(1, listeners.size());
        assertInstanceOf(ServletRequestListener.class, listeners.getFirst());
    }
}
