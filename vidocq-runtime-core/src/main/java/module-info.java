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
module io.vidocq.runtime.core {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    // Vidocq.run(Class, args): the VidocqApp callback is resolved as a CDI bean first
    requires jakarta.cdi;
    // Universal-loader launch mode (VidocqAppLayer): app archives in a child module
    // layer defined by the VaubanClassLoader.
    requires io.vidocq.vauban.classloader;
    requires java.management;
    // Console logging (io.vidocq.runtime.core.console): one aligned line per record on stdout,
    // installed over the JDK default console handler only.
    requires java.logging;
    // LayerJdbcDrivers: the helper whose bytes are defined in a closing layer references java.sql; it never runs in this module.
    requires static java.sql;
    // The dev reload loop's class redefinition (ClassRedefinition): read only when vidocq:dev resolved the agent module
    // into the boot layer and named it in -javaagent; no other launch has it.
    requires static io.vidocq.runtime.dev.agent;
    // The -dev companions told of a redefinition (RedefinitionListeners): read only when vidocq:dev put the dev SPI in
    // the boot layer with the console; a packaged application never has it.
    requires static io.vidocq.runtime.spi.devconsole;

    exports io.vidocq.runtime.core;
    exports io.vidocq.runtime.core.config;
    // The thread factory of the common pool, which the JDK loads by name and creates by reflection
    // (BUG-20261118-93): exported to java.base only, no API of the runtime.
    exports io.vidocq.runtime.core.commonpool to java.base;

    uses io.vidocq.runtime.spi.VidocqExtension;
    uses io.vidocq.runtime.spi.config.ConfigSource;
    // When a ConfigSourceProvider is registered (e.g. via
    // vidocq-runtime-ravel-config-extension), it replaces the native sources below.
    uses io.vidocq.runtime.spi.config.ConfigSourceProvider;
    // Startup report sections of libraries that are not extensions (extensions implement it directly).
    uses io.vidocq.runtime.spi.report.StartupReportContributor;
    // The -dev companions' listeners of a redefinition of vidocq:dev, loaded from the application layer.
    uses io.vidocq.runtime.spi.devconsole.RedefinitionListener;

    // The application layer, for an extension that lists a directory of the application (vidocq#96).
    provides io.vidocq.runtime.spi.ApplicationLayer with io.vidocq.runtime.core.InstalledApplicationLayer;

    provides io.vidocq.runtime.spi.config.ConfigSource with
            io.vidocq.runtime.core.config.SystemPropertiesConfigSource,
            io.vidocq.runtime.core.config.EnvConfigSource,
            io.vidocq.runtime.core.config.ExternalFileConfigSource,
            io.vidocq.runtime.core.config.PropertiesFileConfigSource;
}
