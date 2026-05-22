package io.vidocq.dirac.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

/**
 * Container Arquillian Dirac — embedded local container dédié au TCK
 * MicroProfile Metrics 5.1.1.
 *
 * <p>Protocole {@code Local} : les tests s'exécutent dans la JVM Arquillian,
 * pas dans un container distant.</p>
 */
public class DiracDeployableContainer implements DeployableContainer<DiracContainerConfiguration> {

    @Override
    public Class<DiracContainerConfiguration> getConfigurationClass() {
        return DiracContainerConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public void setup(DiracContainerConfiguration configuration) {
        // rien à initialiser.
    }

    @Override
    public void start() throws LifecycleException {
        // no-op : Vauban démarre par déploiement.
    }

    @Override
    public void stop() throws LifecycleException {
        VaubanDiracTckBootstrap.undeploy();
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            VaubanDiracTckBootstrap.deploy(archive);
        } catch (Exception e) {
            throw new DeploymentException("Failed to bootstrap Dirac+Vauban for "
                    + archive.getName() + " : " + e.getMessage(), e);
        }
        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        VaubanDiracTckBootstrap.undeploy();
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
