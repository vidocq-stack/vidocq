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

import io.vidocq.foy.spi.cdi.CdiContextListeners;
import jakarta.el.ELContext;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.jsp.JspContext;
import jakarta.servlet.jsp.PageContext;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

/**
 * The {@code @Dependent} instances a page created by bean name, kept in a request attribute and destroyed when the
 * request ends. CDI Full destroys them when the EL evaluation completes; JSP has no hook there, and the request is the
 * nearest end the page's EL context knows. Foy registers the listener ahead of the application's
 * ({@link CdiContextListeners}), so it is told last that the request ended.
 */
public final class RequestDependents implements CdiContextListeners {

    /** The request attribute holding the creational contexts to release. */
    static final String ATTRIBUTE = RequestDependents.class.getName();

    /** Created by Foy's service loader. */
    public RequestDependents() {
        // Stateless.
    }

    @Override
    public List<EventListener> listeners(BeanManager beanManager) {
        return List.of(new Release());
    }

    /** The attributes of a request, as far as tracking goes. */
    interface Attributes {
        Object get(String name);

        void set(String name, Object value);

        void remove(String name);
    }

    /**
     * Records a dependent instance's creational context in the request of the page evaluating {@code context}.
     * Outside a page there is no request to end, so the instance is left to the garbage collector.
     */
    static void track(ELContext context, CreationalContext<?> creational) {
        if (context.getContext(JspContext.class) instanceof PageContext page && page.getRequest() != null) {
            track(attributes(page.getRequest()), creational);
        }
    }

    static void track(Attributes request, CreationalContext<?> creational) {
        Tracked tracked;
        if (request.get(ATTRIBUTE) instanceof Tracked existing) {
            tracked = existing;
        } else {
            tracked = new Tracked();
            request.set(ATTRIBUTE, tracked);
        }
        tracked.add(creational);
    }

    /** Releases the request's dependents; one failing destruction does not keep the others alive. */
    static void release(Attributes request) {
        if (!(request.get(ATTRIBUTE) instanceof Tracked tracked)) return;
        request.remove(ATTRIBUTE);
        RuntimeException failure = null;
        for (CreationalContext<?> creational : tracked.drain()) {
            try {
                creational.release();
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private static Attributes attributes(ServletRequest request) {
        return new Attributes() {
            @Override public Object get(String name) { return request.getAttribute(name); }
            @Override public void set(String name, Object value) { request.setAttribute(name, value); }
            @Override public void remove(String name) { request.removeAttribute(name); }
        };
    }

    /** The creational contexts of one request; a request is served by one thread at a time. */
    static final class Tracked {
        private final List<CreationalContext<?>> contexts = new ArrayList<>();

        void add(CreationalContext<?> creational) {
            contexts.add(creational);
        }

        List<CreationalContext<?>> drain() {
            var drained = List.copyOf(contexts);
            contexts.clear();
            return drained;
        }
    }

    private static final class Release implements ServletRequestListener {
        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            release(attributes(event.getServletRequest()));
        }
    }
}
