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

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.container.CodegenCoverage;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's own {@code cdi} panel: what Vauban's metadata says of the beans, the interceptors and the observers,
 * read once, the application's first, as boot facts and as three tables.
 */
class CdiPanelTest {

    /** The application of these tests: the classes of {@code com.acme}. */
    private static final CdiInventory.ApplicationClasses ACME =
            new CdiInventory.ApplicationClasses("the com.acme packages", name -> name.startsWith("com.acme."));

    /** A bean as Vauban's bean manager lists it: metadata only, it creates nothing. */
    private record FakeBean(Class<?> beanClass, Class<? extends Annotation> scope, Set<Annotation> qualifiers,
                            boolean alternative) implements Bean<Object> {

        @Override
        public Class<?> getBeanClass() {
            return beanClass;
        }

        @Override
        public Set<InjectionPoint> getInjectionPoints() {
            return Set.of();
        }

        @Override
        public Object create(CreationalContext<Object> context) {
            throw new AssertionError("the panel created a bean");
        }

        @Override
        public void destroy(Object instance, CreationalContext<Object> context) {
            throw new AssertionError("the panel destroyed a bean");
        }

        @Override
        public Set<Type> getTypes() {
            return Set.of(beanClass, Object.class);
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return qualifiers;
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return scope;
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public Set<Class<? extends Annotation>> getStereotypes() {
            return Set.of();
        }

        @Override
        public boolean isAlternative() {
            return alternative;
        }
    }

    private static FakeBean bean(Class<?> type, Class<? extends Annotation> scope, Annotation... qualifiers) {
        return new FakeBean(type, scope, Set.of(qualifiers), false);
    }

    private static InterceptorDescriptor interceptor(String type, int priority, String... bindings) {
        Set<DotName> names = new java.util.LinkedHashSet<>();
        for (String binding : bindings) {
            names.add(DotName.of(binding));
        }
        return new InterceptorDescriptor(DotName.of(type), names, "intercept", priority);
    }

    private static ObserverDescriptor observer(String type, String method, TypeInfo event, boolean async,
                                               String... qualifiers) {
        List<QualifierInstance> instances = new ArrayList<>();
        for (String qualifier : qualifiers) {
            instances.add(new QualifierInstance(DotName.of(qualifier), Map.of()));
        }
        return new ObserverDescriptor(DotName.of(type), method, event, instances, async, 0);
    }

    private static TypeInfo type(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    /** An application with three beans, one interceptor of each side, and two observers. */
    private static CdiInventory inventory() {
        List<Bean<?>> beans = List.of(
                bean(String.class, Dependent.class, Default.Literal.INSTANCE, Any.Literal.INSTANCE),
                new FakeBean(com.acme.Clock.class, ApplicationScoped.class,
                        Set.of(NamedLiteral.of("clock"), Any.Literal.INSTANCE), true),
                bean(com.acme.Cart.class, RequestScoped.class, Default.Literal.INSTANCE, Any.Literal.INSTANCE),
                bean(Integer.class, ApplicationScoped.class, Default.Literal.INSTANCE, Any.Literal.INSTANCE));
        List<InterceptorDescriptor> interceptors = List.of(
                interceptor("io.vidocq.runtime.Logged", 0, "io.vidocq.runtime.Log"),
                interceptor("com.acme.Timed", 10, "com.acme.Timing", "com.acme.Audit"));
        List<ObserverDescriptor> observers = List.of(
                observer("io.vidocq.runtime.Boot", "started", type("jakarta.enterprise.event.Startup"), false),
                observer("com.acme.Orders", "placed", new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
                        List.of(type("com.acme.Order"))), true, "com.acme.Paid"));
        return CdiInventory.of(beans, interceptors, observers, ACME);
    }

    private static Map<String, List<String>> facts(ReportSection section) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        section.lines().forEach(line -> byKey.put(line.key(), line.values()));
        return byKey;
    }

    private static ReportSection contributed(CdiPanel panel) {
        RecordingSection section = new RecordingSection(CdiPanel.ID, panel.title());
        panel.contribute(new ConsoleReportContext(LaunchMode.DEV), section);
        return section.toSection();
    }

    private static Map<String, Object> sampled(CdiPanel panel) {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> sample, String key) {
        return ((List<Map<String, Object>>) sample.get("values")).stream().filter(v -> key.equals(v.get("key")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rows(Map<String, Object> table) {
        return (List<List<String>>) table.get("rows");
    }

    @Test
    void itIsTheConsolesOwnPanel() {
        CdiPanel panel = new CdiPanel(inventory());

        assertEquals("cdi", panel.id());
        assertEquals("CDI (Vauban)", panel.title());
        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("cdi", entry.id());
        assertEquals(panel, entry.panel(), "live: its tables are written by sample()");
        assertEquals(List.of(), entry.charts(), "nothing to plot: the metadata does not move");
    }

    @Test
    void theBootFactsCountTheBeansByScopeTheInterceptorsTheDecoratorsAndTheObservers() {
        ReportSection section = contributed(new CdiPanel(inventory()));

        assertEquals("4 beans (2 application-scoped, 1 dependent, 1 request-scoped), 2 interceptors, "
                + "0 decorators, 2 observers", section.summary());
        Map<String, List<String>> facts = facts(section);
        assertEquals(List.of("beans", "scopes", "interceptors", "decorators", "observers", "application",
                "beans codegen", "interceptors codegen", "observers codegen", "source"), List.copyOf(facts.keySet()));
        assertEquals(List.of("4 n/a"), facts.get("beans codegen"));
        assertEquals(List.of("2 n/a"), facts.get("interceptors codegen"));
        assertEquals(List.of("2 n/a"), facts.get("observers codegen"));
        assertEquals(List.of("4: 2 of the application, 2 of the libraries"), facts.get("beans"));
        assertEquals(List.of("2 application-scoped", "1 dependent", "1 request-scoped"), facts.get("scopes"));
        assertEquals(List.of("2: 1 of the application, 1 of the libraries"), facts.get("interceptors"));
        assertEquals(List.of("none: Vauban implements CDI Lite, which has no decorators"), facts.get("decorators"));
        assertEquals(List.of("2: 1 of the application, 1 of the libraries"), facts.get("observers"));
        assertEquals(List.of("the com.acme packages"), facts.get("application"));
        assertEquals(List.of("Vauban's metadata, read once at boot: no bean created"), facts.get("source"));
        assertTrue(section.lines().stream().map(ReportLine::key).noneMatch(key -> key.endsWith("left out")),
                "nothing left out of a table of four rows");
    }

    @Test
    void theBeansTableShowsTheApplicationsBeansFirst() {
        Map<String, Object> beans = table(sampled(new CdiPanel(inventory())), "beans");

        assertEquals("table", beans.get("kind"));
        assertEquals(List.of("class", "kind", "scope", "qualifiers", "alternative", "from", "codegen",
                "by reflection"), beans.get("columns"));
        assertEquals(List.of(
                List.of("com.acme.Cart", "other", "request-scoped", "@Default", "no", "application", "n/a", ""),
                List.of("com.acme.Clock", "other", "application-scoped", "@Named(\"clock\")", "yes", "application",
                        "n/a", ""),
                List.of("java.lang.Integer", "other", "application-scoped", "@Default", "no", "library", "n/a", ""),
                List.of("java.lang.String", "other", "dependent", "@Default", "no", "library", "n/a", "")),
                rows(beans));
    }

    @Test
    void theInterceptorsTableShowsTheirBindingsAndPriority() {
        Map<String, Object> interceptors = table(sampled(new CdiPanel(inventory())), "interceptors");

        assertEquals(List.of("interceptor", "bindings", "priority", "from", "codegen", "by reflection"),
                interceptors.get("columns"));
        assertEquals(List.of(
                List.of("com.acme.Timed", "@Audit, @Timing", "10", "application", "n/a", ""),
                List.of("io.vidocq.runtime.Logged", "@Log", "disabled: no @Priority", "library", "n/a", "")),
                rows(interceptors));
    }

    @Test
    void theObserversTableShowsTheEventItsQualifiersAndTheMethod() {
        Map<String, Object> observers = table(sampled(new CdiPanel(inventory())), "observers");

        assertEquals(List.of("event", "qualifiers", "observer", "mode", "from", "codegen", "by reflection"),
                observers.get("columns"));
        assertEquals(List.of(
                List.of("List<Order>", "@Paid", "com.acme.Orders#placed", "async", "application", "n/a", ""),
                List.of("Startup", "", "io.vidocq.runtime.Boot#started", "sync", "library", "n/a", "")),
                rows(observers));
    }

    @Test
    void theCodegenColumnsSayWhatCoversEachRowAndTheBootFactsCountThem() {
        Map<Class<?>, CodegenCoverage.Coverage> byClass = Map.of(
                com.acme.Cart.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.APT, List.of()),
                com.acme.Clock.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.PARTIAL,
                        List.of("field zone", "@PostConstruct start()")),
                String.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.REFLECTION, List.of("constructor")),
                Integer.class, new CodegenCoverage.Coverage(CodegenCoverage.Verdict.UNKNOWN, List.of("constructor")));
        CdiInventory.Coverages coverages = new CdiInventory.Coverages(bean -> byClass.get(bean.getBeanClass()),
                CdiInventory.Coverages.NONE.interceptors(), CdiInventory.Coverages.NONE.observers());
        CdiInventory inventory = CdiInventory.of(List.of(
                        bean(String.class, Dependent.class, Default.Literal.INSTANCE),
                        bean(com.acme.Clock.class, ApplicationScoped.class, Default.Literal.INSTANCE),
                        bean(com.acme.Cart.class, RequestScoped.class, Default.Literal.INSTANCE),
                        bean(Integer.class, ApplicationScoped.class, Default.Literal.INSTANCE)),
                List.of(), List.of(), ACME, coverages);
        CdiPanel panel = new CdiPanel(inventory);

        List<List<String>> rows = rows(table(sampled(panel), "beans"));
        assertEquals(List.of(
                List.of("APT", ""),
                List.of("partial", "field zone, @PostConstruct start()"),
                List.of("unknown", "provider predates coverage: constructor"),
                List.of("reflection", "constructor")),
                rows.stream().map(row -> row.subList(6, 8)).toList());
        assertEquals(List.of("1 APT, 1 partial, 1 reflection, 1 unknown"),
                facts(contributed(panel)).get("beans codegen"));
    }

    @Test
    void anEmptyTableIsWrittenAbsentWithItsReason() {
        CdiPanel panel = new CdiPanel(CdiInventory.of(List.of(), List.of(), List.of(), ACME));
        Map<String, Object> sample = sampled(panel);

        assertEquals(Map.of("key", "interceptors", "kind", "absent", "reason", "none"), table(sample, "interceptors"));
        assertEquals(Map.of("key", "observers", "kind", "absent", "reason", "none"), table(sample, "observers"));
        assertEquals("0 beans, 0 interceptors, 0 decorators, 0 observers", contributed(panel).summary());
    }

    @Test
    void aTableShowsAHundredRowsAndTheBootFactsSayHowManyWereLeftOut() {
        List<Bean<?>> beans = new ArrayList<>();
        for (int i = 0; i < 142; i++) {
            beans.add(bean(i % 2 == 0 ? com.acme.Cart.class : String.class, Dependent.class,
                    Default.Literal.INSTANCE));
        }
        CdiPanel panel = new CdiPanel(CdiInventory.of(beans, List.of(), List.of(), ACME));

        List<List<String>> rows = rows(table(sampled(panel), "beans"));
        assertEquals(100, rows.size());
        assertTrue(rows.subList(0, 71).stream().allMatch(row -> row.get(5).equals("application")),
                "the 71 beans of the application come first");
        assertEquals(List.of("42, past the 100 rows the table shows"), facts(contributed(panel)).get("beans left out"));
    }

    @Test
    void withoutAContainerThePanelSaysSoAndWritesNoTable() {
        CdiPanel panel = new CdiPanel(null);

        ReportSection section = contributed(panel);
        assertEquals("not available: no container", section.summary());
        assertEquals(List.of(), section.lines());
        assertNull(table(sampled(panel), "beans"));
    }

    @Test
    void theScopeOfABeanIsNamedAsTheBootFactsWriteIt() {
        assertEquals("application-scoped", CdiInventory.scope(ApplicationScoped.class));
        assertEquals("request-scoped", CdiInventory.scope(RequestScoped.class));
        assertEquals("dependent", CdiInventory.scope(Dependent.class));
        assertEquals("dependent", CdiInventory.scope(null));
        assertEquals("singleton", CdiInventory.scope(jakarta.inject.Singleton.class));
    }

    @Test
    void aTypeIsWrittenWithSimpleNames() {
        assertEquals("Map<String, List<Order>>", CdiInventory.type(new TypeInfo.ParameterizedType(
                DotName.of("java.util.Map"), List.of(type("java.lang.String"),
                new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(type("com.acme.Order")))))));
        assertEquals("int[][]", CdiInventory.type(new TypeInfo.ArrayType(
                new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 2)));
        assertEquals("Outer$Inner", CdiInventory.type(type("com.acme.Outer$Inner")));
    }

    @Test
    void theApplicationIsItsLayerWhenItHasOne() {
        CdiInventory.ApplicationClasses layer = CdiInventory.ApplicationClasses.of(Optional.of(ModuleLayer.boot()),
                null);

        Predicate<String> application = layer.isApplication();
        assertTrue(application.test("java.lang.String"), "a class of a module of that layer");
        assertFalse(application.test("org.example.Nowhere"), "a package no module of that layer has");
        assertEquals("the classes of the application's module layer", layer.rule());
    }

    @Test
    void withoutALayerTheRuntimeIsTheVidocqAndJakartaPackagesButTheMainModule() {
        CdiInventory.ApplicationClasses flat = CdiInventory.ApplicationClasses.of(Optional.empty(), "java.logging");

        Predicate<String> application = flat.isApplication();
        assertTrue(application.test("com.acme.Cart"));
        assertTrue(application.test("dev.langchain4j.cdi.mcp.server.McpEndpoint"));
        assertFalse(application.test("io.vidocq.runtime.core.Vidocq"));
        assertFalse(application.test("jakarta.enterprise.event.Event"));
        assertFalse(application.test("java.lang.String"));
        assertTrue(application.test("java.util.logging.Logger"), "a package of the main module is the application's");
        assertEquals("the classes outside the io.vidocq, jakarta and java packages, and those of the main module: "
                + "this launch has no application layer", flat.rule());
    }
}
