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
 * The dev console: a page, on a listener of its own, that shows the startup report of the running boot
 * and the live values of the dev console panels, and runs their actions in a dev launch only.
 *
 * <p>The module exports nothing: Vidocq finds the extension as a service, and the core looks for its class by name,
 * without loading it, to promise its address on the banner. The name falls under the
 * {@code io.vidocq.runtime.extensions} prefix, which keeps the module in the boot layer across dev reloads: what the
 * console remembers from one boot to the next, the port it bound and the URL it printed, lives in static fields.
 *
 * <p>The page is a resource under {@code META-INF/resources/devconsole/}, which is not a package: nothing to open.
 */
module io.vidocq.runtime.extensions.essentials.devconsole {
    requires io.vidocq.runtime.spi.devconsole;
    // ChappeMountPoint and ListenerOptions; brings io.vidocq.chappe.api (Handler, Response) transitively
    requires io.vidocq.runtime.extensions.essentials.chappe;
    // DotName and TypeInfo: the descriptors the console's own cdi panel reads name their classes with them
    requires io.vidocq.vauban.indexer;
    // the platform MXBeans the console's own jvm panel reads
    requires java.management;
    // for the tests only, which read the log records and call the console over HTTP
    requires static java.logging;
    requires static java.net.http;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.essentials.devconsole.DevConsoleExtension;
}
