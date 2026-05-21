package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration du container Arquillian Humboldt — pour l'instant aucun paramètre
 * exposé (M7b.4b.1 : squelette). Les options sont prévues pour les étapes
 * suivantes : port HTTP (Chappe), classpath isolation, etc.
 */
public final class HumboldtContainerConfig implements ContainerConfiguration {

    @Override
    public void validate() throws ConfigurationException {
        // M7b.4b.1 : aucune option à valider. Méthode laissée pour les étapes
        // suivantes (M7b.4b.2+) qui ajouteront port HTTP, isolation, etc.
    }
}
