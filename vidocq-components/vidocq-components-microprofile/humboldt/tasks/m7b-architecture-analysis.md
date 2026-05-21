# M7b — Analyse architecturale (2026-05-21)

## Finding clé de l'audit TCK

Le TCK MicroProfile Telemetry 2.1 (`microprofile-telemetry-tracing-tck:2.1`)
exige que l'implémentation supporte le **mécanisme d'extension OpenTelemetry SDK
autoconfigure**. Évidence par décompilation des tests :

```java
// ExporterSpiTest.createDeployment() — extrait javap -c
ShrinkWrap.create(WebArchive.class)
    .addClasses(
        InMemorySpanExporter.class,                  // io.opentelemetry.sdk.trace.export.SpanExporter
        InMemorySpanExporterProvider.class,          // io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
        TestCustomizer.class)
    .addAsServiceProvider(
        ConfigurableSpanExporterProvider.class,       // ← SPI OTel SDK
        InMemorySpanExporterProvider.class)
    .addAsResource(
        new StringAsset("otel.sdk.disabled=false\notel.traces.exporter=in-memory"),
        "META-INF/microprofile-config.properties");
```

Signatures clés :

```
public class InMemorySpanExporter implements io.opentelemetry.sdk.trace.export.SpanExporter
public class InMemorySpanExporterProvider implements io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
```

Et l'injection se fait via CDI :

```java
@Inject InMemorySpanExporter exporter;
exporter.assertSpanCount(1);
```

## Conflit avec la contrainte d'archi Humboldt

CLAUDE.md §"Contraintes d'architecture à ne pas violer" point 1 :

> **Aucun `import io.opentelemetry.sdk.*`** dans humboldt — on réécrit ce code,
> on ne le consomme pas.

Le TCK suppose que :
- L'implémentation embarque OTel SDK autoconfigure
- Les exporters utilisateur sont des `io.opentelemetry.sdk.trace.export.SpanExporter`
- Le pipeline trace consomme des `io.opentelemetry.sdk.trace.data.SpanData`
- La configuration passe par `io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties`

C'est **strictement incompatible** avec la philosophie Humboldt actuelle de
re-implémenter le SDK sans le consommer.

## Options architecturales

### Option A — Embarquer OTel SDK autoconfigure dans humboldt-runtime (refus de l'archi actuelle)

- Humboldt devient un wrapper du SDK OTel officiel
- On peut viser TCK 100 % sans gymnastique
- **Détruit la philosophie zéro-SDK-OTel** + 4 modules `humboldt-sdk-*` deviennent
  morts ou doublons
- Casse les ADRs et la valeur ajoutée Humboldt (codegen statique, VT-friendly,
  HTTP exporter via chappe-client futur)

### Option B — Bridge SDK OTel ↔ Humboldt SDK (adapter complet)

- humboldt-runtime intègre OTel SDK autoconfigure SPI uniquement comme **mécanisme
  de discovery** (le SDK OTel n'est jamais utilisé pour traiter les spans)
- Quand `otel.traces.exporter=<custom>` est configuré, on instancie le provider
  OTel SDK, on obtient un `io.opentelemetry.sdk.trace.export.SpanExporter`
- Un adapter `OtelSpanExporterBridge implements io.vidocq.humboldt.sdk.trace.SpanExporter`
  l'enveloppe : pour chaque batch, convertit `humboldt.SpanData → opentelemetry.SpanData`
  via le `io.opentelemetry.sdk.testing.trace.TestSpanData.builder()` ou impl
  équivalent, puis délègue
- L'`InMemorySpanExporter` du TCK reçoit donc les vrais spans Humboldt convertis
  au format OTel SDK
- Travail : ~600-800 lignes de code de conversion + 1 module nouveau (`humboldt-sdk-bridge-otel`)
  + tests d'isomorphisme
- Restera **OPTIONNEL** — non utilisé en prod, activé uniquement via une
  dépendance explicite (et déclenché par autoconfigure quand l'env demande)

### Option C — Adapter limité au runner TCK (hors-reactor)

- Tout le code de bridge SDK OTel vit dans `humboldt-tck/` (hors-reactor, déjà
  isolé de la prod)
- Pas de nouveau module dans le reactor
- humboldt-runtime expose juste un **hook SPI** (déjà existant ? à créer) pour
  injecter dynamiquement un `SpanExporter` "externe" dans le pipeline trace
  au démarrage
- Le runner TCK fournit le bridge `OtelSpanExporterBridge` + un harness Arquillian
  qui sait :
  1. Parser `META-INF/microprofile-config.properties` du war ShrinkWrap
  2. Charger les `ConfigurableSpanExporterProvider` du war via ServiceLoader
  3. Wrapper les OTel exporters en humboldt exporters via le bridge
  4. Démarrer Vauban CDI + Cassini JAX-RS + Chappe HTTP + AutoConfiguredHumboldt
     en in-process, avec le bean `InMemorySpanExporter` exposé dans le BeanManager
- Travail : ~800-1200 lignes (adapter + container Arquillian custom)
- **Avantage** : zéro pollution du runtime Humboldt. La philosophie est respectée.
- **Risque** : code TCK assez complexe à maintenir, sensible aux évolutions
  des specs (chaque montée de version TCK potentiellement painful).

## Recommandation

**Option C** — confiner le pont SDK OTel au runner TCK hors-reactor.

Justification :
- Préserve la philosophie Humboldt (le runtime applicatif n'embarque pas SDK OTel)
- Cohérent avec la décision déjà prise pour `humboldt-tck/` (pom standalone,
  `opentelemetry-sdk` en scope test)
- Permet de viser TCK 100 % sans compromettre l'archi
- Si plus tard la maintenance devient trop lourde, on peut migrer vers
  Option B (le bridge devient un module officiel optionnel) sans casser
  la philo

## Décision attendue de Yann

Avant de coder M7b.4 (container Arquillian) et M7b.3 (réenregistrement
exporters), je dois savoir lequel des 3 chemins prendre. Le code, l'ampleur
du runner, et les futures cassures de version diffèrent radicalement.

## Plan d'exécution si Option C validée

1. **M7b.3-bis** — Définir/exposer un point d'extension dans humboldt-runtime
   (probablement `HumboldtAutoConfigure.withExtraSpanExporter(humboldt.SpanExporter)`)
   pour qu'un harness externe puisse injecter un exporter sans toucher aux env
   vars (~30 LOC)

2. **M7b.4a** — Créer dans `humboldt-tck/` un `OtelSpanExporterBridge` qui
   convertit `humboldt.SpanData → otel.SpanData` (réutiliser le record OTel
   `io.opentelemetry.sdk.testing.trace.TestSpanData` qui est public)
   (~250 LOC + tests d'isomorphisme)

3. **M7b.4b** — Container Arquillian embedded Humboldt :
   - `HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig>`
   - À chaque `deploy(Archive)` : extract WAR en mémoire, ClassLoader isolé,
     boot Vauban CDI Lite, register filters/providers Cassini, démarrer Chappe
     sur port aléatoire, configurer AutoConfiguredHumboldt avec exporters bridgés
   - Enregistrer le service via `META-INF/services/org.jboss.arquillian.container.spi.client.container.DeployableContainer`
   - (~400-600 LOC)

4. **M7b.5** — Activer `OpenTelemetryBeanTest` (un seul) et viser un START
   sans crash (les assertions peuvent échouer, on cherche juste à valider la
   chaîne).
