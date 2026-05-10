/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.vidocq.knock.tck.arquillian;

import io.vidocq.cassini.tck.CassiniTestHarness;
import io.vidocq.knock.cassini.KnockHealthResource;
import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Container Arquillian Knock — déploie un {@link WebArchive} TCK MicroProfile Health 4.0
 * sur une instance {@link CassiniTestHarness} en y exposant {@link KnockHealthResource}.
 *
 * <p>Stratégie :</p>
 * <ol>
 *   <li>Scanner {@code /WEB-INF/classes/} du WAR pour les implémentations
 *       {@link HealthCheck} qualifiées {@link Liveness}/{@link Readiness}/{@link Startup}
 *       (spec §4.2 — un check non qualifié est ignoré).</li>
 *   <li>Scanner les méthodes {@code @Produces @Liveness/@Readiness/@Startup} retournant
 *       un {@code HealthCheck} (cas CDI producer — spec §4.1).</li>
 *   <li>Construire un {@link HealthCheckRegistry} peuplé et l'attacher à un
 *       {@link KnockHealthResource}.</li>
 *   <li>Démarrer un {@link CassiniTestHarness} sur un port libre, exposer la ressource,
 *       retourner {@link ProtocolMetaData} avec le baseUrl pour que le client TCK
 *       puisse cibler {@code <base>/health*}.</li>
 * </ol>
 *
 * <p>Lecture de {@code microprofile-config.properties} (spec §6 — propriété
 * {@code mp.health.default.readiness.empty.response}) : honoré si le fichier est
 * présent dans {@code /META-INF/} du WAR. La valeur par défaut suit la spec
 * (DOWN pour readiness vide).</p>
 */
public class KnockDeployableContainer implements DeployableContainer<KnockContainerConfiguration> {

    private KnockContainerConfiguration config;
    private final LinkedHashMap<String, CassiniTestHarness> harnesses = new LinkedHashMap<>();

    @Override public Class<KnockContainerConfiguration> getConfigurationClass() {
        return KnockContainerConfiguration.class;
    }

    @Override public void setup(KnockContainerConfiguration cfg) { this.config = cfg; }

    @Override public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override public void start() throws LifecycleException { /* per-deployment */ }

    @Override public void stop() throws LifecycleException {
        harnesses.values().forEach(h -> { try { h.close(); } catch (RuntimeException ignored) {} });
        harnesses.clear();
    }

    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Étape 1 — collecter classes du WAR
        List<Class<?>> classes = new ArrayList<>();
        for (Node node : war.getContent().values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class") || !path.startsWith("/WEB-INF/classes/")) continue;
            String cn = path.substring("/WEB-INF/classes/".length(),
                    path.length() - ".class".length()).replace('/', '.');
            try { classes.add(Class.forName(cn, true, cl)); }
            catch (Throwable ignored) { /* classes manquantes — ignorées */ }
        }

        // Étape 2 — peupler registry depuis les classes scannées
        HealthCheckRegistry registry = HealthCheckRegistries.newRegistry();
        Set<String> registeredNames = new HashSet<>();
        for (Class<?> c : classes) {
            // Cas direct : implémentation HealthCheck + qualifier MP Health
            if (HealthCheck.class.isAssignableFrom(c)
                    && !c.isInterface()
                    && !Modifier.isAbstract(c.getModifiers())) {
                ProbeType type = qualifierOf(c);
                if (type != null) {
                    try {
                        var ctor = c.getDeclaredConstructor();
                        HealthCheck inst = (HealthCheck) ctor.newInstance();
                        injectFields(inst, new java.util.IdentityHashMap<>());
                        register(registry, type, c.getName(), inst, registeredNames);
                    } catch (ReflectiveOperationException e) {
                        throw new DeploymentException("cannot instantiate " + c.getName(), e);
                    }
                }
            }
            // Cas CDI producer : @Produces @Liveness/@Readiness/@Startup HealthCheck foo()
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Produces.class)) continue;
                if (!HealthCheck.class.isAssignableFrom(m.getReturnType())) continue;
                ProbeType type = qualifierOnAnnotated(m);
                if (type == null) continue;
                try {
                    Object owner = Modifier.isStatic(m.getModifiers())
                            ? null
                            : c.getDeclaredConstructor().newInstance();
                    // Producer method en MP Health TCK : package-private. Accès via
                    // MethodHandles.privateLookupIn pour rester JPMS-friendly.
                    var lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                            c, java.lang.invoke.MethodHandles.lookup());
                    var handle = lookup.unreflect(m);
                    HealthCheck check = (HealthCheck) (owner == null
                            ? handle.invoke()
                            : handle.invoke(owner));
                    register(registry, type, c.getName() + "#" + m.getName(), check, registeredNames);
                } catch (Throwable e) {
                    throw new DeploymentException("cannot invoke producer " + m, e);
                }
            }
        }

        // Étape 3 — lire microprofile-config.properties (§6) si présent
        applyConfig(war);

        // Étape 4 — démarrer le harness Cassini avec KnockHealthResource singleton
        KnockHealthResource resource = new KnockHealthResource(registry);
        Application app = new Application() {
            @Override public Set<Object> getSingletons() { return Set.of(resource); }
        };
        CassiniTestHarness harness;
        try {
            harness = CassiniTestHarness.builder()
                    .application(app)
                    .resource(resource)
                    .start();
        } catch (RuntimeException e) {
            throw new DeploymentException("failed to start Cassini harness", e);
        }
        harnesses.put(archive.getName(), harness);

        // Étape 5 — exposer baseUrl au TCK
        URI base = URI.create(harness.baseUrl().isEmpty()
                ? "http://" + config.getHost() + ":" + harness.port()
                : harness.baseUrl().startsWith("http")
                    ? harness.baseUrl()
                    : "http://" + config.getHost() + ":" + harness.port());

        ProtocolMetaData pmd = new ProtocolMetaData();
        HTTPContext ctx = new HTTPContext(
                base.getHost() == null ? config.getHost() : base.getHost(),
                base.getPort() < 0 ? harness.port() : base.getPort());
        ctx.add(new Servlet("knock-health", "/"));
        pmd.addContext(ctx);

        System.err.println("[KnockTCK] deploy archive=" + war.getName()
                + " checks=" + registeredNames + " baseUrl=" + harness.baseUrl());
        return pmd;
    }

    @Override public void undeploy(Archive<?> archive) {
        CassiniTestHarness h = harnesses.remove(archive.getName());
        if (h != null) {
            try { h.close(); } catch (RuntimeException ignored) {}
        }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    // ------------------------------------------------------------------ helpers

    private static ProbeType qualifierOf(Class<?> c) {
        if (c.isAnnotationPresent(Liveness.class))  return ProbeType.LIVENESS;
        if (c.isAnnotationPresent(Readiness.class)) return ProbeType.READINESS;
        if (c.isAnnotationPresent(Startup.class))   return ProbeType.STARTUP;
        return null;
    }

    private static ProbeType qualifierOnAnnotated(java.lang.reflect.AnnotatedElement el) {
        if (el.isAnnotationPresent(Liveness.class))  return ProbeType.LIVENESS;
        if (el.isAnnotationPresent(Readiness.class)) return ProbeType.READINESS;
        if (el.isAnnotationPresent(Startup.class))   return ProbeType.STARTUP;
        return null;
    }

    private static void register(HealthCheckRegistry r, ProbeType type, String name,
                                 HealthCheck check, Set<String> seen) {
        if (seen.add(name)) {
            r.register(type, name, check);
        }
    }

    /**
     * Mini-CDI : pour chaque champ {@link Inject} de {@code instance}, instancie
     * le type déclaré avec son constructeur public no-arg et l'affecte. Récurse
     * sur la chaîne des dépendances. Tolère le cycle via {@code seen} (identité).
     *
     * <p>Ce mini-CDI ne gère ni qualifiers, ni scopes, ni producers — il existe
     * uniquement pour rendre les tests TCK MP Health 4.0 type
     * {@code DelegateHealthSuccessfulTest} fonctionnels sans embarquer Weld.
     * L'écosystème Vidocq utilise Vauban pour le CDI complet en production.</p>
     */
    private static void injectFields(Object instance, java.util.IdentityHashMap<Object, Object> seen) {
        if (instance == null || seen.put(instance, instance) != null) return;
        Class<?> c = instance.getClass();
        while (c != null && c != Object.class) {
            for (var f : c.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Inject.class)) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> type = f.getType();
                try {
                    var ctor = type.getDeclaredConstructor();
                    Object dep = ctor.newInstance();
                    injectFields(dep, seen);
                    var lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                            c, java.lang.invoke.MethodHandles.lookup());
                    var setter = lookup.unreflectSetter(f);
                    setter.invoke(instance, dep);
                } catch (Throwable e) {
                    throw new RuntimeException(
                            "mini-CDI: cannot inject field " + f + " on " + instance, e);
                }
            }
            c = c.getSuperclass();
        }
    }

    /**
     * §6 — Lit la propriété {@code mp.health.default.readiness.empty.response} depuis
     * {@code /META-INF/microprofile-config.properties} si présent. La pose en system
     * property pour que le runtime Knock la consomme.
     */
    private static void applyConfig(WebArchive war) {
        Node n = war.get("/META-INF/microprofile-config.properties");
        if (n == null || n.getAsset() == null) {
            n = war.get("/WEB-INF/classes/META-INF/microprofile-config.properties");
        }
        // Reset systématique des propriétés Knock entre déploiements pour éviter
        // les fuites entre tests TCK.
        System.clearProperty("mp.health.default.readiness.empty.response");
        System.clearProperty("mp.health.default.startup.empty.response");
        if (n == null || n.getAsset() == null) return;
        try (var in = n.getAsset().openStream()) {
            var props = new java.util.Properties();
            props.load(in);
            for (String key : new String[]{
                    "mp.health.default.readiness.empty.response",
                    "mp.health.default.startup.empty.response"}) {
                String v = props.getProperty(key);
                if (v != null) System.setProperty(key, v);
            }
        } catch (java.io.IOException ignored) {}
    }
}




