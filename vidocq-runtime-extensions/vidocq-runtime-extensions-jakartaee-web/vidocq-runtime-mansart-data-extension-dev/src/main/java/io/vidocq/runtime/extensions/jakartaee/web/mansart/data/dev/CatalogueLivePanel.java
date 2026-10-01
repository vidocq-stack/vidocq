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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Repository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Mansart Data section, live. Its <i>Monitoring</i> tab is the catalogue the runtime extension built at boot,
 * from {@link MansartDataLive}: one group per entity with its table, its columns and its repositories, then a group
 * for the repositories with no primary entity. {@link #sample} reads that holder and the calls kept in memory: no
 * bean, no connection, no query.
 *
 * <p>Its actions run the repositories' methods, one tab per repository (see {@link RepositoryActions}), then JDQL
 * statements in a <i>JDQL</i> tab (see {@link JdqlActions}, on Mansart's {@code JdqlExecutor.run}), which also
 * exports a query as CSV and imports a CSV file (see {@link CsvActions}, on {@code RepositoryRuntime.save}): built once
 * per boot by {@link #actions()}, which the console calls after {@link #start}, from the repository interfaces
 * {@link MansartDataLive} holds and the {@link BeanManager} {@code start} keeps; dropped by {@link #stop}. Its
 * {@link #languages()} gives that tab's query editors the {@code jdql} language (see {@link JdqlLanguage}).
 *
 * <p>A value key must match {@code [a-z][a-z0-9.-]{0,39}}: a repository's table is keyed by its name in kebab case,
 * {@code TaskRepository} as {@code task-repository}, its inherited methods by {@code <key>.inherits}, and the count
 * of its methods past the limit by {@code <key>.more}.
 */
public final class CatalogueLivePanel implements LivePanel {

    static final List<String> COLUMNS = List.of("field", "column", "type", "key", "nullable", "unique");
    static final List<String> METHODS = List.of("method", "kind", "query", "parameters", "returns");
    static final String OTHER = "Other repositories";

    /** The longest key base, so that {@code <key>.inherits} stays within the 40 characters of a key. */
    private static final int MAX_BASE = 40 - ".inherits".length();

    /** The keys an entity group already uses. */
    private static final Set<String> RESERVED = Set.of("table", "columns", "model");

    private static final System.Logger LOG = System.getLogger(CatalogueLivePanel.class.getName());

    /** The bean manager of this boot, {@code null} before {@link #start} and after {@link #stop}. */
    private volatile BeanManager beans;
    /** The actions of this boot, {@link RepositoryActions#NONE} until {@link #actions()} builds them. */
    private volatile RepositoryActions run = RepositoryActions.NONE;

    /** Created by the service loader. */
    public CatalogueLivePanel() {}

    @Override
    public String id() {
        return "mansart-data";
    }

    /** Keeps the bean manager the actions resolve the repositories and the transaction manager with. */
    @Override
    public void start(ExtensionContext context) {
        run = RepositoryActions.NONE;
        try {
            beans = context.beanManager();
        } catch (RuntimeException | LinkageError unavailable) {
            beans = null;
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: no bean manager, no action: "
                    + unavailable.getClass().getName());
        }
    }

    /** Drops the bean manager, the actions and their history: nothing of this boot outlives a dev reload. */
    @Override
    public void stop() {
        beans = null;
        run = RepositoryActions.NONE;
    }

    /** The run actions of this boot, built now; none before {@link #start}, or without a catalogue. */
    @Override
    public List<PanelAction> actions() {
        BeanManager manager = beans;
        if (manager == null) {
            return List.of();
        }
        return actions(BeanLookup.of(manager), TransactionRunner.of(manager), type -> EntityModels.of(type),
                RepositoryActions::accessible);
    }

    /** Builds the actions from what {@link MansartDataLive} holds, and keeps them for {@link #sample}. */
    List<PanelAction> actions(BeanLookup lookup, TransactionRunner transactions,
                              Function<Class<?>, EntityModel<?>> models, Predicate<Method> accessible) {
        Optional<MansartDataCatalogue> catalogue = MansartDataLive.catalogue();
        List<Class<?>> repositories = MansartDataLive.repositories();
        if (catalogue.isEmpty() || repositories.isEmpty()) {
            run = RepositoryActions.NONE;
            return List.of();
        }
        try {
            RepositoryActions built = RepositoryActions.build(repositories, catalogue.get(), lookup, transactions,
                    models, accessible, RepositoryActions.MAX_ACTIONS, JdqlRunner.MANSART, EntitySaver.MANSART);
            run = built;
            return built.actions();
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: the run actions could not be built: "
                    + failed.getClass().getName());
            run = RepositoryActions.NONE;
            return List.of();
        }
    }

    /**
     * The {@value JdqlLanguage#ID} language the JDQL tab's query editors complete and check with, built now from the
     * catalogue and the models; none before {@link #start}, as there is no action then either.
     */
    @Override
    public List<PanelLanguage> languages() {
        return beans == null ? List.of() : languages(type -> EntityModels.of(type));
    }

    /** The JDQL language of what {@link MansartDataLive} holds; none without an entity, or when it cannot be built. */
    List<PanelLanguage> languages(Function<Class<?>, EntityModel<?>> models) {
        Optional<MansartDataCatalogue> catalogue = MansartDataLive.catalogue();
        List<Class<?>> repositories = MansartDataLive.repositories();
        if (catalogue.isEmpty() || repositories.isEmpty() || catalogue.get().entities().isEmpty()) {
            return List.of();
        }
        try {
            return List.of(new PanelLanguage(JdqlLanguage.ID, JdqlLanguage.json(catalogue.get().entities(),
                    className -> RepositoryActions.load(className, repositories), models)));
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: no JDQL language: " + failed.getClass().getName());
            return List.of();
        }
    }

    @Override
    public void sample(PanelSample sample) {
        Optional<MansartDataCatalogue> published = MansartDataLive.catalogue();
        if (published.isEmpty()) {
            sample.absent("catalogue", "no catalogue yet");
            return;
        }
        MansartDataCatalogue catalogue = published.get();
        for (Entity entity : catalogue.entities()) {
            PanelSample group = sample.group(entity.name());
            if (entity.failure() == null) {
                group.text("table", entity.table());
                group.table("columns", COLUMNS, entity.columns().stream().map(CatalogueLivePanel::row).toList());
            } else {
                group.text("model", "unavailable: " + entity.failure());
            }
            Set<String> used = new HashSet<>(RESERVED);
            for (Repository repository : catalogue.repositoriesOf(entity)) {
                write(group, repository, used);
            }
        }
        List<Repository> others = catalogue.otherRepositories();
        if (!others.isEmpty()) {
            PanelSample group = sample.group(OTHER);
            Set<String> used = new HashSet<>(RESERVED);
            for (Repository repository : others) {
                write(group, repository, used);
            }
        }
        if (catalogue.moreEntities() > 0) {
            sample.text("more-entities", "and " + catalogue.moreEntities() + " more");
        }
        if (catalogue.moreRepositories() > 0) {
            sample.text("more-repositories", "and " + catalogue.moreRepositories() + " more");
        }
        run.sample(sample);
    }

    private static List<String> row(Column column) {
        return Arrays.asList(column.field(), column.column(), column.type(), column.key(),
                column.nullable() ? "yes" : "", column.unique() ? "yes" : "");
    }

    private static void write(PanelSample group, Repository repository, Set<String> used) {
        String key = key(repository.name(), used);
        group.table(key, METHODS, repository.methods().stream()
                .map(m -> Arrays.asList(m.name(), m.kind(), m.query(), m.parameters(), m.returns()))
                .toList());
        if (repository.inherits() != null && !repository.inherits().isEmpty()) {
            // the key says "inherits" already: the value keeps what follows it
            group.text(key + ".inherits", repository.inherits().replaceFirst("^inherits ", ""));
        }
        if (repository.moreMethods() > 0) {
            group.text(key + ".more", "and " + repository.moreMethods() + " more");
        }
    }

    /**
     * The key of a repository's table: its name in kebab case, {@code TaskRepository} as {@code task-repository},
     * starting with a letter, at most {@value #MAX_BASE} characters, and {@code -2}, {@code -3}… when {@code used}
     * already holds it. Adds the key to {@code used}.
     */
    static String key(String name, Set<String> used) {
        return key(name, used, MAX_BASE);
    }

    /**
     * {@code name} in kebab case as {@link #key(String, Set)} makes it, at most {@code max} characters, the suffix of a
     * clash included: the run actions build their ids {@code m.<repository>.<method>} with it. Adds it to {@code used}.
     */
    static String key(String name, Set<String> used, int max) {
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                boolean afterWord = (previous >= 'a' && previous <= 'z') || (previous >= '0' && previous <= '9');
                if (!out.isEmpty() && afterWord) {
                    out.append('-');
                }
                out.append((char) (c + ('a' - 'A')));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
            previous = c;
        }
        String base = out.toString();
        if (base.isEmpty() || base.charAt(0) < 'a' || base.charAt(0) > 'z') {
            base = "r-" + base;
        }
        base = trim(base, max);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = trim(base, max - suffix.length()) + suffix;
        }
        return key;
    }

    private static String trim(String key, int max) {
        String cut = key.length() > max ? key.substring(0, max) : key;
        while (cut.endsWith("-")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }
}
