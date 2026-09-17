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
package io.vidocq.runtime.core;

/**
 * How the startup banner is printed, set with {@code vidocq.banner.mode} or, overriding the
 * configuration, with {@link VidocqBootstrap#banner(BannerMode)}.
 *
 * <p>Whatever the mode, the banner is emitted at most once per JVM, and never fails the boot.
 */
public enum BannerMode {

    /**
     * The default. The art and two lines (Vidocq identity, context) on standard output when
     * standard output is a terminal or a dev launch is detected (a {@code dev} profile, the
     * {@code vidocq:dev} reload loop, IntelliJ's Run console). One INFO log line with the
     * identity everywhere else: containers, CI, pipes, test runtimes and embedded deployments.
     */
    AUTO,

    /** The art and the two lines on standard output, whatever standard output is. */
    CONSOLE,

    /** The art and the two lines in one INFO log record, without colours. */
    LOG,

    /** Nothing. */
    OFF
}
