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
package io.vidocq.runtime.cli.dev;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal ANSI progress spinner driven by a single virtual thread.
 *
 * <p>Used while the runtime boots; {@link #stop()} cancels the animation and clears the
 * line. The spinner only animates on a real terminal (so CI logs stay clean) and
 * {@link #stop()} is idempotent, making the lifecycle safe to unit-test.
 */
public final class BootSpinner {

    private static final char[] FRAMES = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};

    private final String message;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public BootSpinner(String message) {
        this(message, System.console() != null && System.getenv("NO_COLOR") == null);
    }

    BootSpinner(String message, boolean enabled) {
        this.message = message;
        this.enabled = enabled;
    }

    /** Starts the animation on a virtual thread. No-op when disabled or already running. */
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual().name("vidocq-dev-spinner").start(this::spin);
    }

    private void spin() {
        int i = 0;
        try {
            while (running.get()) {
                System.out.print("\r" + FRAMES[i % FRAMES.length] + " " + message);
                System.out.flush();
                i++;
                Thread.sleep(80);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the animation and clears the spinner line. Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (thread != null) {
            thread.interrupt();
        }
        if (enabled) {
            System.out.print("\r\033[2K");
            System.out.flush();
        }
    }
}
