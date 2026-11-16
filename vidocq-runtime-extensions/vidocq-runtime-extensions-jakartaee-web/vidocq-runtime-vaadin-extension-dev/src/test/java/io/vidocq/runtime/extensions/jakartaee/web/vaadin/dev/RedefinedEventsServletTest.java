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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event stream servlet, over stand-ins of the servlet API. */
class RedefinedEventsServletTest {

    private final Map<String, Object> headers = new ConcurrentHashMap<>();

    @Test
    void theStreamSendsEachEventUntilTheApplicationStops() throws Exception {
        var body = new StringWriter();
        var servlet = new RedefinedEvents();
        Thread reader = Thread.ofVirtual().start(() -> get(servlet, new PrintWriter(body)));
        awaitOpen(body);

        RedefinedEvents.publish("MainView");
        servlet.destroy();
        reader.join(Duration.ofSeconds(5));

        assertEquals(": open\n\ndata: MainView\n\n", body.toString());
        assertEquals("text/event-stream", headers.get("contentType"));
        assertEquals("no-cache", headers.get("Cache-Control"));
    }

    @Test
    void aClosedConnectionEndsTheStream() throws Exception {
        var closed = new PrintWriter(Writer.nullWriter()) {
            @Override
            public boolean checkError() {
                return true;
            }
        };
        var servlet = new RedefinedEvents();
        Thread reader = Thread.ofVirtual().start(() -> get(servlet, closed));
        reader.join(Duration.ofSeconds(1));
        assertTrue(reader.isAlive(), "the stream waits for an event first");

        RedefinedEvents.publish("MainView");
        reader.join(Duration.ofSeconds(5));

        assertEquals(false, reader.isAlive());
    }

    @Test
    void theInitializerMapsTheServlet() {
        List<String> mappings = new ArrayList<>();
        List<Object> added = new ArrayList<>();
        var registration = stub(ServletRegistration.Dynamic.class, (name, args) -> {
            if (name.equals("addMapping")) {
                mappings.addAll(List.of((String[]) args[0]));
                return Set.of();
            }
            return null;
        });
        var context = stub(ServletContext.class, (name, args) -> {
            if (name.equals("addServlet")) {
                added.add(args[0]);
                added.add(args[1]);
                return registration;
            }
            return null;
        });

        new RedefinedEvents.Initializer().onStartup(Set.of(), context);

        assertEquals(List.of("vidocq-dev-redefined", RedefinedEvents.class), added);
        assertEquals(List.of("/.vidocq/dev/redefined"), mappings);
    }

    /** A context that already has a servlet of that name gets none, and nothing fails. */
    @Test
    void aServletOfThatNameAlreadyThereIsLeftAlone() {
        var context = stub(ServletContext.class, (name, args) -> null);

        new RedefinedEvents.Initializer().onStartup(Set.of(), context);
    }

    private void get(RedefinedEvents servlet, PrintWriter out) {
        var request = stub(HttpServletRequest.class, (name, args) -> null);
        var response = stub(HttpServletResponse.class, (name, args) -> switch (name) {
            case "getWriter" -> out;
            case "setContentType" -> headers.put("contentType", args[0]);
            case "setHeader" -> headers.put((String) args[0], args[1]);
            default -> null;
        });
        servlet.doGet(request, response);
    }

    private static void awaitOpen(StringWriter body) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!body.toString().startsWith(": open") && System.nanoTime() < end) {
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private static <T> T stub(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> answer.answer(method.getName(), args == null ? new Object[0] : args)));
    }
}
