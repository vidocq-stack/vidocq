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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live;

import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpServerConfigProducer;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Set;

/**
 * What {@code McpExtension#onStart} reads from the container's metadata, once, without creating a bean. Also read by
 * the {@code mcp} live panel of the dev console (Vidocq/vidocq#143), which only {@code vidocq:dev} adds: that is why
 * this record, and this package, is {@code exports ... to ....mcp.dev} of the runtime extension's module.
 *
 * @param twin                 langchain4j-cdi's beans come from another copy of its classes than the one this
 *                             extension links to: the MCP server was loaded twice ({@code VIDOCQ-MCP-003})
 * @param namedConfigBeans     how many {@code @Named("mcp-server")} {@link McpServerConfig} beans exist
 * @param configFromExtension  whether one of them is produced by {@link McpServerConfigProducer}
 */
public record McpInspection(boolean twin, int namedConfigBeans, boolean configFromExtension) {

    /** Before {@code onStart}, or when the container cannot be read: nothing known. */
    public static final McpInspection NOTHING = new McpInspection(false, 0, false);

    /** The qualifier langchain4j-cdi's {@code McpServerConfigResolver} injects. */
    static final String CONFIG_BEAN_NAME = "mcp-server";

    /**
     * Reads the container's metadata. Never creates a bean and never throws.
     *
     * @param beans the bean manager of the started container
     * @return what was read; {@link #NOTHING} when the container cannot be read
     */
    public static McpInspection of(BeanManager beans) {
        try {
            Bean<?> registry = null;
            for (Bean<?> bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
                if (bean.getBeanClass().getName().equals(McpToolRegistry.class.getName())) {
                    registry = bean;
                    break;
                }
            }
            if (registry != null && registry.getBeanClass() != McpToolRegistry.class) {
                return new McpInspection(true, 0, false);
            }
            Set<Bean<?>> named = beans.getBeans(McpServerConfig.class, NamedLiteral.of(CONFIG_BEAN_NAME));
            boolean fromExtension = named.stream().anyMatch(bean -> bean.getBeanClass() == McpServerConfigProducer.class);
            return new McpInspection(false, named.size(), fromExtension);
        } catch (RuntimeException | LinkageError unreadable) {
            return NOTHING;
        }
    }
}
