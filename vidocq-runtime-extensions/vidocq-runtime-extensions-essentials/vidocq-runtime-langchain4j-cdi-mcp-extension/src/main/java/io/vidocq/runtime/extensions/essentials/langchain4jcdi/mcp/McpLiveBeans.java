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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.invoker.cdi41.McpCdi41InvokerProvider;
import dev.langchain4j.cdi.mcp.server.transport.McpNotificationBroadcaster;
import dev.langchain4j.cdi.mcp.server.transport.McpServerRequestManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSessionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSubscriptionRegistry;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.function.ToIntFunction;

/**
 * The beans the {@code mcp} dev console panel reads, resolved once in {@link McpExtension#onStart} and never
 * created: the immutable snapshot {@link McpExtension#sample} reads.
 *
 * <p><b>Why bean metadata rather than a proxy.</b> Holding a client proxy obtained at boot and calling it from
 * {@code sample} would create the bean on the first poll of the dev console page — for {@link McpSessionManager}
 * that means starting its {@code mcp-session-cleanup} scheduler, on a server that has not served a single request.
 * A panel measures, it does not change what it measures. So this record keeps the {@link Bean} metadata only, and
 * {@code sample} asks the bean's {@link Context} for the <em>existing</em> instance with the single-argument
 * {@link Context#get(jakarta.enterprise.context.spi.Contextual)}, which returns {@code null} rather than creating
 * one.
 *
 * <p><b>Why a value is absent.</b> A bean with no instance yet has no number to give, and a zero would claim a
 * measure the panel does not have. Two absences are told apart:
 * <ul>
 *   <li>no bean at all, {@value #NOT_DEPLOYED}: the MCP server is not in this container;</li>
 *   <li>a bean with no instance: {@value #NO_REQUEST_YET} for the session manager, {@value #NOT_CREATED_YET} for
 *       the beans the server creates as it needs them.</li>
 * </ul>
 *
 * @param beans    the bean manager of the started container, {@code null} for {@link #NONE}
 * @param sessions the {@link McpSessionManager} bean, or {@code null}
 * @param streams  the {@link McpNotificationBroadcaster} bean, or {@code null}
 * @param listens  the {@link McpSubscriptionRegistry} bean, or {@code null}
 * @param pending  the {@link McpServerRequestManager} bean, or {@code null}
 * @param invoker  the {@code McpCdi41InvokerProvider} synthetic bean, or {@code null} when the optional CDI 4.1
 *                 invoker module registered none and the server invokes its methods by reflection
 */
record McpLiveBeans(BeanManager beans, Bean<?> sessions, Bean<?> streams, Bean<?> listens, Bean<?> pending,
                    Bean<?> invoker) {

    /** Before {@code onStart}, after {@code onStop}, and on a twin server: the panel samples nothing. */
    static final McpLiveBeans NONE = new McpLiveBeans(null, null, null, null, null, null);

    /** No bean of that type in this container. */
    static final String NOT_DEPLOYED = "no MCP server in this container";
    /** The session manager exists as a bean, but nothing has asked for it. */
    static final String NO_REQUEST_YET = "no request served yet";
    /** A bean the server creates when it first needs it. */
    static final String NOT_CREATED_YET = "not created yet";
    /** No invoker provider bean: {@code McpBeanInvoker} falls back to {@link java.lang.reflect.Method#invoke}. */
    static final String REFLECTION = "reflection";
    /** The invoker provider bean exists; the container builds it when the first MCP method is invoked. */
    static final String BUILT_AT_FIRST_CALL = "built at the first call";

    /**
     * Resolves the beans of the MCP server, once, without creating any of them.
     *
     * <p>A twin server ({@code VIDOCQ-MCP-003}) resolves to {@link #NONE}: its beans are another copy of the
     * classes this extension links to, so the panel can read nothing of it and stays inert rather than showing
     * numbers of the wrong server.
     *
     * @param inspection what {@link McpInspection} read of the same container
     * @param beans      the bean manager of the started container
     * @return the beans that were resolved; {@link #NONE} for a twin, or when the container cannot be read
     */
    static McpLiveBeans of(McpInspection inspection, BeanManager beans) {
        if (beans == null || inspection == null || inspection.twin()) {
            return NONE;
        }
        try {
            return new McpLiveBeans(beans,
                    resolve(beans, McpSessionManager.class),
                    resolve(beans, McpNotificationBroadcaster.class),
                    resolve(beans, McpSubscriptionRegistry.class),
                    resolve(beans, McpServerRequestManager.class),
                    invokerBean(beans));
        } catch (RuntimeException | LinkageError unreadable) {
            return NONE;
        }
    }

    /**
     * The one bean of {@code type} in this container, when it is that very class. The identity check is the twin
     * guard of {@link McpInspection}: a bean whose class is another copy of {@code type}, loaded by another loader,
     * is not one this extension can read, and a cast of its instance would throw.
     */
    private static Bean<?> resolve(BeanManager beans, Class<?> type) {
        try {
            Bean<?> bean = beans.resolve(beans.getBeans(type));
            return bean != null && bean.getBeanClass() == type ? bean : null;
        } catch (RuntimeException | LinkageError unresolvable) {
            return null;
        }
    }

    /**
     * The synthetic bean of the optional CDI 4.1 invoker provider. The module is a hard requirement of this
     * extension, but its build compatible extension registers the bean only when the deployment holds MCP methods
     * it could build invokers for: {@code null} then, and the server invokes by reflection.
     */
    private static Bean<?> invokerBean(BeanManager beans) {
        try {
            return resolve(beans, McpCdi41InvokerProvider.class);
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /**
     * Writes the live values of the MCP server: memory only, no bean created, no I/O, no lock.
     *
     * <p>{@code invoker.matches} and {@code invoker.misses} count <b>distinct methods, not calls</b>: the provider
     * counts one match the first time a given MCP method resolves to a container invoker, so both totals stop
     * growing once every method has been called once. They say whether the server invokes without reflection, not
     * how busy it is — which is why no chart plots them.
     *
     * @param out where the values go
     */
    void sample(PanelSample out) {
        if (beans == null) {
            return;
        }
        gauge(out, "sessions", sessions, McpSessionManager.class, McpSessionManager::activeSessionCount,
                NO_REQUEST_YET);
        gauge(out, "streams", streams, McpNotificationBroadcaster.class,
                McpNotificationBroadcaster::connectedStreamCount, NOT_CREATED_YET);
        gauge(out, "listens", listens, McpSubscriptionRegistry.class, McpSubscriptionRegistry::size,
                NOT_CREATED_YET);
        gauge(out, "pending", pending, McpServerRequestManager.class, McpServerRequestManager::pendingRequestCount,
                NOT_CREATED_YET);
        sampleInvoker(out);
    }

    /** The three invoker values, or the same absence for the three: they come from one bean. */
    private void sampleInvoker(PanelSample out) {
        if (invoker == null) {
            out.absent("invoker.methods", REFLECTION)
                    .absent("invoker.matches", REFLECTION)
                    .absent("invoker.misses", REFLECTION);
            return;
        }
        McpCdi41InvokerProvider provider = instance(invoker, McpCdi41InvokerProvider.class);
        if (provider == null) {
            out.absent("invoker.methods", BUILT_AT_FIRST_CALL)
                    .absent("invoker.matches", BUILT_AT_FIRST_CALL)
                    .absent("invoker.misses", BUILT_AT_FIRST_CALL);
            return;
        }
        out.gauge("invoker.methods", provider.size(), Unit.COUNT)
                .counter("invoker.matches", provider.matchCount(), Unit.COUNT)
                .counter("invoker.misses", provider.missCount(), Unit.COUNT);
    }

    /** A gauge read from an existing instance, or the absence that says why there is no number. */
    private <T> void gauge(PanelSample out, String key, Bean<?> bean, Class<T> type, ToIntFunction<T> value,
            String noInstance) {
        if (bean == null) {
            out.absent(key, NOT_DEPLOYED);
            return;
        }
        T instance = instance(bean, type);
        if (instance == null) {
            out.absent(key, noInstance);
        } else {
            out.gauge(key, value.applyAsInt(instance), Unit.COUNT);
        }
    }

    /**
     * The instance this bean already has, or {@code null}: the single-argument
     * {@link Context#get(jakarta.enterprise.context.spi.Contextual)} never creates one. An inactive context, or a
     * context this container does not know, reads as no instance.
     */
    private <T> T instance(Bean<?> bean, Class<T> type) {
        try {
            Context context = beans.getContext(bean.getScope());
            Object existing = context.get(bean);
            return type.isInstance(existing) ? type.cast(existing) : null;
        } catch (RuntimeException | LinkageError noInstance) {
            return null;
        }
    }
}
