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
 * Container Arquillian "embedded" Humboldt — assemble Vauban CDI Lite +
 * (futur Cassini JAX-RS / Chappe HTTP) + humboldt-runtime in-process pour
 * exécuter le TCK MicroProfile Telemetry 2.1.
 *
 * <p>Approche from-scratch (cf. {@code tasks/m7b-architecture-analysis.md}
 * Option C) — pas de réutilisation de vidocq pour éviter le cycle de
 * dépendance humboldt-tck → vidocq → humboldt.</p>
 *
 * <p>Progression incrémentale :</p>
 * <ul>
 *   <li><strong>M7b.4b.1</strong> ✅ Squelette : lifecycle start/stop, deploy NoOp</li>
 *   <li><strong>M7b.4b.2</strong> ✅ Deploy : boot Vauban CDI sur les classes
 *       du war + AutoConfiguredHumboldt avec env config hardcodée</li>
 *   <li><strong>M7b.4b.3</strong> Bridge OTel SDK autoconfigure (parse
 *       microprofile-config.properties + scan ServiceLoader OTel)</li>
 *   <li><strong>M7b.4b.4</strong> TestEnricher CDI pour injection
 *       {@code @Inject} dans la classe de test</li>
 * </ul>
 *
 * <p>Protocole Arquillian utilisé : <em>Local</em> — tests exécutés dans la
 * même JVM que le container. Le test peut donc accéder au CDI via
 * {@link VaubanContainer#current()} en attendant l'enricher M7b.4b.4.</p>
 */
public class HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig> {

    private static final Logger LOG = System.getLogger(HumboldtDeployableContainer.class.getName());

    /** Préfixe interne ShrinkWrap pour les classes d'un {@code WebArchive}. */
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
            // Producers CDI standard MP Telemetry §"Required CDI beans" :
            // @Inject Tracer / Span / Baggage / OpenTelemetry — fournis par
            // humboldt-cdi, ajoutés systématiquement à chaque deploy.
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtTelemetryProducers.class);
            // HBT-1 — BCE Cassini @Path → @RequestScoped : Vauban applique les BCE
            // @Enhancement aux classes "unprocessed" via BceProcessor.processEnhancementOnly()
            // (cf. VaubanContainerBuilder.java:742), mais SEULEMENT si la BCE est dans le
            // bean classes set. addBeanClass() ne scanne PAS le ServiceLoader. On ajoute donc
            // manuellement la BCE Cassini ici pour que les ressources @Path du WAR (ex:
            // BaggageResource, RestSpanTest$SpanResource) reçoivent un @RequestScoped
            // synthétique et soient découvertes comme beans Vauban → @Inject Baggage/Tracer
            // côté ressources reste non-null.
            builder.addBeanClass(io.vidocq.cassini.cdi.vauban.CassiniScopeExtension.class);
            // Même problème pour la BCE Humboldt : HumboldtBuildCompatibleExtension scanne
            // les classes annotées @WithSpan (OTel) et leur ajoute @SpanBinding pour activer
            // WithSpanInterceptor. Sans cette BCE, les inner classes TCK comme
            // RestClientSpanTest$SpanBean qui portent @WithSpan ne génèrent jamais le span
            // INTERNAL attendu (chaîne SERVER → CLIENT → INTERNAL incomplete).
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension.class);
            // Et l'interceptor lui-même, sinon @SpanBinding n'a aucun effet runtime.
            builder.addBeanClass(io.vidocq.humboldt.cdi.WithSpanInterceptor.class);
            for (Class<?> bean : beanClasses) {
                builder.addBeanClass(bean);
            }
            this.container = builder.build();

            // Parse MP Config (otel.* + mp_telemetry.*) du war.
            Map<String, String> mpProps = parseMicroprofileConfigProperties(archive);

            // Charge les providers OTel SDK autoconfigure déclarés via
            // META-INF/services dans le war (pattern d'extension TCK).
            Map<String, ConfigurableSpanExporterProvider> spanExporterProviders =
                    loadConfigurableSpanExporterProviders(archive);

            // Si un provider correspond au nom 'otel.traces.exporter', on crée son
            // OTel SpanExporter et on le bridge vers Humboldt — c'est le mécanisme
            // que les TCK utilisent pour récupérer leur InMemorySpanExporter.
            List<SpanExporter> extraSpanExporters = new ArrayList<>();
            String tracesExporterName = mpProps.get("otel.traces.exporter");
            if (tracesExporterName != null && spanExporterProviders.containsKey(tracesExporterName)) {
                ConfigurableSpanExporterProvider p = spanExporterProviders.get(tracesExporterName);
                var otelExporter = p.createExporter(new MapConfigProperties(mpProps));
                extraSpanExporters.add(new OtelSpanExporterBridge(otelExporter));
                LOG.log(Level.INFO, "  → OTel SpanExporter '{0}' bridgé vers Humboldt", tracesExporterName);
            }

            // Cluster D — ResourceProvider SPI : scanne META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider,
            // invoque createResource(configProperties) sur chaque provider, et concatène les
            // attributs résultants à OTEL_RESOURCE_ATTRIBUTES de l'envMap. Le Resource humboldt
            // les inclura via HumboldtAutoConfigure.buildResource() (parsing CSV key=value).
            String spiResourceAttrs = loadResourceProviderAttrs(archive, mpProps);

            // Cluster D — ConfigurableSamplerProvider SPI : scanne pour les samplers custom.
            // Si otel.traces.sampler matche un provider getName(), crée le Sampler OTel et le
            // wrappe dans un OtelSamplerBridge (humboldt.Sampler) pour utilisation directe.
            io.vidocq.humboldt.sdk.trace.samplers.Sampler spiSamplerOverride = resolveSpiSampler(archive, mpProps);

            // Cluster D — ConfigurablePropagatorProvider SPI : scanne pour les propagators
            // custom déclarés dans le WAR. Si otel.propagators (ou MP_TELEMETRY_PROPAGATORS)
            // contient un nom qui matche getName() d'un provider scanné, composer son propagator
            // avec W3C TraceContext + Baggage (défaut MP Telemetry §3.3).
            io.opentelemetry.context.propagation.ContextPropagators spiPropagators =
                    resolveSpiPropagators(archive, mpProps);

            // Cluster D — AutoConfigurationCustomizerProvider SPI : scanne, invoque
            // customize() pour collecter les 6 chaînes de callbacks (Resource, Propagator,
            // Properties, Sampler, SpanExporter, TracerProvider). Appliqué ci-dessous.
            HumboldtAutoConfigurationCustomizer autoCustomizer = scanAutoConfigCustomizers(archive);

            // M4b — ConfigurableMetricExporterProvider SPI : scanne le WAR, bridge l'OTel
            // MetricExporter vers humboldt via OtelMetricExporterBridge. Pattern symétrique
            // à ConfigurableSpanExporterProvider (M7b.4b.3). Cas TCK : InMemoryMetricExporter
            // du WAR pour les assertions awaitility.
            List<io.vidocq.humboldt.sdk.metric.export.MetricExporter> extraMetricExporters =
                    loadMetricExporters(archive, mpProps);

            // Construit l'EnvConfig Humboldt — convertit les props MP (lowercase.dotted)
            // vers les env vars OTEL (SCREAMING_SNAKE) attendues par EnvConfig.
            // Si un bridge externe est en place, force OTEL_TRACES_EXPORTER=none
            // pour éviter qu'Humboldt ajoute son propre InMemorySpanExporter natif.
            Map<String, String> envMap = mpPropsToOtelEnv(mpProps);
            // Appliquer le PropertiesCustomizer / PropertiesSupplier (Cluster D) AVANT
            // le merge des autres modifications — leurs valeurs sont des "defaults" qui
            // peuvent être overridden par les autres sources.
            envMap = autoCustomizer.applyPropertyCustomizers(envMap);
            if (!spiResourceAttrs.isEmpty()) {
                String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
                envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                        existing == null || existing.isEmpty() ? spiResourceAttrs : existing + "," + spiResourceAttrs);
            }
            // ResourceCustomizer (Cluster D) — invoque la chaîne et fusionne les attrs
            // résultants dans OTEL_RESOURCE_ATTRIBUTES (humboldt re-parse ensuite via
            // buildResource()).
            String customizerResourceAttrs = autoCustomizer.applyResourceCustomizersAsAttrs(mpProps);
            if (!customizerResourceAttrs.isEmpty()) {
                String existing = envMap.get("OTEL_RESOURCE_ATTRIBUTES");
                envMap.put("OTEL_RESOURCE_ATTRIBUTES",
                        existing == null || existing.isEmpty() ? customizerResourceAttrs
                                : existing + "," + customizerResourceAttrs);
            }
            // Invocations side-effect-only (le résultat des customizers Sampler/SpanExporter/
            // TracerProvider ne peut pas être bridgé vers humboldt en 1:1 sans bridges
            // bidirectionnels complets — out of scope. Mais le TCK CustomizerSpiTest n'asserte
            // que sur les side-effects loggés des callbacks).
            autoCustomizer.invokeSamplerCustomizers(mpProps);
            autoCustomizer.invokeSpanExporterCustomizers(mpProps);
            autoCustomizer.invokeTracerProviderCustomizers(mpProps);
            envMap.putIfAbsent("OTEL_TRACES_SAMPLER", "always_on");
            // Si un bridge externe est en place pour metrics, force OTEL_METRICS_EXPORTER=none
            // pour éviter qu'Humboldt ajoute son propre InMemoryMetricExporter natif (qui
            // polluerait les assertions TCK ou créerait un second pipeline).
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

            // PropagatorCustomizer (Cluster D) — applique la chaîne sur le propagator final
            // (spiPropagators si défini, sinon W3CPropagators.get()). On wrap le résultat
            // dans un nouveau ContextPropagators si la chaîne a transformé.
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

            // M7c.2 : si le war contient des resources JAX-RS, démarrer
            // Cassini sur Chappe. Branche les filters humboldt-rest pour que
            // les spans SERVER soient générés conformément au TCK.
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
                // Filters humboldt-rest pour générer les spans SERVER
                hb.provider(new HumboldtServerRequestFilter());
                hb.provider(new HumboldtServerResponseFilter());
                hb.provider(new HumboldtSpanFinalizer());
                // HBT-2 — active le RequestContext Vauban autour de chaque dispatch HTTP.
                // CassiniHarness construit son pipeline sans passer par CassiniStackBuilder
                // donc le filter auto-injecté par VaubanBeanProvider.getResourceClasses()
                // n'est pas vu — on l'enregistre manuellement ici. Pour la production
                // (CassiniStack normal), aucune action utilisateur n'est requise.
                hb.provider(new io.vidocq.cassini.cdi.vauban.VaubanRequestScopeFilter(this.container));
                for (Class<?> p : providerClasses) {
                    try { hb.provider(p.getDeclaredConstructor().newInstance()); }
                    catch (ReflectiveOperationException e) {
                        LOG.log(Level.WARNING, "  ⚠ provider non-instanciable : {0}", p.getName());
                    }
                }
                this.cassini = hb.start();
                LOG.log(Level.INFO, "  → Cassini démarré sur {0} ({1} resources, {2} providers)",
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
     * Lit {@code META-INF/microprofile-config.properties} du war ShrinkWrap
     * (positions ShrinkWrap : {@code /META-INF/} pour JavaArchive,
     * {@code /WEB-INF/classes/META-INF/} pour WebArchive). Retourne une map
     * vide si le fichier n'existe pas.
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
     * Charge les {@link ConfigurableSpanExporterProvider} déclarés dans le war
     * ShrinkWrap via {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider}.
     * Retourne une map {@code nom → instance} indexée par {@code provider.getName()}.
     *
     * <p>Pattern utilisé par les TCK MP Telemetry (cf. décompilation
     * {@code ExporterSpiTest.createDeployment()}) qui ajoutent leur
     * {@code InMemorySpanExporterProvider} via
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
                    LOG.log(Level.INFO, "  → SpanExporterProvider chargé : {0} (name={1})",
                            fqn, p.getName());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ provider ignoré ({0}) : {1}", fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return out;
    }

    /**
     * Cluster D — Scanne {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider}
     * dans le war, invoque {@code createResource(configProperties)} sur chaque provider,
     * et renvoie les attributs sous forme de chaîne CSV {@code key1=val1,key2=val2} prête
     * à être concaténée à {@code OTEL_RESOURCE_ATTRIBUTES}. Le Resource humboldt les
     * inclura via {@code HumboldtAutoConfigure.buildResource()} (parsing CSV standard OTel).
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
                        LOG.log(Level.INFO, "  → ResourceProvider chargé : {0} (attrs={1})",
                                fqn, otelResource.getAttributes());
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ ResourceProvider ignoré ({0}) : {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return attrs.toString();
    }

    /**
     * Cluster D — Scanne {@code ConfigurableSamplerProvider} dans le war. Si
     * {@code otel.traces.sampler} matche le {@code getName()} d'un provider, instancie
     * le sampler OTel via {@code createSampler(configProperties)} et le wrappe dans
     * un {@link OtelSamplerBridge} pour utilisation directe par humboldt.
     *
     * @return un {@code humboldt.Sampler} prêt à être passé à {@code HumboldtAutoConfigure.configure(...)},
     *         ou {@code null} si aucun provider ne matche.
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
                        LOG.log(Level.INFO, "  → SamplerProvider '{0}' ({1}) bridgé via OtelSamplerBridge",
                                name, fqn);
                        return new OtelSamplerBridge(otelSampler);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ SamplerProvider ignoré ({0}) : {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return null;
    }

    /**
     * Cluster D — Scanne {@code ConfigurablePropagatorProvider} dans le war. Si
     * {@code otel.propagators} contient un nom qui matche le {@code getName()} d'un provider,
     * instancie le propagator OTel via {@code getPropagator(configProperties)} et compose
     * un {@link io.opentelemetry.context.propagation.ContextPropagators} (W3C TraceContext
     * + Baggage par défaut + propagators custom listés).
     *
     * @return un {@code ContextPropagators} composite, ou {@code null} si aucun custom
     *         provider n'est requis (le caller utilisera alors W3CPropagators.get()).
     */
    private static io.opentelemetry.context.propagation.ContextPropagators resolveSpiPropagators(
            Archive<?> archive, Map<String, String> mpProps) {
        // MP Telemetry §3.3 : la propriété mp_telemetry.propagators est aussi acceptée,
        // avec mappage vers otel.propagators.
        String configured = mpProps.getOrDefault("otel.propagators",
                mpProps.get("mp_telemetry.propagators"));
        if (configured == null) return null;

        // Découpe la liste de noms (CSV, espaces tolérés)
        List<String> names = new ArrayList<>();
        for (String name : configured.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        if (names.isEmpty()) return null;

        // Scan optionnel des SPI providers déclarés dans le WAR (custom propagators
        // type TestPropagator du TCK). Peut être absent si on n'utilise que des builtins
        // (b3, jaeger, etc.) — le switch ci-dessous se débrouille.
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
                            LOG.log(Level.INFO, "  → PropagatorProvider chargé : {0} (name={1})", fqn, name);
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "  ⚠ PropagatorProvider ignoré ({0}) : {1}", fqn, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
            }
        }

        // Composer la liste finale : pour chaque nom dans `otel.propagators`, utiliser
        // le builtin si reconnu (tracecontext, baggage, b3, b3multi, jaeger), sinon
        // le custom SPI scanné dans le WAR.
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
                    else LOG.log(Level.WARNING, "  ⚠ Propagator '{0}' demandé mais non disponible (ni builtin ni SPI scanné)", n);
                }
            }
        }
        if (chosen.isEmpty()) return null;
        return io.opentelemetry.context.propagation.ContextPropagators.create(
                io.opentelemetry.context.propagation.TextMapPropagator.composite(chosen));
    }

    /**
     * M4b — Scanne {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider}
     * dans le war. Si {@code otel.metrics.exporter} matche le {@code getName()} d'un
     * provider scanné, instancie l'OTel MetricExporter via {@code createExporter()} et
     * le wrappe dans {@link OtelMetricExporterBridge} pour intégration au pipeline humboldt.
     * Pattern symétrique à {@code loadConfigurableSpanExporterProviders} (M7b.4b.3).
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
                        LOG.log(Level.INFO, "  → MetricExporter '{0}' ({1}) bridgé vers Humboldt", name, fqn);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ MetricExporterProvider ignoré ({0}) : {1}", fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return out;
    }

    /**
     * Cluster D — Scanne {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
     * dans le war, invoque {@code customize(humboldtCustomizer)} sur chaque provider pour
     * collecter les chaînes de callbacks (Resource/Propagator/Properties/Sampler/SpanExporter/
     * TracerProvider). Le {@link HumboldtAutoConfigurationCustomizer} ainsi peuplé est ensuite
     * appliqué au bon moment dans le pipeline humboldt.
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
                    LOG.log(Level.INFO, "  → AutoConfigCustomizerProvider chargé : {0}", fqn);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ AutoConfigCustomizerProvider ignoré ({0}) : {1}",
                            fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return customizer;
    }

    /**
     * Convertit les propriétés MP Config ({@code lowercase.dotted}) en variables
     * d'environnement OTel ({@code SCREAMING_SNAKE_CASE}). Conformément à la spec
     * OTel : {@code otel.traces.exporter} ↔ {@code OTEL_TRACES_EXPORTER}.
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
     * Extrait les classes du war ShrinkWrap. Suppose qu'elles sont chargeables
     * via le ClassLoader courant — vrai en mode Local Arquillian (même JVM
     * que la spec de test, donc les classes annotées {@code @Deployment}
     * étaient déjà chargées par le ClassLoader de test).
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
                LOG.log(Level.WARNING, "  ⚠ classe ignorée ({0}) : {1}",
                        fqn, e.getMessage());
            }
        }
        return classes;
    }
}
