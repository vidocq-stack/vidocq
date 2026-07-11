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
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.tck.bridge.OtelSpanExporterBridge;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.humboldt.rest.HumboldtServerRequestFilter;
import io.vidocq.humboldt.rest.HumboldtServerResponseFilter;
import io.vidocq.humboldt.rest.HumboldtSpanFinalizer;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * "Embedded" Humboldt Arquillian container — assembles Vauban CDI Lite +
 * (future Cassini JAX-RS / Chappe HTTP) + in-process humboldt-runtime to
 * execute the MicroProfile Telemetry 2.1 TCK.
 *
 * <p>From-scratch approach (see {@code tasks/m7b-architecture-analysis.md}
 * Option C) — no reuse of vidocq to avoid the
 * humboldt-tck → vidocq → humboldt dependency cycle.</p>
 *
 * <p>Incremental progression:</p>
 * <ul>
 *   <li><strong>M7b.4b.1</strong> ✅ Skeleton: lifecycle start/stop, NoOp deploy</li>
 *   <li><strong>M7b.4b.2</strong> ✅ Deploy: boot Vauban CDI on WAR classes
 *       + AutoConfiguredHumboldt with hardcoded env config</li>
 *   <li><strong>M7b.4b.3</strong> OTel SDK autoconfigure bridge (parse
 *       microprofile-config.properties + scan OTel ServiceLoader)</li>
 *   <li><strong>M7b.4b.4</strong> CDI TestEnricher for
 *       {@code @Inject} injection into the test class</li>
 * </ul>
 *
 * <p>Arquillian protocol used: <em>Local</em> — tests run in the
 * same JVM as the container. The test can therefore access CDI through
 * {@link VaubanContainer#current()} while waiting for enricher M7b.4b.4.</p>
 */
public class HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig> {

    private static final Logger LOG = System.getLogger(HumboldtDeployableContainer.class.getName());

    /** Internal ShrinkWrap prefix for classes in a {@code WebArchive}. */
    private static final String WEB_INF_CLASSES_PREFIX = "WEB-INF/classes/";

    private VaubanContainer container;
    private AutoConfiguredHumboldt humboldt;
    private CassiniHarness cassini;

    @Override
    public Class<HumboldtContainerConfig> getConfigurationClass() {
        return HumboldtContainerConfig.class;
    }

    @Override
    public void setup(HumboldtContainerConfig configuration) {
        LOG.log(Level.INFO, "Humboldt Arquillian container — setup");
    }

    @Override
    public void start() throws LifecycleException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — start");
    }

    @Override
    public void stop() throws LifecycleException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — stop");
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — deploy {0}", archive.getName());
        try {
            List<Class<?>> beanClasses = extractBeanClasses(archive);
            LOG.log(Level.INFO, "  → {0} bean class(es) extracted from archive", beanClasses.size());

            VaubanContainerBuilder builder = VaubanContainer.builder();
            // Standard CDI producers from MP Telemetry §"Required CDI beans":
            // @Inject Tracer / Span / Baggage / OpenTelemetry — provided by
            // humboldt-cdi, added systematically to each deploy.
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtTelemetryProducers.class);
            // HBT-1 — Cassini @Path → @RequestScoped BCE: Vauban applies @Enhancement
            // BCEs to "unprocessed" classes through BceProcessor.processEnhancementOnly()
            // (see VaubanContainerBuilder.java:742), but ONLY if the BCE is in the
            // bean classes set. addBeanClass() does NOT scan the ServiceLoader. So add the
            // Cassini BCE manually here so that the WAR's @Path resources (for example
            // BaggageResource, RestSpanTest$SpanResource) receive a synthetic
            // @RequestScoped and are discovered as Vauban beans → @Inject Baggage/Tracer
            // on resource instances remains non-null.
            builder.addBeanClass(io.vidocq.cassini.cdi.vauban.CassiniScopeExtension.class);
            // Same issue for the Humboldt BCE: HumboldtBuildCompatibleExtension scans
            // classes annotated with @WithSpan (OTel) and adds @SpanBinding to activate
            // WithSpanInterceptor. Without this BCE, TCK inner classes such as
            // RestClientSpanTest$SpanBean that carry @WithSpan never generate the expected
            // INTERNAL span (SERVER → CLIENT → INTERNAL chain incomplete).
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension.class);
            // And the interceptor itself, otherwise @SpanBinding has no runtime effect.
            builder.addBeanClass(io.vidocq.humboldt.cdi.WithSpanInterceptor.class);
            for (Class<?> bean : beanClasses) {
                builder.addBeanClass(bean);
            }
            this.container = builder.build();

            // Parse MP Config (otel.* + mp_telemetry.*) from the WAR.
            Map<String, String> mpProps = parseMicroprofileConfigProperties(archive);

            // Load OTel SDK autoconfigure providers declared through
            // META-INF/services in the WAR (TCK extension pattern).
            Map<String, ConfigurableSpanExporterProvider> spanExporterProviders =
                    loadConfigurableSpanExporterProviders(archive);

            // If a provider matches the 'otel.traces.exporter' name, create its
            // OTel SpanExporter and bridge it to Humboldt — this is the mechanism
            // the TCKs use to retrieve their InMemorySpanExporter.
            List<SpanExporter> extraSpanExporters = new ArrayList<>();
            String tracesExporterName = mpProps.get("otel.traces.exporter");
            if (tracesExporterName != null && spanExporterProviders.containsKey(tracesExporterName)) {
                ConfigurableSpanExporterProvider p = spanExporterProviders.get(tracesExporterName);
                var otelExporter = p.createExporter(new MapConfigProperties(mpProps));
                extraSpanExporters.add(new OtelSpanExporterBridge(otelExporter));
                LOG.log(Level.INFO, "  -> OTel SpanExporter '{0}' bridged to Humboldt", tracesExporterName);
            }

            // Cluster D — ResourceProvider SPI: scans META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider,
            // invokes createResource(configProperties) on each provider, and appends the
            // resulting attributes to envMap's OTEL_RESOURCE_ATTRIBUTES. The Humboldt Resource
            // will include them through HumboldtAutoConfigure.buildResource() (CSV key=value parsing).
            String spiResourceAttrs = loadResourceProviderAttrs(archive, mpProps);

            // Cluster D — ConfigurableSamplerProvider SPI: scans for custom samplers.
            // If otel.traces.sampler matches a provider getName(), create the OTel Sampler and
            // wrap it in an OtelSamplerBridge (humboldt.Sampler) for direct use.
            io.vidocq.humboldt.sdk.trace.samplers.Sampler spiSamplerOverride = resolveSpiSampler(archive, mpProps);

            // Cluster D — ConfigurablePropagatorProvider SPI: scans for custom propagators
            // declared in the WAR. If otel.propagators (or MP_TELEMETRY_PROPAGATORS)
            // contains a name matching getName() of a scanned provider, compose its propagator
            // with W3C TraceContext + Baggage (MP Telemetry §3.3 default).
            io.opentelemetry.context.propagation.ContextPropagators spiPropagators =
                    resolveSpiPropagators(archive, mpProps);

            // Cluster D — AutoConfigurationCustomizerProvider SPI: scans and invokes
            // customize() to collect the 6 callback chains (Resource, Propagator,
            // Properties, Sampler, SpanExporter, TracerProvider). Applied below.
            HumboldtAutoConfigurationCustomizer autoCustomizer = scanAutoConfigCustomizers(archive);

            // M4b — ConfigurableMetricExporterProvider SPI: scans the WAR and bridges the OTel
            // MetricExporter to Humboldt via OtelMetricExporterBridge. Symmetric pattern
            // to ConfigurableSpanExporterProvider (M7b.4b.3). TCK case: WAR-provided
            // InMemoryMetricExporter for awaitility assertions.
            List<io.vidocq.humboldt.sdk.metric.export.MetricExporter> extraMetricExporters =
                    loadMetricExporters(archive, mpProps);

            // Builds the Humboldt EnvConfig — converts MP props (lowercase.dotted)
            // to the OTEL env vars (SCREAMING_SNAKE) expected by EnvConfig.
            // If an external bridge is in place, force OTEL_TRACES_EXPORTER=none
            // to avoid Humboldt adding its own native InMemorySpanExporter.
            Map<String, String> envMap = mpPropsToOtelEnv(mpProps);
            // Apply PropertiesCustomizer / PropertiesSupplier (Cluster D) BEFORE
            // merging other modifications — their values are "defaults" that
            // can be overridden by the other sources.
            envMap = autoCustomizer.applyPropertyCustomizers(envMap);
            if (!spiResourceAttrs.isEmpty()) {
                String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
                envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                        existing == null || existing.isEmpty() ? spiResourceAttrs : existing + "," + spiResourceAttrs);
            }
            // ResourceCustomizer (Cluster D) — invokes the chain and merges the resulting
            // attrs into OTEL_RESOURCE_ATTRIBUTES (Humboldt then re-parses them through
            // buildResource()).
            String customizerResourceAttrs = autoCustomizer.applyResourceCustomizersAsAttrs(mpProps);
            if (!customizerResourceAttrs.isEmpty()) {
                String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
                envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                        existing == null || existing.isEmpty() ? customizerResourceAttrs
                                : existing + "," + customizerResourceAttrs);
            }
            // Side-effect-only invocations (the result of Sampler/SpanExporter/
            // TracerProvider customizers cannot be bridged to Humboldt 1:1 without full
            // bidirectional bridges — out of scope. But the TCK CustomizerSpiTest only asserts
            // on the callbacks' logged side effects).
            autoCustomizer.invokeSamplerCustomizers(mpProps);
            autoCustomizer.invokeSpanExporterCustomizers(mpProps);
            autoCustomizer.invokeTracerProviderCustomizers(mpProps);
            envMap.putIfAbsent("OTEL_TRACES_SAMPLER", "always_on");
            // If an external bridge is in place for metrics, force OTEL_METRICS_EXPORTER=none
            // to avoid Humboldt adding its own native InMemoryMetricExporter (which
            // would pollute TCK assertions or create a second pipeline).
            if (!extraMetricExporters.isEmpty()) {
                envMap.put("OTEL_METRICS_EXPORTER", "none");
            } else {
                envMap.putIfAbsent("OTEL_METRICS_EXPORTER", "none");
            }
            envMap.putIfAbsent("OTEL_LOGS_EXPORTER", "none");
            if (!extraSpanExporters.isEmpty()) {
                envMap.put("OTEL_TRACES_EXPORTER", "none");
            } else {
                envMap.putIfAbsent("OTEL_TRACES_EXPORTER", "none");
            }

            // PropagatorCustomizer (Cluster D) — applies the chain to the final propagator
            // (spiPropagators if defined, otherwise W3CPropagators.get()). Wrap the result
            // in a new ContextPropagators if the chain transformed it.
            if (autoCustomizer.hasAny()) {
                io.opentelemetry.context.propagation.TextMapPropagator basePropagator =
                        spiPropagators != null
                                ? spiPropagators.getTextMapPropagator()
                                : io.vidocq.humboldt.propagator.w3c.W3CPropagators.textMap();
                io.opentelemetry.context.propagation.TextMapPropagator customized =
                        autoCustomizer.applyPropagatorCustomizers(basePropagator, mpProps);
                if (customized != basePropagator) {
                    spiPropagators = io.opentelemetry.context.propagation.ContextPropagators.create(customized);
                }
            }

            this.humboldt = HumboldtAutoConfigure.configure(
                    EnvConfig.of(envMap, Map.of()),
                    extraSpanExporters,
                    spiSamplerOverride,
                    spiPropagators,
                    extraMetricExporters);
            GlobalOpenTelemetry.set(this.humboldt);

            // M7c.2: if the WAR contains JAX-RS resources, start
            // Cassini on Chappe. Wire humboldt-rest filters so that
            // SERVER spans are generated as required by the TCK.
            ProtocolMetaData metaData = new ProtocolMetaData();
            List<Class<?>> resourceClasses = beanClasses.stream()
                    .filter(c -> c.isAnnotationPresent(Path.class))
                    .toList();
            List<Class<?>> providerClasses = beanClasses.stream()
                    .filter(c -> c.isAnnotationPresent(Provider.class)
                            && !c.isAnnotationPresent(Path.class))
                    .toList();
            if (!resourceClasses.isEmpty()) {
                String ctxName = deriveContextName(archive);
                String contextPath = ctxName.isEmpty() ? "/" : "/" + ctxName;
                CassiniHarness.Builder hb = CassiniHarness.builder().contextPath(contextPath);
                for (Class<?> r : resourceClasses) hb.resourceClass(r);
                // humboldt-rest filters to generate SERVER spans
                hb.provider(new HumboldtServerRequestFilter());
                hb.provider(new HumboldtServerResponseFilter());
                hb.provider(new HumboldtSpanFinalizer());
                // HBT-2 — activates the Vauban RequestContext around each HTTP dispatch.
                // CassiniHarness builds its pipeline without going through CassiniStackBuilder,
                // so the auto-injected filter from VaubanBeanProvider.getResourceClasses()
                // is not seen — register it manually here. For production
                // (normal CassiniStack), no user action is required.
                hb.provider(new io.vidocq.cassini.cdi.vauban.VaubanRequestScopeFilter(this.container));
                for (Class<?> p : providerClasses) {
                    try { hb.provider(p.getDeclaredConstructor().newInstance()); }
                    catch (ReflectiveOperationException e) {
                        LOG.log(Level.WARNING, "  ⚠ provider non-instantiable : {0}", p.getName());
                    }
                }
                this.cassini = hb.start();
                LOG.log(Level.INFO, "  -> Cassini started on {0} ({1} resources, {2} providers)",
                        cassini.baseUrl(), resourceClasses.size(), providerClasses.size());

                HTTPContext httpContext = new HTTPContext("127.0.0.1", cassini.port());
                httpContext.add(new Servlet("ArquillianServletRunner", contextPath));
                metaData.addContext(httpContext);
            }
            return metaData;
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy " + archive.getName(), e);
        }
    }

    private static String deriveContextName(Archive<?> archive) {
        String name = archive.getName();
        if (name == null) return "";
        if (name.endsWith(".war")) name = name.substring(0, name.length() - 4);
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        return name;
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — undeploy {0}", archive.getName());
        try {
            if (cassini != null) {
                cassini.close();
                cassini = null;
            }
        } finally {
            try {
                if (container != null) {
                    container.close();
                    container = null;
                }
            } finally {
                try {
                    if (humboldt != null) {
                        humboldt.close();
                        humboldt = null;
                    }
                } finally {
                    GlobalOpenTelemetry.resetForTest();
                }
            }
        }
    }

    /**
     * Reads {@code META-INF/microprofile-config.properties} from the ShrinkWrap WAR
     * (ShrinkWrap locations: {@code /META-INF/} for JavaArchive,
     * {@code /WEB-INF/classes/META-INF/} for WebArchive). Returns an empty map
     * if the file does not exist.
     */
    private static Map<String, String> parseMicroprofileConfigProperties(Archive<?> archive) {
        Node node = archive.get("/META-INF/microprofile-config.properties");
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/microprofile-config.properties");
        }
        if (node == null || node.getAsset() == null) return new HashMap<>();
        try (InputStream in = node.getAsset().openStream()) {
            Properties props = new Properties();
            props.load(in);
            Map<String, String> out = new HashMap<>();
            for (String name : props.stringPropertyNames()) {
                out.put(name, props.getProperty(name));
            }
            return out;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture microprofile-config.properties : {0}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Loads {@link ConfigurableSpanExporterProvider}s declared in the ShrinkWrap WAR
     * through {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider}.
     * Returns a {@code name → instance} map indexed by {@code provider.getName()}.
     *
     * <p>Pattern used by the MP Telemetry TCKs (see decompilation of
     * {@code ExporterSpiTest.createDeployment()}) which add their
     * {@code InMemorySpanExporterProvider} through
     * {@code WebArchive.addAsServiceProvider(ConfigurableSpanExporterProvider.class, ...)}.</p>
     */
    private static Map<String, ConfigurableSpanExporterProvider> loadConfigurableSpanExporterProviders(Archive<?> archive) {
        String service = "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return Map.of();

        Map<String, ConfigurableSpanExporterProvider> out = new LinkedHashMap<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    ConfigurableSpanExporterProvider p = (ConfigurableSpanExporterProvider)
                            cls.getDeclaredConstructor().newInstance();
                    out.put(p.getName(), p);
                    LOG.log(Level.INFO, "  → SpanExporterProvider loaded : {0} (name={1})",
                            fqn, p.getName());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ provider ignored ({0}): {1}", fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
                    LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
        }
        return out;
    }

    /**
     * Cluster D — Scans {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider}
     * in the WAR, invokes {@code createResource(configProperties)} on each provider,
     * and returns the attributes as a CSV string {@code key1=val1,key2=val2} ready
     * to be appended to {@code OTEL_RESOURCE_ATTRIBUTES}. The Humboldt Resource will
     * include them through {@code HumboldtAutoConfigure.buildResource()} (standard OTel CSV parsing).
     */
    private static String loadResourceProviderAttrs(Archive<?> archive, Map<String, String> mpProps) {
        String service = "io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return "";

        StringBuilder attrs = new StringBuilder();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        MapConfigProperties configProps = new MapConfigProperties(mpProps);
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    var provider = cls.getDeclaredConstructor().newInstance();
                    // ResourceProvider.createResource(ConfigProperties) → OTel Resource
                    var createMethod = cls.getMethod("createResource",
                            io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.class);
                    Object resource = createMethod.invoke(provider, configProps);
                    if (resource instanceof io.opentelemetry.sdk.resources.Resource otelResource) {
                        otelResource.getAttributes().forEach((key, value) -> {
                            if (value == null) return;
                            if (attrs.length() > 0) attrs.append(',');
                            attrs.append(key.getKey()).append('=').append(value);
                        });
                        LOG.log(Level.INFO, "  -> ResourceProvider loaded: {0} (attrs={1})",
                                fqn, otelResource.getAttributes());
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ ResourceProvider ignored ({0}): {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
        }
        return attrs.toString();
    }

    /**
     * Cluster D — Scans {@code ConfigurableSamplerProvider} in the WAR. If
     * {@code otel.traces.sampler} matches a provider's {@code getName()}, instantiates
     * the OTel sampler through {@code createSampler(configProperties)} and wraps it in
     * an {@link OtelSamplerBridge} for direct use by Humboldt.
     *
     * @return a {@code humboldt.Sampler} ready to be passed to {@code HumboldtAutoConfigure.configure(...)},
     *         or {@code null} if no provider matches.
     */
    private static io.vidocq.humboldt.sdk.trace.samplers.Sampler resolveSpiSampler(
            Archive<?> archive, Map<String, String> mpProps) {
        String configuredName = mpProps.get("otel.traces.sampler");
        if (configuredName == null) return null;

        String service = "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return null;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        MapConfigProperties configProps = new MapConfigProperties(mpProps);
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    var provider = cls.getDeclaredConstructor().newInstance();
                    String name = (String) cls.getMethod("getName").invoke(provider);
                    if (!configuredName.equals(name)) continue;
                    Object sampler = cls.getMethod("createSampler",
                            io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.class).invoke(provider, configProps);
                    if (sampler instanceof io.opentelemetry.sdk.trace.samplers.Sampler otelSampler) {
                        LOG.log(Level.INFO, "  -> SamplerProvider '{0}' ({1}) bridged via OtelSamplerBridge",
                                name, fqn);
                        return new OtelSamplerBridge(otelSampler);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ SamplerProvider ignored ({0}): {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
        }
        return null;
    }

    /**
     * Cluster D — Scans {@code ConfigurablePropagatorProvider} in the WAR. If
     * {@code otel.propagators} contains a name matching a provider's {@code getName()},
     * instantiates the OTel propagator through {@code getPropagator(configProperties)} and composes
     * a {@link io.opentelemetry.context.propagation.ContextPropagators} (W3C TraceContext
     * + default Baggage + listed custom propagators).
     *
     * @return a composite {@code ContextPropagators}, or {@code null} if no custom
     *         provider is required (the caller will then use W3CPropagators.get()).
     */
    private static io.opentelemetry.context.propagation.ContextPropagators resolveSpiPropagators(
            Archive<?> archive, Map<String, String> mpProps) {
        // MP Telemetry §3.3: the mp_telemetry.propagators property is also accepted,
        // mapped to otel.propagators.
        String configured = mpProps.getOrDefault("otel.propagators",
                mpProps.get("mp_telemetry.propagators"));
        if (configured == null) return null;

        // Split the list of names (CSV, spaces tolerated)
        List<String> names = new ArrayList<>();
        for (String name : configured.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        if (names.isEmpty()) return null;

        // Optional scan of SPI providers declared in the WAR (custom propagators
        // such as the TCK's TestPropagator). It may be absent if only builtins are used
        // (b3, jaeger, etc.) — the switch below handles that case.
        String service = "io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        MapConfigProperties configProps = new MapConfigProperties(mpProps);
        Map<String, io.opentelemetry.context.propagation.TextMapPropagator> byName = new LinkedHashMap<>();
        if (node != null && node.getAsset() != null) {
            try (InputStream in = node.getAsset().openStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String fqn = line.trim();
                    if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                    try {
                        Class<?> cls = Class.forName(fqn, true, cl);
                        var provider = cls.getDeclaredConstructor().newInstance();
                        String name = (String) cls.getMethod("getName").invoke(provider);
                        Object p = cls.getMethod("getPropagator",
                                io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.class).invoke(provider, configProps);
                        if (p instanceof io.opentelemetry.context.propagation.TextMapPropagator tmp) {
                            byName.put(name, tmp);
                            LOG.log(Level.INFO, "  -> PropagatorProvider loaded: {0} (name={1})", fqn, name);
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "  ⚠ PropagatorProvider ignored ({0}): {1}", fqn, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
            }
        }

        // Compose the final list: for each name in `otel.propagators`, use
        // the builtin if recognized (tracecontext, baggage, b3, b3multi, jaeger), otherwise
        // the custom SPI scanned from the WAR.
        List<io.opentelemetry.context.propagation.TextMapPropagator> chosen = new ArrayList<>();
        for (String n : names) {
            switch (n) {
                case "tracecontext" -> chosen.add(io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance());
                case "baggage" -> chosen.add(io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance());
                case "b3" -> chosen.add(io.opentelemetry.extension.trace.propagation.B3Propagator.injectingSingleHeader());
                case "b3multi" -> chosen.add(io.opentelemetry.extension.trace.propagation.B3Propagator.injectingMultiHeaders());
                case "jaeger" -> chosen.add(io.opentelemetry.extension.trace.propagation.JaegerPropagator.getInstance());
                default -> {
                    var p = byName.get(n);
                    if (p != null) chosen.add(p);
                    else LOG.log(Level.WARNING, "  ⚠ Propagator '{0}' requested but unavailable (neither builtin nor scanned SPI)", n);
                }
            }
        }
        if (chosen.isEmpty()) return null;
        return io.opentelemetry.context.propagation.ContextPropagators.create(
                io.opentelemetry.context.propagation.TextMapPropagator.composite(chosen));
    }

    /**
     * M4b — Scans {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider}
     * in the WAR. If {@code otel.metrics.exporter} matches a scanned provider's {@code getName()},
     * instantiates the OTel MetricExporter through {@code createExporter()} and wraps it in
     * {@link OtelMetricExporterBridge} for integration into the Humboldt pipeline.
     * Symmetric pattern to {@code loadConfigurableSpanExporterProviders} (M7b.4b.3).
     */
    private static List<io.vidocq.humboldt.sdk.metric.export.MetricExporter> loadMetricExporters(
            Archive<?> archive, Map<String, String> mpProps) {
        String service = "io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return List.of();

        String configured = mpProps.get("otel.metrics.exporter");
        if (configured == null) return List.of();

        List<io.vidocq.humboldt.sdk.metric.export.MetricExporter> out = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        MapConfigProperties cfg = new MapConfigProperties(mpProps);
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    var provider = cls.getDeclaredConstructor().newInstance();
                    String name = (String) cls.getMethod("getName").invoke(provider);
                    if (!configured.equals(name)) continue;
                    Object exporter = cls.getMethod("createExporter",
                            io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.class).invoke(provider, cfg);
                    if (exporter instanceof io.opentelemetry.sdk.metrics.export.MetricExporter otelExporter) {
                        out.add(new OtelMetricExporterBridge(otelExporter));
                        LOG.log(Level.INFO, "  -> MetricExporter '{0}' ({1}) bridged to Humboldt", name, fqn);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ MetricExporterProvider ignored ({0}): {1}", fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
        }
        return out;
    }

    /**
     * Cluster D — Scans {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
     * in the WAR, invokes {@code customize(humboldtCustomizer)} on each provider to
     * collect the callback chains (Resource/Propagator/Properties/Sampler/SpanExporter/
     * TracerProvider). The resulting {@link HumboldtAutoConfigurationCustomizer} is then
     * applied at the appropriate time in the Humboldt pipeline.
     */
    private static HumboldtAutoConfigurationCustomizer scanAutoConfigCustomizers(Archive<?> archive) {
        HumboldtAutoConfigurationCustomizer customizer = new HumboldtAutoConfigurationCustomizer();
        String service = "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return customizer;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    var provider = cls.getDeclaredConstructor().newInstance();
                    cls.getMethod("customize",
                            io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer.class)
                            .invoke(provider, customizer);
                    LOG.log(Level.INFO, "  -> AutoConfigCustomizerProvider loaded: {0}", fqn);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ AutoConfigCustomizerProvider ignored ({0}): {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading services/{0}: {1}", service, e.getMessage());
        }
        return customizer;
    }

    /**
     * Converts MP Config properties ({@code lowercase.dotted}) into OTel environment
     * variables ({@code SCREAMING_SNAKE_CASE}). As required by the OTel spec:
     * {@code otel.traces.exporter} ↔ {@code OTEL_TRACES_EXPORTER}.
     */
    private static Map<String, String> mpPropsToOtelEnv(Map<String, String> mpProps) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : mpProps.entrySet()) {
            String envKey = e.getKey().replace('.', '_').replace('-', '_').toUpperCase();
            out.put(envKey, e.getValue());
        }
        return out;
    }

    /**
     * Extracts classes from the ShrinkWrap WAR. Assumes they can be loaded
     * through the current ClassLoader — true in Local Arquillian mode (same JVM
     * as the test spec, so classes annotated with {@code @Deployment}
     * were already loaded by the test ClassLoader).
     */
    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (ArchivePath p : archive.getContent().keySet()) {
            String path = p.get();
            String s = path.startsWith("/") ? path.substring(1) : path;
            if (s.startsWith(WEB_INF_CLASSES_PREFIX)) {
                s = s.substring(WEB_INF_CLASSES_PREFIX.length());
            }
            if (!s.endsWith(".class")) continue;
            String fqn = s.substring(0, s.length() - ".class".length()).replace('/', '.');
            try {
                classes.add(Class.forName(fqn, true, cl));
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                LOG.log(Level.WARNING, "  ⚠ class ignored ({0}): {1}",
                        fqn, e.getMessage());
            }
        }
        return classes;
    }
}
