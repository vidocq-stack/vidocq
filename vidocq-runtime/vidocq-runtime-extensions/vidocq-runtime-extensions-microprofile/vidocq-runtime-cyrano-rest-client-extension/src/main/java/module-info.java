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
 * Vidocq Runtime wrapper that activates Cyrano (MicroProfile Rest Client 4.0)
 * via its standard SPIs (RestClientBuilderResolver + BCE CDI 4.1). The BCE is
 * cyrano-cdi-vauban's own, which that module declares itself: this module adds none.
 */
module io.vidocq.runtime.extensions.microprofile.cyrano {
    // Every MicroProfile extension requires the Config extension (Vidocq/vidocq#829): Ravel is the MicroProfile Config
    // implementation this extension reads its keys through, and an application gets @Inject @ConfigProperty with it.
    requires transitive io.vidocq.runtime.extensions.microprofile.ravel;

    requires transitive io.vidocq.cyrano.api;
    requires transitive io.vidocq.cyrano.core;
    requires transitive io.vidocq.cyrano.cdi.vauban;

    requires jakarta.cdi;
}

