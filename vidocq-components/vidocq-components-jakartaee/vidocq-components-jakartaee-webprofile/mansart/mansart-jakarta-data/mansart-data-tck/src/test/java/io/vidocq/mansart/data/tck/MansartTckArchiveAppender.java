package io.vidocq.mansart.data.tck;

import io.vidocq.mansart.data.cdi.MansartDataExtension;
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

    /**
     * M6.5 — Switches the deployment between H2 (default) and PostgreSQL (Testcontainers)
     * based on the system property {@code mansart.tck.dialect}. Set to {@code pg}
     * (typically via Maven profile {@code -Ptck-pg}) to wire the
     * {@link PostgresDataSourceProducer} and the
     * {@code io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialectFactory}.
     */
    private static final String DIALECT_PROP = System.getProperty("mansart.tck.dialect", "h2")
            .toLowerCase();
    private static final boolean USE_PG = "pg".equals(DIALECT_PROP)
            || "postgres".equals(DIALECT_PROP)
            || "postgresql".equals(DIALECT_PROP);

    @Override
    public void process(Archive<?> archive, TestClass testClass) {
        if (!(archive instanceof ClassContainer<?> cc)) return;
        if (!(archive instanceof ResourceContainer<?> rc)) return;

        // M6.5 — `io.vidocq.mansart.data` (the api annotations package) added non-recursively;
        // the recursive sweep would otherwise pull `io.vidocq.mansart.data.tck.*` and land BOTH
        // H2DataSourceProducer and PostgresDataSourceProducer in the deployment, making the
        // `@Inject DataSource` resolution ambiguous.
        cc.addPackages(false, "io.vidocq.mansart.data");
        cc.addPackages(true,
                "io.vidocq.mansart.data.dialect",
                "io.vidocq.mansart.data.core",
                "io.vidocq.mansart.data.cdi",
                USE_PG ? "io.vidocq.mansart.data.dialect.postgresql"
                       : "io.vidocq.mansart.data.dialect.h2");

        cc.addClass(USE_PG ? PostgresDataSourceProducer.class : H2DataSourceProducer.class);

        // M7-22 — EntityTests.createDeployment() in the TCK jar only ships EntityTests + Box
        // + Boxes; MultipleEntityRepo and Coordinate stay in the TCK jar but are missing from
        // the deployment archive, leaving @Inject MultipleEntityRepo shared null and breaking
        // testUpdateQueryWith[out]WhereClause. We side-load them by FQN so the archive's
        // ByteArrayClassLoader can find them and the BCE @Enhancement scan can register them.
        for (String fqn : new String[]{
                "ee.jakarta.tck.data.standalone.entity.MultipleEntityRepo",
                "ee.jakarta.tck.data.standalone.entity.Coordinate"
        }) {
            try {
                cc.addClass(Thread.currentThread().getContextClassLoader().loadClass(fqn));
            } catch (ClassNotFoundException ignored) {
                // Test class isn't on the classpath — non-EntityTests deployments will hit
                // this path and that's fine; they just don't need these classes.
            }
        }

        rc.addAsResource(new StringAsset(MansartDataExtension.class.getName() + "\n"),
                "META-INF/services/" + BuildCompatibleExtension.class.getName());
        String dialectFactory = USE_PG
                ? "io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialectFactory"
                : "io.vidocq.mansart.data.dialect.h2.H2DialectFactory";
        rc.addAsResource(new StringAsset(dialectFactory + "\n"),
                "META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory");
    }
}
