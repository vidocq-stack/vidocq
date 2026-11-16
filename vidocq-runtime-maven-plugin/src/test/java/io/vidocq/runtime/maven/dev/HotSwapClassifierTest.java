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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.core.ReloadRequest;
import io.vidocq.runtime.maven.depgen.DepgenFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.vidocq.runtime.maven.dev.HotSwapProject.build;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HotSwapClassifierTest {

    /** Stand-ins of the APIs, under their real names, so the shipped declaration files apply. */
    private static final Map<String, String> APIS = Map.ofEntries(
            Map.entry("jakarta/enterprise/context/ApplicationScoped.java", runtime("jakarta.enterprise.context",
                    "ApplicationScoped")),
            Map.entry("jakarta/enterprise/context/Initialized.java", """
                    package jakarta.enterprise.context;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Initialized { Class<?> value(); }
                    """),
            Map.entry("jakarta/annotation/PostConstruct.java", runtime("jakarta.annotation", "PostConstruct")),
            Map.entry("jakarta/enterprise/inject/Produces.java", runtime("jakarta.enterprise.inject", "Produces")),
            Map.entry("jakarta/enterprise/event/Observes.java", runtime("jakarta.enterprise.event", "Observes")),
            Map.entry("jakarta/enterprise/event/Startup.java",
                    "package jakarta.enterprise.event; public class Startup {}"),
            Map.entry("jakarta/servlet/ServletConfig.java",
                    "package jakarta.servlet; public interface ServletConfig {}"),
            Map.entry("jakarta/servlet/Servlet.java",
                    "package jakarta.servlet; public interface Servlet { void init(ServletConfig c); }"),
            Map.entry("jakarta/servlet/GenericServlet.java", """
                    package jakarta.servlet;
                    public abstract class GenericServlet implements Servlet {
                        public void init(ServletConfig c) { init(); }
                        public void init() {}
                    }
                    """),
            Map.entry("jakarta/servlet/ServletContextEvent.java",
                    "package jakarta.servlet; public class ServletContextEvent {}"),
            Map.entry("jakarta/servlet/ServletContextListener.java", """
                    package jakarta.servlet;
                    public interface ServletContextListener { default void contextInitialized(ServletContextEvent e) {} }
                    """),
            Map.entry("jakarta/ws/rs/core/Application.java", """
                    package jakarta.ws.rs.core;
                    public class Application { public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); } }
                    """),
            Map.entry("jakarta/ws/rs/ext/Provider.java", runtime("jakarta.ws.rs.ext", "Provider")),
            Map.entry("com/vaadin/cdi/annotation/UIScoped.java", runtime("com.vaadin.cdi.annotation", "UIScoped")),
            Map.entry("com/vaadin/flow/server/ServiceInitEvent.java",
                    "package com.vaadin.flow.server; public class ServiceInitEvent {}"),
            Map.entry("com/vaadin/flow/server/VaadinServiceInitListener.java", """
                    package com.vaadin.flow.server;
                    public interface VaadinServiceInitListener { void serviceInit(ServiceInitEvent event); }
                    """));

    private static final StartTimeDeclarations CDI = new StartTimeDeclarations(
            Set.of("jakarta.enterprise.context.ApplicationScoped"), Set.of(),
            Set.of("jakarta.annotation.PostConstruct", "jakarta.enterprise.inject.Produces"),
            Set.of("jakarta.enterprise.event.Observes"),
            Set.of("jakarta.enterprise.event.Startup", "jakarta.enterprise.context.Initialized"), Set.of(),
            List.of("test"));

    @TempDir
    static Path shared;
    static Path apis;
    static StartTimeDeclarations shipped;

    @TempDir
    Path dir;
    private final List<String> warnings = new ArrayList<>();

    @BeforeAll
    static void compileTheApis() {
        apis = HotSwapFixtures.jar(shared, "apis", APIS, List.of());
        var read = new ArrayList<String>();
        shipped = StartTimeDeclarations.read(List.of(ExtensionDeclarationsTest.CORE, ExtensionDeclarationsTest.FOY,
                ExtensionDeclarationsTest.CASSINI, ExtensionDeclarationsTest.VAADIN), read::add);
        assertEquals(List.of(), read);
    }

    // Rule 1: nothing

    @Test
    void identicalOutputIsNothing() {
        var v1 = build().source("app/A.java", "package app; class A { int a() { return 1; } }");

        assertEquals(Optional.empty(), classify(CDI, v1, v1));
    }

    @Test
    void buildInfoFilesAloneAreNothing() {
        var v1 = build().source("app/A.java", "package app; class A {}")
                .resource("META-INF/vidocq/build-info/app.properties", "time=1");
        var v2 = build().source("app/A.java", "package app; class A {}")
                .resource("META-INF/vidocq/build-info/app.properties", "time=2");

        assertEquals(Optional.empty(), classify(CDI, v1, v2));
    }

    // Rule 2: a file added or removed

    @Test
    void anAddedOrRemovedClassSwaps() {
        var one = build().source("app/A.java", "package app; class A {}");
        var two = build().source("app/A.java", "package app; class A {}")
                .source("app/B.java", "package app; class B {}");

        assertSwap("B is a new class", classify(CDI, one, two));
        assertSwap("B was deleted", classify(CDI, two, one));
    }

    @Test
    void aNewAnonymousClassSwaps() {
        var v1 = build().source("app/MainView.java", "package app; class MainView { Object o = new Object(); }");
        var v2 = build().source("app/MainView.java", "package app; class MainView { Object o = new Object() {}; }");

        assertSwap("MainView$1 is a new class", classify(CDI, v1, v2));
    }

    @Test
    void anAddedResourceSwaps() {
        var v1 = build().source("app/A.java", "package app; class A {}");
        var v2 = build().source("app/A.java", "package app; class A {}").resource("application.properties", "a=1");

        assertSwap("application.properties was added", classify(CDI, v1, v2));
    }

    // Rule 3: a resource or module-info changed

    @Test
    void aChangedResourceSwaps() {
        var v1 = build().source("app/A.java", "package app; class A {}").resource("application.properties", "a=1");
        var v2 = build().source("app/A.java", "package app; class A {}").resource("application.properties", "a=2");

        assertSwap("application.properties changed", classify(CDI, v1, v2));
    }

    @Test
    void moduleInfoSwaps() {
        var v1 = build().source("module-info.java", "module app {}").source("app/A.java", "package app; class A {}");
        var v2 = build().source("module-info.java", "module app { requires java.sql; }")
                .source("app/A.java", "package app; class A {}");

        assertSwap("module-info changed", classify(CDI, v1, v2));
    }

    // Rule 4: not the project's own sources

    @Test
    void aGeneratedClassWithASourceUnderGeneratedSourcesSwaps() {
        var v1 = build().generated("app/Gen.java", "package app; class Gen { int a() { return 1; } }");
        var v2 = build().generated("app/Gen.java", "package app; class Gen { int a() { return 2; } }");

        assertSwap("Gen is not compiled from this project's sources", classify(CDI, v1, v2));
    }

    @Test
    void aClassWithNoSourceSwaps() {
        var v1 = build().classFile("app.Proxy", proxy(1));
        var v2 = build().classFile("app.Proxy", proxy(2));

        assertSwap("Proxy is not compiled from this project's sources", classify(CDI, v1, v2));
    }

    @Test
    void aClassOfAnotherClassRootSwaps() throws IOException {
        var project = new HotSwapProject(dir, List.of(apis));
        project.apply(build());
        Path sibling = HotSwapFixtures.compile(dir, "sibling1",
                Map.of("lib/Lib.java", "package lib; public class Lib { public int a() { return 1; } }"));
        var roots = List.of(project.classes, sibling);
        var base = ClassesSnapshot.take(roots, ClassesSnapshot.EMPTY);
        Files.write(sibling.resolve("lib/Lib.class"), HotSwapFixtures.bytes(HotSwapFixtures.compile(dir, "sibling2",
                Map.of("lib/Lib.java", "package lib; public class Lib { public int a() { return 2; } }")), "lib.Lib"));
        var next = ClassesSnapshot.take(roots, ClassesSnapshot.EMPTY);

        var result = new HotSwapClassifier(List.of(project.src), CDI)
                .classify(base, next, SupertypeIndex.of(next, List.of(apis), warnings::add));

        assertSwap("Lib is not compiled from this project's sources", result);
    }

    @Test
    void aNestedClassOfAnApplicationSourceIsTheProjects() {
        var v1 = build().source("app/MainView.java",
                "package app; class MainView { static class Inner { int a() { return 1; } } }");
        var v2 = build().source("app/MainView.java",
                "package app; class MainView { static class Inner { int a() { return 2; } } }");

        assertRedefine(classify(CDI, v1, v2), "app.MainView$Inner", "a");
    }

    // Rule 5: shape

    @Test
    void aNewLambdaSwapsNamingIt() {
        var v1 = build().source("app/MainView.java", """
                package app;
                class MainView { MainView() { Runnable a = () -> System.out.println("a"); a.run(); } }
                """);
        var v2 = build().source("app/MainView.java", """
                package app;
                class MainView {
                    MainView() {
                        Runnable a = () -> System.out.println("a"); a.run();
                        Runnable b = () -> System.out.println("b"); b.run();
                    }
                }
                """);

        assertSwap("MainView gained a method lambda$new$1", classify(CDI, v1, v2));
    }

    @Test
    void aChangedAnnotationSwaps() {
        var v1 = build().source("app/GreetingResource.java", "package app; class GreetingResource { String hello() "
                + "{ return \"a\"; } }");
        var v2 = build().source("app/GreetingResource.java", "package app; class GreetingResource { "
                + "@jakarta.annotation.PostConstruct String hello() { return \"a\"; } }");

        assertSwap("GreetingResource changed an annotation on hello()", classify(CDI, v1, v2));
    }

    // Rule 6: start-time code, from declarations given here

    @Test
    void aStaticInitializerSwapsWithNoDeclaration() {
        var v1 = build().source("app/A.java", "package app; class A { static int X = Integer.parseInt(\"1\"); }");
        var v2 = build().source("app/A.java", "package app; class A { static int X = Integer.parseInt(\"2\"); }");

        assertSwap("A's static initializer ran at start", classify(StartTimeDeclarations.EMPTY, v1, v2));
    }

    @Test
    void theConstructorAFieldInitializerAndAnInitializerMethodOfALongLivedBeanSwap() {
        String bean = """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                class Greeter {
                    String label = "%s";
                    Greeter() { System.out.println("%s"); }
                    @jakarta.annotation.PostConstruct void init() { System.out.println("%s"); }
                    String greet() { return "%s"; }
                }
                """;
        var base = build().source("app/Greeter.java", bean.formatted("a", "a", "a", "a"));

        assertSwap("Greeter's constructor ran at start",
                classify(CDI, base, build().source("app/Greeter.java", bean.formatted("b", "a", "a", "a"))));
        assertSwap("Greeter's constructor ran at start",
                classify(CDI, base, build().source("app/Greeter.java", bean.formatted("a", "b", "a", "a"))));
        assertSwap("Greeter.init() ran at start",
                classify(CDI, base, build().source("app/Greeter.java", bean.formatted("a", "a", "b", "a"))));
        assertRedefine(classify(CDI, base, build().source("app/Greeter.java", bean.formatted("a", "a", "a", "b"))),
                "app.Greeter", "greet");
    }

    @Test
    void theSameBeanWithNoDeclarationRedefines() {
        String bean = """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                class Greeter { Greeter() { System.out.println("%s"); } }
                """;

        assertRedefine(classify(StartTimeDeclarations.EMPTY, build().source("app/Greeter.java", bean.formatted("a")),
                build().source("app/Greeter.java", bean.formatted("b"))), "app.Greeter", "<init>");
    }

    @Test
    void aLongLivedTypeThroughAnApplicationSuperclass() {
        var servlets = declaring(Set.of(), Set.of("jakarta.servlet.Servlet"), Set.of());
        String hello = "package app; public class Hello extends Base { public Hello() { System.out.println(\"%s\"); } }";
        var base = "package app; public abstract class Base extends jakarta.servlet.GenericServlet {}";

        assertSwap("Hello's constructor ran at start", classify(servlets,
                build().source("app/Base.java", base).source("app/Hello.java", hello.formatted("a")),
                build().source("app/Base.java", base).source("app/Hello.java", hello.formatted("b"))));
    }

    @Test
    void aLongLivedTypeThroughAJarSuperclass() {
        var servlets = declaring(Set.of(), Set.of("jakarta.servlet.Servlet"), Set.of());
        String hello = "package app; public class Hello extends jakarta.servlet.GenericServlet "
                + "{ public Hello() { System.out.println(\"%s\"); } }";

        assertSwap("Hello's constructor ran at start", classify(servlets,
                build().source("app/Hello.java", hello.formatted("a")),
                build().source("app/Hello.java", hello.formatted("b"))));
    }

    @Test
    void aStartAnnotationOnAnyClass() {
        String producer = "package app; class Names { @jakarta.enterprise.inject.Produces String name() "
                + "{ return \"%s\"; } }";

        assertSwap("Names.name() ran at start", classify(CDI, build().source("app/Names.java", producer.formatted("a")),
                build().source("app/Names.java", producer.formatted("b"))));
    }

    @Test
    void anObserverOfAStartEventSwapsAndOfAnotherEventRedefines() {
        String observer = """
                package app;
                import jakarta.enterprise.event.Observes;
                import jakarta.enterprise.event.Startup;
                class Boot {
                    void start(@Observes Startup s) { System.out.println("%s"); }
                    void other(@Observes String s) { System.out.println("%s"); }
                    void ready(@Observes @jakarta.enterprise.context.Initialized(Object.class) Object o) {
                        System.out.println("%s");
                    }
                }
                """;
        var base = build().source("app/Boot.java", observer.formatted("a", "a", "a"));

        assertSwap("Boot.start() ran at start",
                classify(CDI, base, build().source("app/Boot.java", observer.formatted("b", "a", "a"))));
        assertRedefine(classify(CDI, base, build().source("app/Boot.java", observer.formatted("a", "b", "a"))),
                "app.Boot", "other");
        assertSwap("Boot.ready() ran at start",
                classify(CDI, base, build().source("app/Boot.java", observer.formatted("a", "a", "b"))));
    }

    @Test
    void aDeclaredCallback() {
        var callbacks = declaring(Set.of(), Set.of(),
                Set.of(new StartTimeDeclarations.Callback("jakarta.servlet.GenericServlet", "init", "()V")));
        String servlet = """
                package app;
                public class Hello extends jakarta.servlet.GenericServlet {
                    public void init() { System.out.println("%s"); }
                    public String hello() { return "%s"; }
                }
                """;
        var base = build().source("app/Hello.java", servlet.formatted("a", "a"));

        assertSwap("Hello.init() ran at start",
                classify(callbacks, base, build().source("app/Hello.java", servlet.formatted("b", "a"))));
        assertRedefine(classify(callbacks, base, build().source("app/Hello.java", servlet.formatted("a", "b"))),
                "app.Hello", "hello");
    }

    @Test
    void whatALongLivedConstructorCallsRanAtStart() {
        String sources = """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                class Greeter {
                    Greeter() { setup(); new Helper().prepare(); Step s = new FastStep(); s.run(); }
                    private void setup() { System.out.println("%s"); }
                }
                """;
        String helper = "package app; class Helper { void prepare() { System.out.println(\"%s\"); } }";
        String steps = "package app; abstract class Step { abstract void run(); }";
        String fast = "package app; class FastStep extends Step { void run() { System.out.println(\"%s\"); } }";
        var base = build().source("app/Greeter.java", sources.formatted("a"))
                .source("app/Helper.java", helper.formatted("a")).source("app/Step.java", steps)
                .source("app/FastStep.java", fast.formatted("a"));

        assertSwap("Greeter.setup() ran at start", classify(CDI, base,
                build().source("app/Greeter.java", sources.formatted("b")).source("app/Helper.java", helper.formatted("a"))
                        .source("app/Step.java", steps).source("app/FastStep.java", fast.formatted("a"))));
        assertSwap("Helper.prepare() ran at start", classify(CDI, base,
                build().source("app/Greeter.java", sources.formatted("a")).source("app/Helper.java", helper.formatted("b"))
                        .source("app/Step.java", steps).source("app/FastStep.java", fast.formatted("a"))));
        assertSwap("FastStep.run() ran at start", classify(CDI, base,
                build().source("app/Greeter.java", sources.formatted("a")).source("app/Helper.java", helper.formatted("a"))
                        .source("app/Step.java", steps).source("app/FastStep.java", fast.formatted("b"))));
    }

    @Test
    void aLambdaCreatedInALongLivedConstructorRedefines() {
        String bean = """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                class Greeter {
                    Runnable r;
                    Greeter() { r = () -> System.out.println("%s"); }
                }
                """;

        assertRedefine(classify(CDI, build().source("app/Greeter.java", bean.formatted("a")),
                build().source("app/Greeter.java", bean.formatted("b"))), "app.Greeter", "lambda$new$0");
    }

    @Test
    void anUnresolvedSupertypeCountsAsLongLived() {
        Path missing = HotSwapFixtures.jar(dir, "missing", Map.of("other/Base.java",
                "package other; public class Base {}"), List.of());
        var servlets = declaring(Set.of(), Set.of("jakarta.servlet.Servlet"), Set.of());
        String hello = "package app; class Hello extends other.Base { Hello() { System.out.println(\"%s\"); } }";
        var project = new HotSwapProject(dir, List.of(apis, missing));
        var base = project.apply(build().source("app/Hello.java", hello.formatted("a")));
        var next = project.apply(build().source("app/Hello.java", hello.formatted("b")));

        var result = new HotSwapClassifier(List.of(project.src), servlets)
                .classify(base, next, SupertypeIndex.of(next, List.of(apis), warnings::add));

        assertSwap("Hello's constructor ran at start", result);
        assertEquals(1, warnings.size(), warnings::toString);
    }

    // Rule 6, with the declaration files this repository ships

    @Test
    void theShippedDeclarationsMakeEachTechnologysStartCodeSwap() {
        assertShippedSwap("Greeter's constructor ran at start", "app/Greeter.java", """
                package app;
                @jakarta.enterprise.context.ApplicationScoped class Greeter { Greeter() { System.out.println("%s"); } }
                """);
        assertShippedSwap("Boot.start() ran at start", "app/Boot.java", """
                package app;
                class Boot { void start(@jakarta.enterprise.event.Observes jakarta.enterprise.event.Startup s) {
                    System.out.println("%s"); } }
                """);
        assertShippedSwap("Hello.init() ran at start", "app/Hello.java", """
                package app;
                public class Hello extends jakarta.servlet.GenericServlet {
                    public void init(jakarta.servlet.ServletConfig c) { System.out.println("%s"); }
                }
                """);
        assertShippedSwap("Listener.contextInitialized() ran at start", "app/Listener.java", """
                package app;
                public class Listener implements jakarta.servlet.ServletContextListener {
                    public void contextInitialized(jakarta.servlet.ServletContextEvent e) { System.out.println("%s"); }
                }
                """);
        assertShippedSwap("Rest.getClasses() ran at start", "app/Rest.java", """
                package app;
                public class Rest extends jakarta.ws.rs.core.Application {
                    public java.util.Set<Class<?>> getClasses() { System.out.println("%s"); return java.util.Set.of(); }
                }
                """);
        assertShippedSwap("Filter's constructor ran at start", "app/Filter.java", """
                package app;
                @jakarta.ws.rs.ext.Provider public class Filter { public Filter() { System.out.println("%s"); } }
                """);
        assertShippedSwap("Cart's constructor ran at start", "app/Cart.java", """
                package app;
                @com.vaadin.cdi.annotation.UIScoped public class Cart { public Cart() { System.out.println("%s"); } }
                """);
        assertShippedSwap("Init.serviceInit() ran at start", "app/Init.java", """
                package app;
                public class Init implements com.vaadin.flow.server.VaadinServiceInitListener {
                    public void serviceInit(com.vaadin.flow.server.ServiceInitEvent e) { System.out.println("%s"); }
                }
                """);
    }

    @Test
    void aVaadinViewConstructorRedefinesWithTheShippedDeclarations() {
        String view = "package app; public class MainView { public MainView() { System.out.println(\"%s\"); } }";

        assertRedefine(classify(shipped, build().source("app/MainView.java", view.formatted("a")),
                build().source("app/MainView.java", view.formatted("b"))), "app.MainView", "<init>");
    }

    // Declarations of a third-party jar, and of the application itself

    @Test
    void aThirdPartyDeclarationApplies() throws IOException {
        Path lib = DepgenFixtures.jar(dir, "lib", Map.of(
                "lib/Keep.java", runtime("lib", "Keep"),
                "lib/Plugin.java", "package lib; public interface Plugin { void boot(); }"), List.of(),
                Map.of(StartTimeDeclarations.FILE,
                        "long-lived-annotations = lib.Keep\nstart-callbacks = lib.Plugin#boot()V\n"));
        var declared = StartTimeDeclarations.read(List.of(lib), warnings::add);
        var project = new HotSwapProject(dir, List.of(lib));
        String sources = """
                package app;
                @lib.Keep class Kept { Kept() { System.out.println("%s"); } }
                """;
        String plugin = "package app; public class P implements lib.Plugin { public void boot() "
                + "{ System.out.println(\"%s\"); } }";
        var base = project.apply(build().source("app/Kept.java", sources.formatted("a"))
                .source("app/P.java", plugin.formatted("a")));
        var kept = project.apply(build().source("app/Kept.java", sources.formatted("b"))
                .source("app/P.java", plugin.formatted("a")));
        var classifier = new HotSwapClassifier(List.of(project.src), declared);

        assertSwap("Kept's constructor ran at start",
                classifier.classify(base, kept, SupertypeIndex.of(kept, List.of(lib), warnings::add)));
        var booted = project.apply(build().source("app/Kept.java", sources.formatted("a"))
                .source("app/P.java", plugin.formatted("b")));
        assertSwap("P.boot() ran at start",
                classifier.classify(base, booted, SupertypeIndex.of(booted, List.of(lib), warnings::add)));
    }

    @Test
    void theApplicationsOwnDeclarationApplies() {
        String file = "long-lived-annotations = app.Keep\n";
        String keep = runtime("app", "Keep");
        String kept = "package app; @Keep class Kept { Kept() { System.out.println(\"%s\"); } }";
        var project = new HotSwapProject(dir, List.of(apis));
        var base = project.apply(build().source("app/Keep.java", keep).source("app/Kept.java", kept.formatted("a"))
                .resource(StartTimeDeclarations.FILE, file));
        var next = project.apply(build().source("app/Keep.java", keep).source("app/Kept.java", kept.formatted("b"))
                .resource(StartTimeDeclarations.FILE, file));
        var declared = StartTimeDeclarations.read(List.of(project.classes), warnings::add);

        assertSwap("Kept's constructor ran at start", new HotSwapClassifier(List.of(project.src), declared)
                .classify(base, next, SupertypeIndex.of(next, List.of(apis), warnings::add)));
    }

    // Rule 7: redefine

    @Test
    void methodAndLambdaBodiesRedefine() {
        String view = """
                package app;
                class MainView {
                    Runnable listener() { return () -> System.out.println("%s"); }
                    String title() { return "%s"; }
                }
                """;
        var base = build().source("app/MainView.java", view.formatted("a", "a"));

        assertRedefine(classify(CDI, base, build().source("app/MainView.java", view.formatted("b", "a"))),
                "app.MainView", "lambda$listener$0");
        assertRedefine(classify(CDI, base, build().source("app/MainView.java", view.formatted("a", "b"))),
                "app.MainView", "title");
    }

    @Test
    void aBodyChangeThatMovesTheConstantPoolRedefinesOnlyThatMethod() {
        String a = "package app; class A { int foo() { return %s; } String bar() { return \"b\"; } }";

        assertRedefine(classify(CDI, build().source("app/A.java", a.formatted("1")),
                build().source("app/A.java", a.formatted("Integer.parseInt(\"42\") + \"new\".length()"))),
                "app.A", "foo");
    }

    @Test
    void aMovedLineRedefines() {
        assertRedefine(classify(CDI, build().source("app/A.java", "package app;\nclass A { int foo() { return 1; } }"),
                build().source("app/A.java", "package app;\nclass A {\n int foo() {\n\n return 1; } }")),
                "app.A", "foo");
    }

    @Test
    void theRequestListsEveryChangedClassWithItsMethods() {
        String a = "package app; class A { int one() { return %s; } int two() { return %s; } }";
        String b = "package app; class B { int three() { return %s; } }";

        var result = classify(CDI, build().source("app/A.java", a.formatted("1", "1")).source("app/B.java", b.formatted("1")),
                build().source("app/A.java", a.formatted("2", "2")).source("app/B.java", b.formatted("2")));

        var redefine = assertInstanceOf(ReloadRequest.Redefine.class, result.orElseThrow());
        assertEquals(List.of("app.A", "app.B"), List.copyOf(redefine.methodsByClass().keySet()));
        assertEquals(List.of("one", "two"), List.copyOf(redefine.methodsByClass().get("app.A")));
    }

    // helpers

    private Optional<ReloadRequest> classify(StartTimeDeclarations declarations, HotSwapProject.Build before,
            HotSwapProject.Build after) {
        var project = new HotSwapProject(dir.resolve("p" + System.nanoTime()), List.of(apis));
        var base = project.apply(before);
        var next = project.apply(after);
        return new HotSwapClassifier(List.of(project.src), declarations)
                .classify(base, next, SupertypeIndex.of(next, List.of(apis), warnings::add));
    }

    private void assertShippedSwap(String reason, String path, String source) {
        assertSwap(reason, classify(shipped, build().source(path, source.formatted("a")),
                build().source(path, source.formatted("b"))));
    }

    private static StartTimeDeclarations declaring(Set<String> annotations, Set<String> types,
            Set<StartTimeDeclarations.Callback> callbacks) {
        return new StartTimeDeclarations(annotations, types, Set.of(), Set.of(), Set.of(), callbacks, List.of("test"));
    }

    private static void assertSwap(String reason, Optional<ReloadRequest> result) {
        assertEquals(Optional.of(new ReloadRequest.Swap(reason)), result);
    }

    private static void assertRedefine(Optional<ReloadRequest> result, String binaryName, String... methods) {
        var redefine = assertInstanceOf(ReloadRequest.Redefine.class, result.orElseThrow(), () -> result.toString());
        assertEquals(List.of(binaryName), List.copyOf(redefine.methodsByClass().keySet()));
        assertEquals(List.of(methods), List.copyOf(redefine.methodsByClass().get(binaryName)));
    }

    private static String runtime(String pkg, String name) {
        return "package " + pkg + ";\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface " + name + " {}\n";
    }

    private static byte[] proxy(int value) {
        return ClassFile.of().build(ClassDesc.of("app.Proxy"), cb -> cb.withMethodBody("a",
                MethodTypeDesc.of(ConstantDescs.CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                cob -> cob.loadConstant(value).ireturn()));
    }
}
