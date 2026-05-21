package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * Smoke test M7b.4b.1 — vérifie que la chaîne Arquillian → HumboldtDeployableContainer
 * démarre sans erreur :
 * <ol>
 *   <li>{@link HumboldtLoadableExtension} est chargé via {@code META-INF/services}</li>
 *   <li>{@link HumboldtDeployableContainer} est enregistré + setup/start exécutés</li>
 *   <li>{@link HumboldtDeployableContainer#deploy(org.jboss.shrinkwrap.api.Archive)}
 *       est invoqué avec l'archive {@link #deployment()}</li>
 *   <li>Le test lui-même s'exécute (preuve que le protocole Local renvoie un
 *       {@code ProtocolMetaData} suffisant)</li>
 *   <li>undeploy + stop en teardown</li>
 * </ol>
 *
 * <p>Aucune vérification CDI/HTTP — ces étapes viendront en M7b.4b.2 et suivants.</p>
 */
public class HumboldtArquillianBootSmokeTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-arq-boot-smoke.jar")
                .addClass(HumboldtArquillianBootSmokeTest.class);
    }

    @Test
    public void container_boots_and_executes_in_test_method() {
        // Si on arrive ici, c'est que :
        //  - Arquillian a démarré
        //  - HumboldtDeployableContainer.start() a réussi
        //  - HumboldtDeployableContainer.deploy(Archive) a renvoyé un ProtocolMetaData valide
        //  - Le test a été invoqué par le protocole Local
        // → le squelette M7b.4b.1 est fonctionnel.
        assertTrue(true, "Boot chain Arquillian → Humboldt container OK");
    }
}
