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
 * Dev services for a whole JUnit Platform run, through a {@code LauncherSessionListener}. Surefire's module-path mode
 * puts on the module path only the modules the application's own requires; this test-scope jar is none of them and
 * stays on the class path, where its {@code META-INF/services} entry finds it as before. Should it land on the module
 * path, the {@code provides} below finds it there (Vidocq/vidocq#177).
 */
module io.vidocq.runtime.devservices.junit {
    requires io.vidocq.runtime.devservices.host;
    requires org.junit.platform.launcher;

    provides org.junit.platform.launcher.LauncherSessionListener
            with io.vidocq.runtime.devservices.junit.DevServicesSessionListener;
}
