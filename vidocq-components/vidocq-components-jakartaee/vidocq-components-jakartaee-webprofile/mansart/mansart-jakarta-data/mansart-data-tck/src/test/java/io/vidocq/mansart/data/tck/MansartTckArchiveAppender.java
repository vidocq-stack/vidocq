package io.vidocq.mansart.data.tck;

import io.vidocq.mansart.data.cdi.MansartDataExtension;
import io.vidocq.mansart.data.dialect.h2.H2DialectFactory;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.container.ClassContainer;
import org.jboss.shrinkwrap.api.container.ResourceContainer;

/**
 * M7-4 — Arquillian {@link ApplicationArchiveProcessor} that injects the Mansart provider into
 * every official Jakarta Data TCK deployment. The TCK only ships its test classes + read-only
 * packages; without this appender the deployment has no provider, so {@code @Inject} into TCK
 * test fields stays null.
 *
 * <p>What we add to every deployment:
 * <ul>
 *   <li>Mansart implementation packages (api, core, dialect, dialect-h2, cdi).</li>
 *   <li>The {@link MansartDataExtension} BCE registration via
 *       {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.</li>
 *   <li>The H2 {@code DialectFactory} ServiceLoader registration.</li>
 *   <li>The shared {@link H2DataSourceProducer} so a {@code DataSource} bean exists.</li>
 * </ul>
 *
 * <p>Note: TCK repository interfaces stay in the TCK jar — the BCE {@code @Enhancement} phase
 * detects them via the {@code @Repository} annotation and routes them to the runtime Proxy path
 * (M7-1/2/3) since they have no compile-time {@code *Impl} class.
 */
public class MansartTckArchiveAppender implements ApplicationArchiveProcessor {

    @Override
    public void process(Archive<?> archive, TestClass testClass) {
        if (!(archive instanceof ClassContainer<?> cc)) return;
        if (!(archive instanceof ResourceContainer<?> rc)) return;

        cc.addPackages(true,
                "io.vidocq.mansart.data",
                "io.vidocq.mansart.data.dialect",
                "io.vidocq.mansart.data.core",
                "io.vidocq.mansart.data.cdi",
                "io.vidocq.mansart.data.dialect.h2");

        cc.addClass(H2DataSourceProducer.class);

        rc.addAsResource(new StringAsset(MansartDataExtension.class.getName() + "\n"),
                "META-INF/services/" + BuildCompatibleExtension.class.getName());
        rc.addAsResource(new StringAsset(H2DialectFactory.class.getName() + "\n"),
                "META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory");
    }
}
