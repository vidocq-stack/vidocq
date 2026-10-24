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

/**
 * {@code @VidocqTest}: the application under test, started in its own JVM for a test class (Vidocq/vidocq#350).
 * Surefire's module-path mode leaves this test-scope jar on the class path, where JUnit finds the extension through
 * {@code @ExtendWith}; the module descriptor serves a build that puts it on the module path.
 */
module io.vidocq.runtime.junit {
    requires org.junit.jupiter.api;
    requires java.net.http;

    exports io.vidocq.runtime.junit;
}
