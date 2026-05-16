/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
 * Container Arquillian Heisenberg — <strong>embedded local container</strong> dédié au TCK
 * MicroProfile Fault Tolerance 4.1.
 *
 * <p>Pour chaque déploiement TCK, démarre un container Vauban CDI avec les classes de
 * l'archive ShrinkWrap, l'intercepteur {@code FaultToleranceInterceptor}, la BCE
 * {@code HeisenbergExtension} et les beans d'état CDI ({@code StateRegistryBean},
 * {@code BulkheadStateRegistryBean}).</p>
 *
 * <p>Protocole {@code Local} : les tests s'exécutent dans la JVM Arquillian, pas dans
 * un container distant.</p>
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
        // rien à initialiser.
    }

    @Override
    public void start() throws LifecycleException {
        // no-op : Vauban démarre par déploiement.
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

