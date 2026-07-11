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

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.runtime.spi.VidocqExtension;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.impl.base.path.BasicPath;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Embedded Arquillian container for Vidocq.
 * <p>
 * Starts a complete Vidocq server (CDI + extensions) with the classes
 * from the ShrinkWrap deployment registered as CDI beans.
 * </p>
 */
public class VidocqEmbeddedContainer implements DeployableContainer<VidocqContainerConfig> {

    private VidocqContainerConfig config;
    private VidocqBootstrap bootstrap;
    private int actualPort;

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
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 6.0");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            // Resolve port
            actualPort = config.getPort() == 0 ? findFreePort() : config.getPort();

            // Extract bean classes from archive
            List<String> beanClassNames = extractClassNames(archive);

            // Set system properties for the Chappe HTTP listener (consumed by
            // ChappeServerBootstrap). Cassini (rest-cassini-extension) mounts
            // on this listener via ChappeMountPoint.
            System.setProperty("vidocq.chappe.listener.default.host", config.getHost());
            System.setProperty("vidocq.chappe.listener.default.port", String.valueOf(actualPort));

            // Boot Vidocq with extracted classes
            bootstrap = VidocqBootstrap.create();
            bootstrap.configure(beanClassNames);
            bootstrap.start();

            // Return protocol metadata with HTTP context
            HTTPContext httpContext = new HTTPContext(config.getHost(), actualPort);
            httpContext.add(new Servlet("default", "/"));
            ProtocolMetaData metadata = new ProtocolMetaData();
            metadata.addContext(httpContext);
            return metadata;

        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy Vidocq application", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    private List<String> extractClassNames(Archive<?> archive) {
        List<String> classNames = new ArrayList<>();
        Map<org.jboss.shrinkwrap.api.ArchivePath, Node> content = archive.getContent();
        for (var entry : content.entrySet()) {
            String path = entry.getKey().get();
            if (path.endsWith(".class") && !path.contains("module-info")) {
                // Normalise both JavaArchive (classes at root) and WebArchive
                // (classes under WEB-INF/classes/) layouts, e.g.:
                //   /com/example/MyClass.class            -> com.example.MyClass
                //   /WEB-INF/classes/com/example/Foo.class -> com.example.Foo
                String relative = path.substring(1); // remove leading /
                if (relative.startsWith("WEB-INF/classes/")) {
                    relative = relative.substring("WEB-INF/classes/".length());
                }
                String className = relative
                        .replace('/', '.')
                        .replace(".class", "");
                classNames.add(className);
            }
        }
        return classNames;
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 18080; // fallback
        }
    }
}
