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
package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;

/**
 * Vidocq's main extension point.
 * <p>
 * Extensions are discovered via {@link java.util.ServiceLoader} and
 * executed according to their {@link #priority() priority} during the lifecycle
 * from the server:
 * <ol>
 *   <li>{@link #configure} — configuration before CDI boot</li>
 *   <li>{@link #beforeStart} — enrichment of the container builder</li>
 *   <li>{@link #onStart} — the CDI container is ready</li>
 *   <li>{@link #onStop} — server shutdown (reverse order)</li>
 * </ol>
 *
 * <p><b>Main extension point for Vidocq.</b>
 * Extensions are discovered via {@link java.util.ServiceLoader} and
 * executed by {@link #priority()} during the server lifecycle.</p>
 */
public interface VidocqExtension {

    /**
     * Unique name of the extension. / Unique extension name.
     */
    String name();

    /**
     * Execution priority (lower = higher priority, default 1000).
     * <p>Execution priority (lower = higher priority, default 1000).</p>
     */
    default int priority() {
        return 1000;
    }

    /**
     * Configuration phase: called before CDI boot.
     * <p>Configuration phase: called before CDI boot.</p>
     */
    default void configure(VidocqConfiguration config) {}

    /**
     * Pre-startup phase: enrich the {@link VaubanContainerBuilder}.
     * <p>Pre-start phase: enrich the {@link VaubanContainerBuilder}.</p>
     */
    default void beforeStart(VaubanContainerBuilder builder) {}

    /**
     * Startup phase: the CDI container is initialized.
     * <p>Start phase: the CDI container is initialized.</p>
     */
    default void onStart(ExtensionContext context) {}

    /**
     * Shutdown phase: release resources.
     * <p>Stop phase: release resources.</p>
     */
    default void onStop() {}

    /**
     * The {@code vidocq.*} configuration keys this extension consumes, declared so the runtime can
     * tell a configured key apart from a key nobody reads.
     *
     * <p>A key that nothing consumes is applied by nobody: the application silently keeps the
     * default, and the mistake is invisible whenever the configured value happens to <em>be</em> the
     * default. Declaring keys here lets {@code VidocqBootstrap} report that at startup instead, as
     * {@code VIDOCQ-CFG-003} (see {@code ConfigKeyAudit}).
     *
     * <p>An entry ending in {@code *} is a prefix — {@code "vidocq.chappe.listener.*"} covers
     * {@code vidocq.chappe.listener.admin.port}. Auditing is opt-in per namespace: keys are only
     * reported under a {@code vidocq.<namespace>.} that some loaded extension, or the core itself,
     * claims, so an extension that declares nothing costs nothing and never produces false warnings.
     *
     * @return the consumed keys and key prefixes; empty by default
     */
    default java.util.Set<String> configKeys() {
        return java.util.Set.of();
    }
}
