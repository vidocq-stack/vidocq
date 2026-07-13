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
package io.vidocq.runtime.arquillian;

import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded Arquillian container for Vidocq.
 * <p>
 * Starts a complete Vidocq server for each deployment through the exact same
 * path a real application uses: {@link VidocqBootstrap} discovers the
 * extensions present on the module path / classpath via {@code ServiceLoader}
 * and drives their lifecycle. The only harness-specific concessions are the
 * ones an in-memory ShrinkWrap deployment requires:
 * </p>
 * <ul>
 *   <li>the archive's classes are registered as additional CDI bean classes
 *       (a real application has them indexed at build time);</li>
 *   <li>the archive is materialized on disk and exposed through a deployment
 *       class loader installed as the thread context class loader, so archive
 *       resources (config files, keys, service files) are visible to the
 *       runtime as if they were on the application classpath;</li>
 *   <li>every {@code microprofile-config.properties} bundled in the archive is
 *       republished as system properties for the MP Config provider.</li>
 * </ul>
 */
public class VidocqEmbeddedContainer implements DeployableContainer<VidocqContainerConfig> {

    private VidocqContainerConfig config;
    private final Map<Archive<?>, DeploymentState> deployments = new LinkedHashMap<>();

    /**
     * Everything created for one deployment, so deployments stay isolated and
     * undeploy can tear down exactly what deploy set up (the JWT TCK deploys
     * several archives over the life of one container).
     */
    private record DeploymentState(
            VidocqBootstrap bootstrap,
            int port,
            MaterializedDeployment materialized,
            ClassLoader previousContextClassLoader) {
    }

    @Override
    public Class<VidocqContainerConfig> getConfigurationClass() {
        return VidocqContainerConfig.class;
    }

    @Override
    public void setup(VidocqContainerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws LifecycleException {
        // Port allocation done at deploy time
    }

    @Override
    public void stop() throws LifecycleException {
        for (Archive<?> archive : List.copyOf(deployments.keySet())) {
            try {
                undeploy(archive);
            } catch (DeploymentException e) {
                throw new LifecycleException("Failed to undeploy " + archive.getName(), e);
            }
        }
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        // Local: the deployment runs in this JVM, so in-container test classes
        // are executed directly and enriched by the registered TestEnrichers.
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        MaterializedDeployment materialized = null;
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        try {
            int port = config.getPort() == 0 ? findFreePort() : config.getPort();

            // Surface the archive content (keys, static documents,
            // META-INF/services entries) on a deployment class loader. It stays
            // installed as the context class loader until undeploy: threads the
            // runtime spawns during start() inherit it, and lazy resource reads
            // (JWT keys, OpenAPI documents) keep working after boot.
            materialized = MaterializedDeployment.of(archive);

            // Bean classes handed to the boot: every ServiceLoader-registered BCE
            // first, then the archive classes (WEB-INF/classes AND WEB-INF/lib
            // jars — CDI treats bundled libraries as bean archives). Dynamic
            // archives are not APT-processed, so the BCEs must go through the
            // full build-compatible lifecycle (@Discovery..@Synthesis) scoped to
            // the archive classes — exactly what build-time codegen does for a
            // real application.
            List<String> beanClassNames = new ArrayList<>();
            beanClassNames.addAll(BuildCompatibleExtensions.discover(materialized.classLoader()));
            beanClassNames.addAll(materialized.beanClassNames());

            // Set system properties for the Chappe HTTP listener (consumed by
            // ChappeServerBootstrap). Cassini (rest-cassini-extension) mounts
            // on this listener via ChappeMountPoint.
            System.setProperty("vidocq.chappe.listener.default.host", config.getHost());
            System.setProperty("vidocq.chappe.listener.default.port", String.valueOf(port));

            // The deployment's MicroProfile Config needs no special handling:
            // the archive is materialized on disk, so every
            // microprofile-config(-profile).properties and every
            // META-INF/services ConfigSource/Converter is visible to the MP
            // Config provider (ravel) through the deployment class loader
            // installed below. Surfacing entries as system properties (the
            // previous approach) is actively harmful — it promotes file
            // entries to ordinal 400, clobbers harness-provided system
            // properties (DefaultConfigSourceOrdinalTest's config_ordinal) and
            // breaks profile resolution (%dev. keys).
            Thread.currentThread().setContextClassLoader(materialized.classLoader());

            // Boot Vidocq with extracted classes
            VidocqBootstrap bootstrap = VidocqBootstrap.create();
            bootstrap.configure(beanClassNames);
            bootstrap.start();

            // In-container tests (Local protocol) run outside any HTTP request,
            // but CDI TCK beans are frequently @RequestScoped. A servlet-based
            // Arquillian container would have an active request context around
            // each test; the embedded container activates it for the deployment
            // lifetime, like other embedded CDI containers do.
            VaubanContainer vauban = VaubanContainer.current();
            if (vauban != null) {
                vauban.requestContext().activate();
            }

            deployments.put(archive, new DeploymentState(
                    bootstrap, port, materialized, previousTccl));

            // Return protocol metadata with HTTP context
            HTTPContext httpContext = new HTTPContext(config.getHost(), port);
            httpContext.add(new Servlet("default", "/"));
            ProtocolMetaData metadata = new ProtocolMetaData();
            metadata.addContext(httpContext);
            return metadata;

        } catch (Exception e) {
            Thread.currentThread().setContextClassLoader(previousTccl);
            if (materialized != null) {
                closeQuietly(materialized);
            }
            throw new DeploymentException("Failed to deploy Vidocq application", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        DeploymentState state = deployments.remove(archive);
        if (state == null) {
            return;
        }
        try {
            state.bootstrap().shutdown();
        } finally {
            Thread.currentThread().setContextClassLoader(state.previousContextClassLoader());
            System.clearProperty("vidocq.chappe.listener.default.host");
            System.clearProperty("vidocq.chappe.listener.default.port");
            closeQuietly(state.materialized());
        }
    }

    private static void closeQuietly(MaterializedDeployment materialized) {
        try {
            materialized.close();
        } catch (IOException ignored) {
            // Temp files are cleaned up by the OS eventually; never fail a test on this.
        }
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 18080; // fallback
        }
    }
}
