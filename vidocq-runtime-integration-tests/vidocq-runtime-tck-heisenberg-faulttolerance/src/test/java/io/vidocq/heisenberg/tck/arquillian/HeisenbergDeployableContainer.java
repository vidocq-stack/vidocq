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
package io.vidocq.heisenberg.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;

/**
 * Heisenberg Arquillian container — <strong>embedded local container</strong> dedicated to the
 * MicroProfile Fault Tolerance 4.1 TCK.
 *
 * <p>For each TCK deployment, starts a Vauban CDI container with the classes from
 * the ShrinkWrap archive, the {@code FaultToleranceInterceptor}, the BCE
 * {@code HeisenbergExtension}, and the CDI state beans ({@code StateRegistryBean},
 * {@code BulkheadStateRegistryBean}).</p>
 *
 * <p>{@code Local} protocol: tests run in the Arquillian JVM, not in
 * a remote container.</p>
 */
public class HeisenbergDeployableContainer implements DeployableContainer<HeisenbergContainerConfiguration> {

    @Override
    public Class<HeisenbergContainerConfiguration> getConfigurationClass() {
        return HeisenbergContainerConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public void setup(HeisenbergContainerConfiguration configuration) {
        // Nothing to initialize.
    }

    @Override
    public void start() throws LifecycleException {
        // no-op: Vauban starts on deployment.
    }

    @Override
    public void stop() throws LifecycleException {
        VaubanTckBootstrap.undeploy();
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            VaubanTckBootstrap.deploy(archive);
        } catch (Exception e) {
            FaultToleranceDefinitionException ftDefinition = findFtDefinitionException(e);
            if (ftDefinition != null) {
                throw ftDefinition;
            }
            if (isFtValidationFailure(e.getMessage())) {
                throw new FaultToleranceDefinitionException(e.getMessage(), e);
            }
            throw new DeploymentException("Failed to bootstrap Heisenberg+Vauban for "
                    + archive.getName() + " : " + e.getMessage(), e);
        }
        return new ProtocolMetaData();
    }

    private static FaultToleranceDefinitionException findFtDefinitionException(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof FaultToleranceDefinitionException ft) {
                return ft;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static boolean isFtValidationFailure(String message) {
        return message != null && message.contains("@Enhancement error:");
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        VaubanTckBootstrap.undeploy();
    }

    @Override
    public void deploy(Descriptor descriptor) {
        // no-op.
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        // no-op.
    }
}

