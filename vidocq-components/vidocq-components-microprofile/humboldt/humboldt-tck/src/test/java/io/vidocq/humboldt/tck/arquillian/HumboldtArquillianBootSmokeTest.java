package io.vidocq.humboldt.tck.arquillian;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * Smoke test M7b.4b.1 — verifies that the Arquillian → HumboldtDeployableContainer
 * chain starts without errors:
 * <ol>
 *   <li>{@link HumboldtLoadableExtension} is loaded through {@code META-INF/services}</li>
 *   <li>{@link HumboldtDeployableContainer} is registered and setup/start are executed</li>
 *   <li>{@link HumboldtDeployableContainer#deploy(org.jboss.shrinkwrap.api.Archive)}
 *       is invoked with the {@link #deployment()} archive</li>
 *   <li>The test itself runs (proof that the Local protocol returns
 *       sufficient {@code ProtocolMetaData})</li>
 *   <li>undeploy + stop in teardown</li>
 * </ol>
 *
 * <p>No CDI/HTTP verification yet — those steps will come in M7b.4b.2 and later.</p>
 */
public class HumboldtArquillianBootSmokeTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-arq-boot-smoke.jar")
                .addClass(HumboldtArquillianBootSmokeTest.class);
    }

    @Test
    public void container_boots_and_executes_in_test_method() {
        // If we get here, it means:
        //  - Arquillian started
        //  - HumboldtDeployableContainer.start() succeeded
        //  - HumboldtDeployableContainer.deploy(Archive) returned valid ProtocolMetaData
        //  - The test was invoked by the Local protocol
        // → the M7b.4b.1 skeleton is working.
        assertTrue(true, "Boot chain Arquillian → Humboldt container OK");
    }
}
