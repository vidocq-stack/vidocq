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
package io.vidocq.runtime.extensions.essentials.chappe.spi;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.List;
import java.util.Optional;

/**
 * Scope view of the configuration of a declarative mount. Exposed to
 * {@link MountHandlerProvider} so they don't have to rebuild the key
 * complete {@code vidocq.http.mount.<name>.<suffix>}.
 *
 * <h3>Utility methods</h3>
 * <ul>
 *   <li>{@link #property(String)} — scoped raw value</li>
 *   <li>{@link #property(String, Class)} — scoped typed value</li>
 *   <li>{@link #property(String, Class, Object)} — typed value + default</li>
 *   <li>{@link #properties(String, Class)} — typed list (CSV)</li>
 * </ul>
 */
public final class MountConfig {

    private final String name;
    private final String prefix;
    private final String listener;
    private final int priority;
    private final VidocqConfig config;
    private final ExtensionContext context;
    private final String keyPrefix;

    public MountConfig(String name, String prefix, String listener, int priority,
                       VidocqConfig config, ExtensionContext context) {
        this.name = name;
        this.prefix = prefix;
        this.listener = listener;
        this.priority = priority;
        this.config = config;
        this.context = context;
        this.keyPrefix = "vidocq.http.mount." + name + ".";
    }

    /** Logical identifier of the mount (declarative key). */
    public String name() { return name; }

    /** Mount HTTP prefix (e.g. {@code "/api"}, {@code ""} for root). */
    public String prefix() { return prefix; }

    /** Target listener (default {@code "default"}). */
    public String listener() { return listener; }

    /** Recording priority (higher = mounted first, therefore higher priority). */
    public int priority() { return priority; }

    /** Raw access to the underlying {@link VidocqConfig}. */
    public VidocqConfig vidocqConfig() { return config; }

    /** Extension context (for {@code beanManager()}, {@code container()}, etc.). */
    public ExtensionContext extensionContext() { return context; }

    /** Raw value of {@code vidocq.http.mount.<name>.<suffix>}. */
    public Optional<String> property(String suffix) {
        return config.getValue(keyPrefix + suffix);
    }

    /** Typed value of {@code vidocq.http.mount.<name>.<suffix>}. */
    public <T> Optional<T> property(String suffix, Class<T> type) {
        return config.getValue(keyPrefix + suffix, type);
    }

    /** Typed value with default of {@code vidocq.http.mount.<name>.<suffix>}. */
    public <T> T property(String suffix, Class<T> type, T defaultValue) {
        return config.getValue(keyPrefix + suffix, type, defaultValue);
    }

    /** Typed list (CSV) of {@code vidocq.http.mount.<name>.<suffix>}. */
    public <T> List<T> properties(String suffix, Class<T> elementType) {
        return config.getValues(keyPrefix + suffix, elementType);
    }
}
