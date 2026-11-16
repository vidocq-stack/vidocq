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

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The event stream of the redefinitions, at {@value #PATH}: one event for each redefinition that changed more than
 * lambda bodies, read by {@link RefreshScript}. A comment line every {@value #KEEP_ALIVE_SECONDS} seconds keeps the
 * connection open and finds a closed one.
 */
public final class RedefinedEvents extends HttpServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The servlet's path, under the context of the application: the contract between {@link RefreshScript} and this
     * servlet, both in this module, not a setting of the deployment.
     */
    @SuppressWarnings("java:S1075")
    static final String PATH = "/.vidocq/dev/redefined";

    private static final long KEEP_ALIVE_SECONDS = 15;

    /** Ends the open streams when the application is undeployed; never a class name. */
    private static final String END = "\u0000";

    private static final Set<BlockingQueue<String>> STREAMS = ConcurrentHashMap.newKeySet();

    /** A new stream, which every later {@link #publish} reaches until it is {@linkplain #close closed}. */
    static BlockingQueue<String> open() {
        BlockingQueue<String> stream = new LinkedBlockingQueue<>();
        STREAMS.add(stream);
        return stream;
    }

    static void close(BlockingQueue<String> stream) {
        STREAMS.remove(stream);
    }

    /** Sends {@code data} to every open stream. */
    static void publish(String data) {
        STREAMS.forEach(stream -> stream.add(data));
    }

    /** The text of one event. */
    static String event(String data) {
        return "data: " + data + "\n\n";
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        BlockingQueue<String> stream = open();
        try {
            PrintWriter out = response.getWriter();
            out.write(": open\n\n");
            out.flush();
            String data = stream.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            while (!END.equals(data) && !out.checkError()) {
                out.write(data == null ? ": keep-alive\n\n" : event(data));
                out.flush();
                data = stream.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            }
        } catch (IOException _) {
            // The response cannot be written: the page went away, and it opens a new stream when it comes back.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            close(stream);
        }
    }

    @Override
    public void destroy() {
        STREAMS.forEach(stream -> stream.add(END));
    }

    /** Registers the servlet; only {@code vidocq:dev} adds this module, so only a dev deployment has it. */
    public static final class Initializer implements ServletContainerInitializer {

        @Override
        public void onStartup(Set<Class<?>> classes, ServletContext context) {
            var registration = context.addServlet("vidocq-dev-redefined", RedefinedEvents.class);
            if (registration != null) {
                registration.addMapping(PATH);
            }
        }
    }
}
