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
package io.vidocq.runtime.it.foywebsocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A programmatic endpoint that answers each text message with {@code echo:} and the message, and records
 * what the container tells it: the close codes and the errors.
 */
public class EchoEndpoint extends Endpoint {

    /** Counted down when a connection opens. */
    public static final AtomicReference<CountDownLatch> OPENED = new AtomicReference<>(new CountDownLatch(1));
    /** Counted down when a connection closes. */
    public static final AtomicReference<CountDownLatch> CLOSED = new AtomicReference<>(new CountDownLatch(1));
    /** The close codes {@code onClose} received. */
    public static final List<Integer> CLOSE_CODES = new CopyOnWriteArrayList<>();
    /** The errors {@code onError} received. */
    public static final List<Throwable> ERRORS = new CopyOnWriteArrayList<>();

    /** Forgets what earlier connections recorded. */
    public static void reset() {
        OPENED.set(new CountDownLatch(1));
        CLOSED.set(new CountDownLatch(1));
        CLOSE_CODES.clear();
        ERRORS.clear();
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        CLOSE_CODES.add(reason.getCloseCode().getCode());
        CLOSED.get().countDown();
    }

    @Override
    public void onError(Session session, Throwable error) {
        ERRORS.add(error);
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        OPENED.get().countDown();
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) message -> {
            try {
                session.getBasicRemote().sendText("echo:" + message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
