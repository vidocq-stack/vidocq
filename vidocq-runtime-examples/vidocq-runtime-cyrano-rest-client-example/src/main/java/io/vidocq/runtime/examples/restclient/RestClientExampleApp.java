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
package io.vidocq.runtime.examples.restclient;

import io.vidocq.runtime.core.Vidocq;
import io.vidocq.runtime.spi.VidocqApp;
import io.vidocq.runtime.spi.VidocqMain;

/**
 * A MicroProfile Rest Client application: {@code GET /api/relay} calls {@code GET /api/greeting} of the same
 * server through the {@link GreetingClient} injected with {@code @Inject @RestClient}.
 *
 * <pre>{@code
 * java -m io.vidocq.runtime.examples.restclient/io.vidocq.runtime.examples.restclient.RestClientExampleApp
 * curl http://localhost:18091/api/relay
 * }</pre>
 */
@VidocqMain
public class RestClientExampleApp implements VidocqApp {

    static void main(String[] args) {
        Vidocq.run(RestClientExampleApp.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        Vidocq.waitForExit();
        return 0;
    }
}
