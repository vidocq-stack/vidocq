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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Glue between Vidocq lifecycle and the Mansart Jakarta Data 1.0 stack.
 *
 * <p>This extension does <b>not</b> register the {@code mansart-data-cdi} BCE — Vauban picks it
 * up via the standard CDI 4.1 ServiceLoader contract
 * ({@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}).
 * Instead, it does three things at {@code onStart}:
 *
 * <ol>
 *   <li><b>Fail-fast connectivity check</b>: opens a connection from the {@code @Default}
 *       {@link DataSource} and immediately closes it. A misconfigured JDBC URL or unreachable
 *       database surfaces here at boot, not on the first user request.</li>
 *   <li><b>Repository inventory log</b>: walks the {@link BeanManager} and prints the
 *       {@code @Repository} interfaces that have been wired, so an operator can confirm at a
 *       glance that the APT generation + BCE discovery actually fired.</li>
 *   <li><b>Catalogue</b>: reads those repositories and, through {@link EntityModels#of}, the model Mansart uses for
 *       each primary entity, once, into a {@link MansartDataCatalogue} of names and texts. It is the
 *       {@code mansart-data} section of the startup report, and the {@code vidocq-runtime-mansart-data-extension-dev}
 *       companion, which only {@code vidocq:dev} adds, shows it live from {@link MansartDataLive}. An entity whose
 *       model cannot be read raises {@value #ANOMALY}; nothing here fails the boot or opens a connection.</li>
 * </ol>
 *
 * <p>The connectivity check is opt-out via {@code vidocq.data.checkOnStart=false} for tests or
 * deployments that boot before the database is reachable (typical migration pipelines).
 *
 * <p>Priority {@code 300} — runs after the pool extension (200) which publishes the
 * {@code DataSource}, but before any HTTP transport (Cassini at 500) so a broken database
 * configuration aborts the boot before exposing a port.
 */
public final class MansartDataIntegrationExtension implements VidocqExtension, StartupReportContributor {

    private static final System.Logger LOG =
            System.getLogger(MansartDataIntegrationExtension.class.getName());

    private static final String P_CHECK_ON_START = "vidocq.data.checkOnStart";

    /** An entity whose model Mansart could not build. */
    static final String ANOMALY = "MANSART-DATA-001";

    static final String HINT =
            "Check its mapping annotations; Mansart could not build its model, so its repositories may fail too.";

    /** The catalogue of this boot, {@code null} before {@code onStart} and after {@code onStop}. */
    private volatile MansartDataCatalogue catalogue;

    /** Why there is no catalogue although {@code onStart} ran: the class of the exception; {@code null} otherwise. */
    private volatile String unavailable;

    @Override
    public String name() {
        return "mansart-data";
    }

    @Override
    public String id() {
        return "mansart-data";
    }

    @Override
    public String title() {
        return "Mansart Data";
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
        start(() -> beanClasses(bm), type -> EntityModels.of(type));
    }

    /**
     * Finds the repositories among {@code beanClasses}, logs them and builds the catalogue; never throws, the boot never
     * fails for it, not even when listing the beans or reading their interfaces does. Visible for tests.
     */
    void start(Supplier<Iterable<Class<?>>> beanClasses, Function<Class<?>, EntityModel<?>> models) {
        List<Class<?>> repositories;
        try {
            repositories = CatalogueBuilder.repositoryInterfaces(beanClasses.get());
        } catch (RuntimeException | LinkageError failure) {
            catalogue = null;
            unavailable = failure.getClass().getName();
            MansartDataLive.clear();
            LOG.log(System.Logger.Level.WARNING, "Mansart Data: the repositories could not be listed", failure);
            return;
        }
        logRepositoryInventory(repositories);
        catalogue(repositories, models);
    }

    /** Builds the catalogue with the limits of the spec, keeps it and publishes it. Visible for tests. */
    void catalogue(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models) {
        catalogue(repositories, models, CatalogueBuilder.DEFAULT);
    }

    /** Builds the catalogue, keeps it and publishes it; never throws: the boot never fails for it. Visible for tests. */
    void catalogue(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models, CatalogueBuilder builder) {
        try {
            MansartDataCatalogue built = builder.build(repositories, models);
            catalogue = built;
            unavailable = null;
            MansartDataLive.publish(built);
        } catch (RuntimeException | LinkageError failure) {
            catalogue = null;
            unavailable = failure.getClass().getName();
            MansartDataLive.clear();
            LOG.log(System.Logger.Level.WARNING, "Mansart Data: the catalogue could not be built", failure);
        }
    }

    @Override
    public void onStop() {
        // First: a dev console poll from now on reads no catalogue.
        MansartDataLive.clear();
        catalogue = null;
        unavailable = null;
    }

    /**
     * The catalogue, from memory: the summary, then at {@link Verbosity#DETAILED} a row per entity and per repository,
     * then one {@value #ANOMALY} per entity whose model could not be read, at every verbosity.
     */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        MansartDataCatalogue read = catalogue;
        if (read == null) {
            String why = unavailable;
            section.summary(why == null ? "no catalogue: the extension did not start" : "no catalogue (" + why + ")");
            return;
        }
        section.summary(count(read.entityCount(), "entity", "entities") + ", "
                + count(read.repositoryCount(), "repository", "repositories") + ", "
                + count(read.methodCount(), "method", "methods"));
        if (context.verbosity() == Verbosity.DETAILED) {
            for (MansartDataCatalogue.Entity entity : read.entities()) {
                section.row(entity.name(), entity.failure() == null
                        ? entity.table() + ", " + count(entity.columns().size(), "column", "columns")
                        : "model unavailable (" + entity.failure() + ")");
            }
            if (read.moreEntities() > 0) {
                section.row("more entities", "and " + read.moreEntities() + " more");
            }
            for (MansartDataCatalogue.Repository repository : read.repositories()) {
                if (repository.entityClassName() != null) {
                    section.row(repository.name(), repository.entityName() + ", "
                            + count(repository.methodCount(), "method", "methods"));
                }
            }
            for (MansartDataCatalogue.Repository repository : read.otherRepositories()) {
                section.row(repository.name(),
                        "no primary entity, " + count(repository.methodCount(), "method", "methods"));
            }
            if (read.moreRepositories() > 0) {
                section.row("more repositories", "and " + read.moreRepositories() + " more");
            }
        }
        for (MansartDataCatalogue.Entity entity : read.entities()) {
            if (entity.failure() != null) {
                section.anomaly(ANOMALY, "The model of entity " + entity.className() + " could not be read ("
                        + entity.failure() + ")", HINT);
            }
        }
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static void checkDataSourceReachable(BeanManager bm) {
        Set<Bean<?>> dsBeans = bm.getBeans(DataSource.class, AnyLiteral.INSTANCE);
        if (dsBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Mansart Data extension is enabled but no DataSource bean is exposed by CDI. "
                            + "Either deploy the mansart-pool extension with vidocq.pool.url, or "
                            + "publish your own @Produces DataSource.");
        }
        // Resolve via lookup() so Default qualifier semantics apply; the @Any literal above is
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

    /**
     * The class of every bean. We cannot ask the BeanManager directly for an annotated type set without crossing CDI
     * Lite limits, so the repositories are picked among them.
     */
    private static List<Class<?>> beanClasses(BeanManager bm) {
        List<Class<?>> classes = new ArrayList<>();
        for (Bean<?> bean : bm.getBeans(Object.class, AnyLiteral.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass != null) {
                classes.add(beanClass);
            }
        }
        return classes;
    }

    private static void logRepositoryInventory(List<Class<?>> repositories) {
        if (repositories.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Mansart Data: no @Repository interface discovered. "
                            + "Did the APT processor (mansart-data-processor) run on the application module?");
        } else {
            LOG.log(System.Logger.Level.INFO,
                    "Mansart Data: " + repositories.size() + " repository(ies) wired: "
                            + repositories.stream().map(Class::getName).toList());
        }
    }

    /** {@code @Any} qualifier literal — kept off the hot path. */
    private static final class AnyLiteral
            extends jakarta.enterprise.util.AnnotationLiteral<Any> implements Any {
        static final AnyLiteral INSTANCE = new AnyLiteral();
        private AnyLiteral() {}
    }
}
