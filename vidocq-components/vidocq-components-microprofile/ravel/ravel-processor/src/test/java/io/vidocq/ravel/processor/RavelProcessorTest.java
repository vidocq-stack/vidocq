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
package io.vidocq.ravel.processor;

import io.vidocq.ravel.spi.GeneratedConfigProperties;
import io.vidocq.ravel.spi.GeneratedConverters;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code Ravel's processor generates fills {@code @ConfigProperties} classes and converts injected types the way
 * the reflective path does (MicroProfile Config 3.1 §6.4 and §5.2), with plain calls.
 */
class RavelProcessorTest {

    private static final String SETTINGS = """
            package com.example.app;

            import java.util.Optional;
            import java.util.OptionalInt;
            import org.eclipse.microprofile.config.inject.ConfigProperties;
            import org.eclipse.microprofile.config.inject.ConfigProperty;

            @ConfigProperties(prefix = "app")
            public class Settings {
                public String greeting = "hi";
                int times = 1;
                private long size;
                @ConfigProperty(name = "real-name")
                public String alias;
                @ConfigProperty(defaultValue = "7")
                public Integer fallback;
                public Optional<String> nick;
                public OptionalInt port;
                public Level level;
                public Money money;
                public static String ignored = "static";

                public long size() { return size; }
            }
            """;

    private static final String TYPES = """
            package com.example.app;

            public enum Level { LOW, HIGH }
            """;

    private static final String MONEY = """
            package com.example.app;

            public final class Money {
                public final String amount;
                private Money(String amount) { this.amount = amount; }
                public static Money of(String amount) {
                    if (amount.equals("bad")) throw new IllegalStateException("bad amount");
                    return new Money(amount);
                }
            }
            """;

    private static final String CONSUMER = """
            package com.example.app;

            import org.eclipse.microprofile.config.inject.ConfigProperty;

            public class Consumer {
                @ConfigProperty(name = "timeout") java.time.Duration timeout;
                @ConfigProperty(name = "home") java.util.Optional<java.net.URI> home;
            }
            """;

    private static final String FINAL_FIELD = """
            package com.example.other;

            import org.eclipse.microprofile.config.inject.ConfigProperties;

            @ConfigProperties
            public class Frozen {
                public final String value = "fixed";
            }
            """;

    @Test
    void theGeneratedFactoryFillsTheClassUnderItsPrefix(@TempDir Path dir) throws Exception {
        try (var loader = compile(dir, SETTINGS, TYPES, MONEY)) {
            var factory = factoryOf(loader, "com.example.app.Settings");
            assertEquals("app", factory.classPrefix());

            Object settings = factory.create("app", config(Map.of(
                    "app.greeting", "bonjour",
                    "app.times", "3",
                    "app.size", "42",
                    "app.real-name", "aliased",
                    "app.nick", "nn",
                    "app.port", "8080",
                    "app.level", "HIGH",
                    "app.money", "12.50")));

            Class<?> type = settings.getClass();
            assertEquals("bonjour", type.getField("greeting").get(settings));
            var times = type.getDeclaredField("times");
            times.setAccessible(true);
            assertEquals(3, times.get(settings));
            assertEquals(42L, type.getMethod("size").invoke(settings));
            assertEquals("aliased", type.getField("alias").get(settings));
            assertEquals(7, type.getField("fallback").get(settings), "the default value converts");
            assertEquals(Optional.of("nn"), type.getField("nick").get(settings));
            assertEquals(java.util.OptionalInt.of(8080), type.getField("port").get(settings));
            assertEquals("HIGH", type.getField("level").get(settings).toString());
            Object money = type.getField("money").get(settings);
            assertEquals("12.50", money.getClass().getField("amount").get(money));
            assertEquals("static", type.getField("ignored").get(null), "static fields are left alone");
        }
    }

    @Test
    void aFieldWithoutValueKeepsItsInitialValue(@TempDir Path dir) throws Exception {
        try (var loader = compile(dir, SETTINGS, TYPES, MONEY)) {
            Object settings = factoryOf(loader, "com.example.app.Settings").create("", config(Map.of()));

            assertEquals("hi", settings.getClass().getField("greeting").get(settings));
            assertEquals(Optional.empty(), settings.getClass().getField("nick").get(settings));
        }
    }

    @Test
    void aClassTheGeneratedCodeCannotFillKeepsTheReflectivePath(@TempDir Path dir) throws Exception {
        try (var loader = compile(dir, FINAL_FIELD)) {
            assertTrue(factories(loader).stream().noneMatch(f -> f.typeName().equals("com.example.other.Frozen")));
        }
    }

    @Test
    void theGeneratedConvertersCallTheFactoryTheSpecificationPicks(@TempDir Path dir) throws Exception {
        try (var loader = compile(dir, SETTINGS, TYPES, MONEY, CONSUMER)) {
            var converters = converters(loader);
            assertFalse(converters.isEmpty());

            var duration = convert(converters, java.time.Duration.class, "PT5S");
            assertEquals(java.time.Duration.ofSeconds(5), duration);
            assertEquals(java.net.URI.create("https://vidocq.dev"),
                    convert(converters, java.net.URI.class, "https://vidocq.dev"));
            Class<?> money = loader.loadClass("com.example.app.Money");
            Object amount = convert(converters, money, "3");
            assertEquals("3", money.getField("amount").get(amount));
            assertNull(convert(converters, money, ""), "an empty value converts to null");
            assertThrows(IllegalArgumentException.class, () -> convert(converters, money, "bad"));
            Class<?> level = loader.loadClass("com.example.app.Level");
            assertThrows(IllegalArgumentException.class, () -> convert(converters, level, "MEDIUM"));
            assertNull(firstConverter(converters, String.class), "a built-in type is left to Ravel");
        }
    }

    // ---- harness ----

    private static Config config(Map<String, String> values) {
        ConfigSource source = new ConfigSource() {
            @Override public Set<String> getPropertyNames() { return values.keySet(); }
            @Override public String getValue(String name) { return values.get(name); }
            @Override public String getName() { return "test"; }
        };
        return ConfigProviderResolver.instance().getBuilder().withSources(source).build();
    }

    private static Object convert(List<GeneratedConverters> all, Class<?> type, String value) {
        var converter = firstConverter(all, type);
        if (converter == null) {
            throw new AssertionError("no generated converter for " + type);
        }
        return converter.convert(value);
    }

    private static org.eclipse.microprofile.config.spi.Converter<?> firstConverter(
            List<GeneratedConverters> all, Class<?> type) {
        for (var converters : all) {
            var converter = converters.converter(type);
            if (converter != null) {
                return converter;
            }
        }
        return null;
    }

    private static GeneratedConfigProperties factoryOf(ClassLoader loader, String type) {
        return factories(loader).stream().filter(f -> f.typeName().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError("no generated factory for " + type));
    }

    private static List<GeneratedConfigProperties> factories(ClassLoader loader) {
        var all = new ArrayList<GeneratedConfigProperties>();
        ServiceLoader.load(GeneratedConfigProperties.class, loader).forEach(all::add);
        return all;
    }

    private static List<GeneratedConverters> converters(ClassLoader loader) {
        var all = new ArrayList<GeneratedConverters>();
        ServiceLoader.load(GeneratedConverters.class, loader).forEach(all::add);
        return all;
    }

    private static URLClassLoader compile(Path dir, String... sources) throws IOException {
        Path src = dir.resolve("src");
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        var files = new ArrayList<Path>();
        for (String source : sources) {
            String pkg = source.lines().filter(l -> l.startsWith("package ")).findFirst().orElseThrow()
                    .substring("package ".length()).replace(";", "").trim();
            String name = source.lines().filter(l -> l.contains(" class ") || l.contains(" enum "))
                    .findFirst().orElseThrow().replaceAll(".*(class|enum) (\\w+).*", "$2");
            Path file = src.resolve(pkg.replace('.', '/')).resolve(name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source, StandardCharsets.UTF_8);
            files.add(file);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            String classPath = System.getProperty("java.class.path") + File.pathSeparator
                    + System.getProperty("jdk.module.path", "");
            var task = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", out.toString(), "-s", out.toString(), "-classpath", classPath, "-proc:only"),
                    null, fileManager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new RavelProcessor()));
            assertTrue(task.call(), () -> "processing failed: " + diagnostics.getDiagnostics());
            var generated = new ArrayList<Path>(files);
            try (var walk = Files.walk(out)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(generated::add);
            }
            var compile = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", out.toString(), "-classpath", classPath, "-proc:none"),
                    null, fileManager.getJavaFileObjectsFromPaths(generated));
            assertTrue(compile.call(), () -> "compilation failed: " + diagnostics.getDiagnostics());
        }
        return new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, RavelProcessorTest.class.getClassLoader());
    }
}
