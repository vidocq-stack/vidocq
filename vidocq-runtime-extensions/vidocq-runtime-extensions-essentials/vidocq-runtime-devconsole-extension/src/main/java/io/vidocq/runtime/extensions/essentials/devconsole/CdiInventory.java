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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.container.BuiltInBean;
import io.vidocq.vauban.core.container.ManagedBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Named;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * What Vauban's metadata says of the application's beans, interceptors and observers, read once at boot, as strings
 * only: no {@link Class}, no {@link Bean}, nothing of the application is kept, so that the inventory outlives nothing.
 *
 * <p>The tables hold at most {@value #MAX_ROWS} rows, the console's limit, the application's first; each total says
 * how many rows were left out.
 *
 * @param scopes           the enabled beans by scope, most first, such as {@code application-scoped → 18}
 * @param beans            the enabled beans, all of them
 * @param applicationBeans those of the application
 * @param beanRows         the rows of the beans table
 * @param interceptors     the interceptors, all of them
 * @param applicationInterceptors those of the application
 * @param interceptorRows  the rows of the interceptors table
 * @param observers        the observer methods, all of them
 * @param applicationObservers those of the application
 * @param observerRows     the rows of the observers table
 * @param applicationRule  how the application's classes were told apart from the runtime's
 */
record CdiInventory(Map<String, Integer> scopes, int beans, int applicationBeans, List<List<String>> beanRows,
                    int interceptors, int applicationInterceptors, List<List<String>> interceptorRows,
                    int observers, int applicationObservers, List<List<String>> observerRows,
                    String applicationRule) {

    /** The rows of a table the console keeps. */
    static final int MAX_ROWS = 100;
    static final List<String> BEAN_COLUMNS = List.of("class", "kind", "scope", "qualifiers", "alternative", "from");
    static final List<String> INTERCEPTOR_COLUMNS = List.of("interceptor", "bindings", "priority", "from");
    static final List<String> OBSERVER_COLUMNS = List.of("event", "qualifiers", "observer", "mode", "from");

    private static final String APPLICATION = "application";
    private static final String RUNTIME = "runtime";
    /** The packages of the runtime when the application has no layer of its own. */
    private static final List<String> RUNTIME_PACKAGES = List.of("io.vidocq.", "jakarta.", "java.");
    private static final Comparator<List<String>> APPLICATION_FIRST = Comparator
            .comparing((List<String> row) -> !APPLICATION.equals(row.getLast()))
            .thenComparing(row -> row.getFirst());

    CdiInventory {
        scopes = Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
        beanRows = List.copyOf(beanRows);
        interceptorRows = List.copyOf(interceptorRows);
        observerRows = List.copyOf(observerRows);
    }

    /**
     * How the classes of the application are told apart from those of the runtime.
     *
     * @param rule          what the boot facts say of it
     * @param isApplication whether a class, by its binary name, is the application's; holds strings only
     */
    record ApplicationClasses(String rule, Predicate<String> isApplication) {

        ApplicationClasses {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(isApplication, "isApplication");
        }

        /**
         * The classes of the application's module layer, when Vidocq booted it in one; otherwise, in a flat launch,
         * every class outside the {@code io.vidocq}, {@code jakarta} and {@code java} packages, and those of the
         * main module, whatever their package.
         *
         * @param layer      the application's layer, {@link io.vidocq.runtime.spi.ApplicationLayer#current()}
         * @param mainModule the module the JVM was launched with, {@code jdk.module.main}, or {@code null}
         */
        static ApplicationClasses of(Optional<ModuleLayer> layer, String mainModule) {
            if (layer.isPresent()) {
                Set<String> packages = packages(layer.get().modules());
                return new ApplicationClasses("the classes of the application's module layer",
                        name -> packages.contains(packageOf(name)));
            }
            Set<String> main = mainModule == null ? Set.of()
                    : ModuleLayer.boot().findModule(mainModule).map(module -> packages(Set.of(module)))
                            .orElse(Set.of());
            return new ApplicationClasses("the classes outside the io.vidocq, jakarta and java packages, and those "
                    + "of the main module: this launch has no application layer",
                    name -> main.contains(packageOf(name))
                            || RUNTIME_PACKAGES.stream().noneMatch(name::startsWith));
        }

        private static Set<String> packages(Set<Module> modules) {
            Set<String> packages = new HashSet<>();
            modules.forEach(module -> packages.addAll(module.getPackages()));
            return Set.copyOf(packages);
        }
    }

    /**
     * Reads the metadata of {@code container}: the enabled beans its bean manager knows, of any type and qualifier,
     * its interceptors and its observer methods. Creates no bean and reads no context.
     *
     * @param container   the running container
     * @param application how to tell the application's classes apart
     */
    static CdiInventory read(VaubanContainer container, ApplicationClasses application) {
        return of(container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE),
                container.interceptorManager().getInterceptors(), container.eventDispatcher().observers(),
                application);
    }

    /**
     * The inventory of these beans, interceptors and observers.
     *
     * @param beans        the enabled beans
     * @param interceptors the interceptors
     * @param observers    the observer methods
     * @param application  how to tell the application's classes apart
     */
    static CdiInventory of(Collection<? extends Bean<?>> beans, List<InterceptorDescriptor> interceptors,
                           List<ObserverDescriptor> observers, ApplicationClasses application) {
        Predicate<String> ofApplication = application.isApplication();
        Map<String, Integer> scopes = new TreeMap<>();
        List<List<String>> beanRows = new ArrayList<>();
        for (Bean<?> bean : beans) {
            String scope = scope(bean.getScope());
            scopes.merge(scope, 1, Integer::sum);
            String name = bean.getBeanClass().getName();
            beanRows.add(List.of(name, kind(bean), scope, qualifiers(bean.getQualifiers()),
                    bean.isAlternative() ? "yes" : "no", from(ofApplication, name)));
        }
        List<List<String>> interceptorRows = new ArrayList<>();
        for (InterceptorDescriptor interceptor : interceptors) {
            String name = interceptor.interceptorClass().value();
            interceptorRows.add(List.of(name, annotations(interceptor.bindings()),
                    interceptor.enabled() ? String.valueOf(interceptor.priority()) : "disabled: no @Priority",
                    from(ofApplication, name)));
        }
        List<List<String>> observerRows = new ArrayList<>();
        for (ObserverDescriptor observer : observers) {
            String name = observer.declaringClass().value();
            List<DotName> qualifiers = observer.qualifiers().stream().map(QualifierInstance::annotationName)
                    .filter(qualifier -> !qualifier.equals(QualifierInstance.ANY_NAME)).toList();
            observerRows.add(List.of(type(observer.eventType()), annotations(qualifiers),
                    name + "#" + observer.methodName(), observer.async() ? "async" : "sync",
                    from(ofApplication, name)));
        }
        Map<String, Integer> byCount = new LinkedHashMap<>();
        scopes.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> byCount.put(entry.getKey(), entry.getValue()));
        return new CdiInventory(byCount, beanRows.size(), ofApplication(beanRows), firstRows(beanRows),
                interceptorRows.size(), ofApplication(interceptorRows), firstRows(interceptorRows),
                observerRows.size(), ofApplication(observerRows), firstRows(observerRows),
                application.rule());
    }

    /**
     * A scope as the boot facts name it: {@code application-scoped} for {@code ApplicationScoped},
     * {@code dependent}, {@code singleton}.
     *
     * @param scope the scope annotation, {@code null} for {@code @Dependent}
     */
    static String scope(Class<? extends Annotation> scope) {
        if (scope == null || scope == Dependent.class) {
            return "dependent";
        }
        String name = scope.getSimpleName();
        if (name.endsWith("Scoped") && name.length() > "Scoped".length()) {
            return name.substring(0, name.length() - "Scoped".length()).toLowerCase(Locale.ROOT) + "-scoped";
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * A type with the simple names of its classes, such as {@code List<Order>} or {@code int[]}.
     *
     * @param type the type, as Vauban's index keeps it
     */
    static String type(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType classType -> classType.name().simpleName();
            case TypeInfo.ParameterizedType parameterized -> parameterized.rawType().simpleName()
                    + parameterized.typeArguments().stream().map(CdiInventory::type)
                            .collect(Collectors.joining(", ", "<", ">"));
            case TypeInfo.ArrayType array -> type(array.componentType()) + "[]".repeat(array.dimensions());
            case TypeInfo.PrimitiveType primitive -> primitive.kind().name().toLowerCase(Locale.ROOT);
            case TypeInfo.TypeVariable variable -> variable.name();
            case TypeInfo.WildcardType wildcard -> wildcard.lowerBound() != null
                    ? "? super " + type(wildcard.lowerBound())
                    : wildcard.upperBound() == null || isObject(wildcard.upperBound()) ? "?"
                    : "? extends " + type(wildcard.upperBound());
            case TypeInfo.VoidType ignored -> "void";
        };
    }

    private static boolean isObject(TypeInfo type) {
        return type instanceof TypeInfo.ClassType classType && "java.lang.Object".equals(classType.name().value());
    }

    /** Where a bean comes from, as Vauban made it. */
    private static String kind(Bean<?> bean) {
        if (bean instanceof ManagedBean<?> managed) {
            BeanDescriptor.BeanKind kind = managed.descriptor().kind();
            return kind == null ? "managed" : kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return bean instanceof BuiltInBean<?> ? "built-in" : "other";
    }

    /** The qualifiers of a bean, {@code @Any} left out since every bean has it. */
    private static String qualifiers(Set<Annotation> qualifiers) {
        return qualifiers.stream().filter(qualifier -> !(qualifier instanceof Any)).map(CdiInventory::annotation)
                .sorted().collect(Collectors.joining(", "));
    }

    /** {@code @Named("clock")}, {@code @Default}: the simple name, and the members when there are some. */
    private static String annotation(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        if (annotation instanceof Named named) {
            return "@Named(\"" + named.value() + "\")";
        }
        String text = annotation.toString();
        String prefix = "@" + type.getName();
        String members = text.startsWith(prefix) ? text.substring(prefix.length()) : "";
        return "@" + type.getSimpleName() + ("()".equals(members) ? "" : members);
    }

    private static String annotations(Collection<DotName> names) {
        return names.stream().map(name -> "@" + name.simpleName()).sorted().collect(Collectors.joining(", "));
    }

    private static String from(Predicate<String> ofApplication, String className) {
        return ofApplication.test(className) ? APPLICATION : RUNTIME;
    }

    private static int ofApplication(List<List<String>> rows) {
        return (int) rows.stream().filter(row -> APPLICATION.equals(row.getLast())).count();
    }

    /** The application's rows first, then by their first cell; the first {@value #MAX_ROWS}. */
    private static List<List<String>> firstRows(List<List<String>> rows) {
        return rows.stream().sorted(APPLICATION_FIRST).limit(MAX_ROWS).toList();
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
