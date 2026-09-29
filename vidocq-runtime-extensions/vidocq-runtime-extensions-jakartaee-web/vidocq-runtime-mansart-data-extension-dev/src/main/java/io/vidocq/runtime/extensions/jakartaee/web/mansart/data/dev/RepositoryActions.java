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

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.data.repository.Query;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The actions of the Mansart Data panel that run the repositories' methods (spec §2-§8), built once per boot by the
 * panel's {@code actions()}. Each repository is a group, a tab of the page; each runnable method one action,
 * {@code m.<repository key>.<method key>}, whose json argument {@code arguments} holds its parameters. A write asks
 * first and has a {@code transaction} argument, {@code rollback} (the default) or {@code commit}.
 *
 * <p>A call converts the arguments before anything else, then resolves the bean, then runs the method — for a write
 * in a transaction — and turns its result into JSON inside that transaction, so that a {@code Stream} is read before
 * it is rolled back. Every call is kept in a {@link CallHistory}.
 *
 * <p>After the repositories' tabs comes the <i>JDQL</i> tab of {@link JdqlActions}, then its {@link CsvActions}, whose
 * calls the same history keeps.
 *
 * <p>Holds the application's classes and bean manager for one boot: the panel drops it in {@code stop}.
 */
final class RepositoryActions {

    /** Before {@code actions()} and after {@code stop}: no action, nothing sampled. */
    static final RepositoryActions NONE = new RepositoryActions(null, TransactionRunner.NONE, null);
    /** The most actions of one panel the console keeps. */
    static final int MAX_ACTIONS = 128;
    /** The longest repository key of an id, so that the method key keeps at least 17 characters. */
    static final int MAX_REPOSITORY_KEY = 20;
    static final String ARGUMENTS = "arguments";
    static final String TRANSACTION = "transaction";
    static final List<String> NOT_RUNNABLE_COLUMNS = List.of("repository", "method", "reason");

    private static final int MAX_ID = 40;
    private static final String PREFIX = "m.";
    private static final System.Logger LOG = System.getLogger(RepositoryActions.class.getName());

    /**
     * One action.
     *
     * @param id             its id
     * @param label          its label, the method's name or signature
     * @param repositoryName the catalogue's name of its repository
     * @param repository     the repository interface
     * @param group          its tab
     * @param candidate      its method
     * @param signature      its schema and arguments
     * @param write          whether it writes
     */
    record Entry(String id, String label, String repositoryName, Class<?> repository, String group,
                 RepositoryMethods.Candidate candidate, Signature signature, boolean write) {}

    private final BeanLookup beans;
    private final TransactionRunner transactions;
    private final EntityJson entities;
    private final CallHistory history = new CallHistory();
    private final List<PanelAction> actions = new ArrayList<>();
    private final List<List<String>> notRunnable = new ArrayList<>();
    private int moreMethods;

    private RepositoryActions(BeanLookup beans, TransactionRunner transactions, EntityJson entities) {
        this.beans = beans;
        this.transactions = transactions;
        this.entities = entities;
    }

    /**
     * The actions of {@code repositories}, in the catalogue's name order.
     *
     * @param repositories the repository interfaces {@code MansartDataLive} holds
     * @param catalogue    the catalogue, for the names and the entities
     * @param beans        the beans, resolved at each call
     * @param transactions the transactions of a write
     * @param models       the model of an entity class, {@code EntityModels.of}
     * @param accessible   whether reflection may call a method, making it accessible; see {@link #accessible}
     * @param maxActions   the most actions, {@value #MAX_ACTIONS}; the rest is counted as {@code more-methods}
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions) {
        return build(repositories, catalogue, beans, transactions, models, accessible, maxActions, null);
    }

    /**
     * The actions of {@code repositories} and the <i>JDQL</i> tab's {@link JdqlActions}, without its CSV actions.
     *
     * @param jdql runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int, JdqlRunner,
     *      EntitySaver)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql) {
        return build(repositories, catalogue, beans, transactions, models, accessible, maxActions, jdql, null);
    }

    /**
     * The actions of {@code repositories}, in the catalogue's name order, then, when {@code jdql} is given and the
     * catalogue holds an entity, the {@value JdqlActions#COUNT} actions of the <i>JDQL</i> tab, followed, when
     * {@code saver} is given too, by its {@value CsvActions#COUNT} CSV actions; all keep their calls in the same
     * history, and the repositories get {@code maxActions} less those.
     *
     * @param jdql  runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @param saver saves an imported entity, {@link EntitySaver#MANSART} outside tests; {@code null} for no CSV action
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql,
                                   EntitySaver saver) {
        Set<String> entityNames = new HashSet<>();
        for (MansartDataCatalogue.Entity entity : catalogue.entities()) {
            entityNames.add(entity.className());
        }
        Map<String, String> names = new HashMap<>();
        for (MansartDataCatalogue.Repository repository : catalogue.repositories()) {
            names.put(repository.className(), repository.name());
            if (repository.entityClassName() != null) {
                entityNames.add(repository.entityClassName());
            }
        }
        RepositoryActions built = new RepositoryActions(beans, transactions, new EntityJson(models, entityNames));
        List<Class<?>> ordered = new ArrayList<>(repositories);
        ordered.sort(Comparator.comparing((Class<?> type) -> names.getOrDefault(type.getName(), type.getName())));
        Set<String> keys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        boolean withJdql = jdql != null && !catalogue.entities().isEmpty();
        boolean withCsv = withJdql && saver != null;
        int room = maxActions - (withJdql ? JdqlActions.COUNT : 0) - (withCsv ? CsvActions.COUNT : 0);
        for (Class<?> repository : ordered) {
            String name = names.getOrDefault(repository.getName(), repository.getName());
            built.add(repository, name, CatalogueLivePanel.key(name, keys, MAX_REPOSITORY_KEY), group(name, groups),
                    accessible, room);
        }
        if (withJdql) {
            String jdqlGroup = group(JdqlActions.GROUP, groups);
            JdqlActions tab = new JdqlActions(catalogue.entities(), className -> load(className, repositories),
                    models, built.entities, beans, transactions, jdql, built.history, jdqlGroup);
            built.actions.addAll(tab.actions());
            if (withCsv) {
                built.actions.addAll(new CsvActions(tab, built.entities, transactions, jdql, saver, built.history,
                        jdqlGroup).actions());
            }
        }
        return built;
    }

    /**
     * The class {@code className} as the application loads it: through the loader of the first repository that finds
     * it, an entity living with its repositories.
     *
     * @throws IllegalStateException when none does
     */
    static Class<?> load(String className, List<Class<?>> repositories) {
        for (Class<?> repository : repositories) {
            try {
                return Class.forName(className, false, repository.getClassLoader());
            } catch (ClassNotFoundException | LinkageError notThere) {
                // the next repository's loader may know it
            }
        }
        throw new IllegalStateException("class " + className + " not found");
    }

    /** Whether reflection may call {@code method}: its package open to this module; made accessible when it is. */
    static boolean accessible(Method method) {
        try {
            return method.trySetAccessible();
        } catch (SecurityException refused) {
            return false;
        }
    }

    /**
     * The tab of a repository: its name, cut from the left with {@code …} past {@value PanelAction#MAX_GROUP}
     * characters, {@code  2}, {@code  3}… when {@code used} already holds it. Adds it to {@code used}.
     */
    static String group(String name, Set<String> used) {
        String base = left(name, PanelAction.MAX_GROUP);
        String group = base;
        for (int n = 2; !used.add(group); n++) {
            String suffix = " " + n;
            group = left(base, PanelAction.MAX_GROUP - suffix.length()) + suffix;
        }
        return group;
    }

    private static String left(String name, int max) {
        return name.length() <= max ? name : "…" + name.substring(name.length() - (max - 1));
    }

    /** The actions, in page order. */
    List<PanelAction> actions() {
        return List.copyOf(actions);
    }

    /**
     * Writes, in the <i>Monitoring</i> tab, the methods that cannot run ({@code not-runnable}) and those past the
     * console's limit ({@code more-methods}); and the {@code calls} table, which the page shows in the repository tabs.
     */
    void sample(PanelSample out) {
        if (!notRunnable.isEmpty()) {
            out.table("not-runnable", NOT_RUNNABLE_COLUMNS, notRunnable);
        }
        if (moreMethods > 0) {
            out.text("more-methods", "and " + moreMethods + " more methods");
        }
        if (!actions.isEmpty()) {
            history.writeTo(out);
        }
    }

    private void add(Class<?> repository, String name, String key, String group, Predicate<Method> accessible,
                     int maxActions) {
        List<RepositoryMethods.Candidate> candidates;
        try {
            candidates = RepositoryMethods.of(repository);
        } catch (RuntimeException | LinkageError unreadable) {
            notRunnable.add(List.of(name, "", "methods unreadable: " + unreadable.getClass().getName()));
            return;
        }
        List<Signature> signatures = new ArrayList<>();
        Map<String, Integer> runnable = new HashMap<>();
        for (RepositoryMethods.Candidate candidate : candidates) {
            Signature signature = Signature.of(candidate, entities, accessible);
            signatures.add(signature);
            if (signature.reason() == null) {
                runnable.merge(candidate.method().getName(), 1, Integer::sum);
            }
        }
        Set<String> methodKeys = new HashSet<>();
        int maxMethodKey = MAX_ID - PREFIX.length() - key.length() - 1;
        for (int i = 0; i < candidates.size(); i++) {
            RepositoryMethods.Candidate candidate = candidates.get(i);
            Signature signature = signatures.get(i);
            if (signature.reason() != null) {
                notRunnable.add(List.of(name, candidate.signature(), signature.reason()));
                continue;
            }
            if (actions.size() >= maxActions) {
                moreMethods++;
                continue;
            }
            String method = candidate.method().getName();
            try {
                String id = PREFIX + key + "." + CatalogueLivePanel.key(method, methodKeys, maxMethodKey);
                String label = runnable.get(method) > 1 ? candidate.signature() : method;
                actions.add(action(new Entry(id, label, name, repository, group, candidate, signature,
                        WriteKinds.isWrite(candidate.method()))));
            } catch (IllegalArgumentException refused) {
                notRunnable.add(List.of(name, candidate.signature(), "the console refuses it: "
                        + refused.getMessage()));
            } catch (RuntimeException | LinkageError unreadable) {
                notRunnable.add(List.of(name, candidate.signature(), "unreadable: "
                        + unreadable.getClass().getName()));
            }
        }
    }

    private PanelAction action(Entry entry) {
        List<PanelAction.Argument> arguments = new ArrayList<>();
        arguments.add(PanelAction.Argument.json(ARGUMENTS, "Arguments", entry.signature().schema()));
        if (entry.write()) {
            arguments.add(new PanelAction.Argument(TRANSACTION, "Transaction",
                    runnerFor(entry.candidate().method(), entry.repository(), transactions).modes(), null, null));
        }
        String confirmation = entry.write() ? "Runs " + entry.repositoryName() + "."
                + entry.candidate().method().getName() + " against the database." : null;
        return new PanelAction(entry.id(), entry.label(), confirmation, arguments, given -> call(entry, given),
                entry.group(), description(entry.candidate()));
    }

    /** {@code JDQL: <query>}, then {@code name(param: Type, …) → Return}, then where an inherited method comes from. */
    static String description(RepositoryMethods.Candidate candidate) {
        StringBuilder out = new StringBuilder();
        Query query = candidate.method().getAnnotation(Query.class);
        if (query != null) {
            out.append("JDQL: ").append(query.value().strip()).append('\n');
        }
        out.append(candidate.method().getName()).append('(');
        for (int i = 0; i < candidate.names().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(candidate.names().get(i)).append(": ").append(candidate.types().get(i).getSimpleName());
        }
        out.append(") → ").append(candidate.returns());
        if (candidate.inherited()) {
            out.append("\ninherited from ").append(candidate.method().getDeclaringClass().getSimpleName());
        }
        return Failures.cut(out.toString(), PanelAction.MAX_DESCRIPTION - 1);
    }

    /**
     * The transactions {@code method} runs in: none of the console's when its own {@code @Transactional} escapes it
     * ({@link WriteKinds#escapesTransaction}), so that only {@code commit} is offered and no rollback is claimed;
     * {@code transactions} otherwise.
     */
    static TransactionRunner runnerFor(Method method, Class<?> repository, TransactionRunner transactions) {
        return WriteKinds.escapesTransaction(method, repository) ? TransactionRunner.NONE : transactions;
    }

    /** Runs {@code entry} with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult call(Entry entry, Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(ARGUMENTS, "{}");
        String mode = entry.write() ? given.getOrDefault(TRANSACTION, TransactionRunner.COMMIT) : null;
        Object parsed = null;
        PanelAction.ActionResult result;
        try {
            parsed = parse(sent);
            result = run(entry, arguments(entry, parsed), bean(entry), parsed, mode, start);
        } catch (Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    details(entry, parsed, mode));
        }
        history.add(entry.group(), System.currentTimeMillis(), entry.label(),
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), replay(entry, parsed, mode));
        return result;
    }

    private PanelAction.ActionResult run(Entry entry, Object[] arguments, Object bean, Object parsed, String mode,
                                         long start) {
        Method method = entry.candidate().method();
        boolean isVoid = method.getReturnType() == void.class;
        TransactionRunner.Outcome<ResultJson.Result> outcome =
                runnerFor(method, entry.repository(), transactions)
                        .run(mode, () -> ResultJson.of(invoke(method, bean, arguments), isVoid, entities));
        String details = details(entry, parsed, mode);
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + entry.id() + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        ResultJson.Result value = outcome.value();
        String summary = value.what() + (value.rows() ? " in " + millis(start) + " ms" : "")
                + (outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, value.body() == null ? null : PanelAction.ActionResult.JSON,
                value.body(), false, details);
    }

    private static Object invoke(Method method, Object bean, Object[] arguments) throws Throwable {
        try {
            return method.invoke(bean, arguments);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause() == null ? thrown : thrown.getCause();
        }
    }

    private static Object parse(String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(ARGUMENTS + ": " + unreadable.getMessage());
        }
    }

    private static Object[] arguments(Entry entry, Object parsed) throws Refused {
        try {
            return entry.signature().arguments(parsed);
        } catch (ArgumentException refused) {
            throw new Refused(refused.getMessage());
        } catch (RuntimeException | LinkageError failed) {
            throw new Refused(Failures.text(failed));
        }
    }

    private Object bean(Entry entry) throws Refused {
        try {
            return beans.reference(entry.repository());
        } catch (RuntimeException | LinkageError none) {
            throw new Refused("no bean for " + entry.repositoryName());
        }
    }

    /** What the page shows under "Exchange" (spec §6): the method, each argument, the transaction asked. */
    private static String details(Entry entry, Object parsed, String mode) {
        Map<?, ?> members = parsed instanceof Map<?, ?> map ? map : Map.of();
        List<Object> arguments = new ArrayList<>();
        for (Signature.Parameter parameter : entry.signature().parameters()) {
            arguments.add(Scalars.object("name", parameter.name(), "type", parameter.type().getSimpleName(),
                    "value", members.get(parameter.name())));
        }
        return Json.write(Scalars.object("method", entry.repositoryName() + "." + entry.candidate().signature(),
                "arguments", arguments, "transaction", mode == null ? "none" : mode));
    }

    /** {@code <action id> {"arguments": {...}, "transaction": "..."}}, or empty when the arguments were no object. */
    private static String replay(Entry entry, Object parsed, String mode) {
        if (!(parsed instanceof Map<?, ?>)) {
            return "";
        }
        Map<String, Object> values = mode == null ? Scalars.object(ARGUMENTS, parsed)
                : Scalars.object(ARGUMENTS, parsed, TRANSACTION, mode);
        return entry.id() + " " + Json.write(values);
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A call refused before the method runs: its message is the summary. */
    private static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
