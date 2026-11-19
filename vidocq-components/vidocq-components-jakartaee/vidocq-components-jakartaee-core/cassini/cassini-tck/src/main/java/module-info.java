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
 * Runner module for the official Jakarta RESTful Web Services 4.0 TCK.
 *
 * <p>It declares no packages of its own: the embedded harness it used to carry
 * now lives in the published {@code io.vidocq.cassini.testing}. What remains are
 * this module's test sources — the Arquillian adapter and the smoke test — which
 * are patched into this module when the suite runs, which is why the module keeps
 * its name: {@code cassini-core} grants its internals to it by qualified export.</p>
 *
 * <p>This module is never deployed (see {@code maven.deploy.skip} in its POM).</p>
 */
module io.vidocq.cassini.tck {
    requires transitive io.vidocq.cassini.testing;

    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires jakarta.cdi;

    requires static java.net.http;
}
