package io.vidocq.mpserver.ext.mansart.data;

import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;
import jakarta.data.repository.Repository;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Glue between Vidocq lifecycle and the Mansart Jakarta Data 1.0 stack.
 *
 * <p>This extension does <b>not</b> register the {@code mansart-data-cdi} BCE — Vauban picks it
 * up via the standard CDI 4.1 ServiceLoader contract
 * ({@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}).
 * Instead, it does two things at {@code onStart}:
 *
 * <ol>
 *   <li><b>Fail-fast connectivity check</b>: opens a connection from the {@code @Default}
 *       {@link DataSource} and immediately closes it. A misconfigured JDBC URL or unreachable
 *       database surfaces here at boot, not on the first user request.</li>
 *   <li><b>Repository inventory log</b>: walks the {@link BeanManager} and prints the
 *       {@code @Repository} interfaces that have been wired, so an operator can confirm at a
 *       glance that the APT generation + BCE discovery actually fired.</li>
 * </ol>
 *
 * <p>The connectivity check is opt-out via {@code vidocq.data.checkOnStart=false} for tests or
 * deployments that boot before the database is reachable (typical migration pipelines).
 *
 * <p>Priority {@code 300} — runs after the pool extension (200) which publishes the
 * {@code DataSource}, but before any HTTP transport (Cassini at 500) so a broken database
 * configuration aborts the boot before exposing a port.
 */
public final class MansartDataIntegrationExtension implements VidocqExtension {

    private static final System.Logger LOG =
            System.getLogger(MansartDataIntegrationExtension.class.getName());

    private static final String P_CHECK_ON_START = "vidocq.data.checkOnStart";

    @Override
    public String name() {
        return "mansart-data";
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public void onStart(ExtensionContext context) {
        BeanManager bm = context.beanManager();

        if (context.config().getValue(P_CHECK_ON_START, Boolean.class, Boolean.TRUE)) {
            checkDataSourceReachable(bm);
        }
        logRepositoryInventory(bm);
    }

    private static void checkDataSourceReachable(BeanManager bm) {
        Set<Bean<?>> dsBeans = bm.getBeans(DataSource.class, AnyLiteral.INSTANCE);
        if (dsBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Mansart Data extension is enabled but no DataSource bean is exposed by CDI. "
                            + "Either deploy the mansart-pool extension with vidocq.pool.url, or "
                            + "publish your own @Produces DataSource.");
        }
        // Resolve via lookup() so Default qualifier semantics apply ; the @Any literal above is
        // only there to enumerate candidates for the diagnostic message.
        DataSource ds = (DataSource) bm.getReference(
                bm.resolve(bm.getBeans(DataSource.class)),
                DataSource.class,
                bm.createCreationalContext(null));
        try (Connection c = ds.getConnection()) {
            if (!c.isValid(2)) {
                throw new IllegalStateException(
                        "Mansart Data: DataSource handed out an invalid connection on boot");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Mansart Data: cannot reach the configured DataSource on boot", e);
        }
        LOG.log(System.Logger.Level.INFO, "Mansart Data: DataSource reachable");
    }

    private static void logRepositoryInventory(BeanManager bm) {
        // Walk every bean and pick the ones whose declaring class carries @Repository. We cannot
        // ask the BeanManager directly for an annotated type set without crossing CDI Lite limits.
        Set<String> reposFqn = new LinkedHashSet<>();
        for (Bean<?> b : bm.getBeans(Object.class, AnyLiteral.INSTANCE)) {
            Class<?> beanClass = b.getBeanClass();
            if (beanClass == null) continue;
            for (Class<?> itf : beanClass.getInterfaces()) {
                if (itf.isAnnotationPresent(Repository.class)) {
                    reposFqn.add(itf.getName());
                    break;
                }
            }
            if (beanClass.isInterface() && beanClass.isAnnotationPresent(Repository.class)) {
                reposFqn.add(beanClass.getName());
            }
        }
        if (reposFqn.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Mansart Data: no @Repository interface discovered. "
                            + "Did the APT processor (mansart-data-processor) run on the application module?");
        } else {
            LOG.log(System.Logger.Level.INFO,
                    "Mansart Data: " + reposFqn.size() + " repository(ies) wired: " + reposFqn);
        }
    }

    /** {@code @Any} qualifier literal — kept off the hot path. */
    private static final class AnyLiteral
            extends jakarta.enterprise.util.AnnotationLiteral<Any> implements Any {
        static final AnyLiteral INSTANCE = new AnyLiteral();
        private AnyLiteral() {}
    }
}
