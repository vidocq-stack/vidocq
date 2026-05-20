# Humboldt — Leçons de session

> Toute correction utilisateur ou validation non triviale doit être consignée ici. Calque sur `chappe/tasks/lessons.md`.

## M2 — SDK Trace (2026-05-20)

### Locale.ROOT pour les format strings

`String.format("%.6f", 0.42)` retourne `"0,420000"` dans un environnement français.
Les `description()` de Sampler / IdGenerator / etc. **doivent** utiliser
`String.format(Locale.ROOT, ...)` pour garantir un point décimal portable —
sinon les tests `contains("0.42")` échouent en CI selon la locale du runner.

### InMemorySpanExporter.shutdown() ne purge pas

Convention Humboldt : `shutdown()` arrête l'export mais **conserve** les
spans déjà collectés, pour permettre l'inspection après `provider.close()`
dans les tests en try-with-resources. Utiliser `reset()` pour vider
explicitement. Diffère de l'impl OTel de référence qui clear sur shutdown.

### SpanBuilder OTel 1.39 — 4 setAttribute primitifs abstract

Dans OpenTelemetry API 1.39.0, `SpanBuilder.setAttribute(String, boolean)`
est ABSTRACT (et probablement les 3 autres primitifs). Il faut overrider
les 4 par délégation `setAttribute(AttributeKey.{string|long|double|boolean}Key(key), value)`.
Pareil pour `TracerProvider.get(String name, String version)` qui doit
être implémenté (la simplification "cache by name only" est OK pour M2).

## M3 — Exporter OTLP (2026-05-20)

### `requires static jdk.httpserver` pour les tests E2E

Dans un module JPMS avec `module-info.java`, les tests Maven 4 + Surefire 3.5
sont compilés en MODULEPATH (pas classpath). Un test qui utilise
`com.sun.net.httpserver.HttpServer` (HttpServer du JDK pur, parfait pour les
fake servers in-process) doit donc voir le module `jdk.httpserver`.

Solution propre : `requires static jdk.httpserver;` dans le module-info
du main. `static` = compile-time uniquement, n'apparaît pas dans le graphe
runtime. Aucun ajout de dep externe (le module est dans le JDK).

Alternative envisagée (rejetée) : test module-info séparé, argLine
`--add-modules` côté surefire — plus complexe pour zéro gain.

### Propagators W3C déjà concrets dans OTel API

`W3CTraceContextPropagator.getInstance()` et `W3CBaggagePropagator.getInstance()`
sont des classes concrètes dans `opentelemetry-api` (pas que des interfaces).
Donc `humboldt-propagator-w3c` ne réimplémente pas — il fournit juste la
composition canonique `ContextPropagators.create(TextMapPropagator.composite(...))`.

## M4 — SDK Metric (2026-05-20)

### `mvn clean` obligatoire après déplacement de package

Symptôme : `LayerInstantiationException: Package X in both module A and module B`
au démarrage du test JVM. Cause : un `.class` orphelin reste dans `target/`
après qu'on a déplacé le `.java` vers un autre module (ou changé son
`package`). Le JAR construit contient à la fois la classe à la nouvelle
position ET la classe résiduelle à l'ancienne — JPMS détecte le doublon
de package et refuse de monter la layer.

Toujours faire `mvn clean install` après un déplacement de classe entre
modules ou un changement de package declaration.

### Couplage cross-SDK à éviter — placer les briques partagées en sdk-common

`SpanData`, `MetricData`, `LogRecordData` partagent les mêmes briques :
`Resource`, `InstrumentationScope`, `CompletableResultCode`. Si on les laisse
dans le premier SDK qui les crée (humboldt-sdk-trace), les autres SDK
finissent par devoir `requires` ce module — ce qui couple inutilement
metric/log à trace.

Règle : tout type partagé entre 2+ SDK doit vivre dans `humboldt-sdk-common`.
Migration appliquée en M4 pour InstrumentationScope (trace → common) et
CompletableResultCode (trace → common).

### OpenTelemetry API 1.39 — MeterBuilder.setInstrumentationAttributes absent

Contrairement à ce qu'on pourrait croire, `MeterBuilder` dans OTel 1.39 n'a
PAS `setInstrumentationAttributes(Attributes)`. Méthodes abstraites = juste
`setInstrumentationVersion(String)`, `setSchemaUrl(String)` et `build()`.
Toujours vérifier les overrides côté compilateur — chaque version OTel a
des nuances dans ce qui est default vs abstract sur les builders.

## M6b — humboldt-rest (2026-05-21)

### `java.lang.reflect.Proxy` pour mocker une API JAX-RS sans Mockito

Les interfaces `ContainerRequestContext` et `UriInfo` de JAX-RS 4.0 ont
chacune ~40-50 méthodes abstraites (et la surface change entre versions :
`MatchedResource` ajouté en 4.0, `getMatchedResourceTemplate()` nouveau,
etc.). Implémenter ces interfaces à la main dans un test = boilerplate
énorme et fragile à chaque upgrade JAX-RS.

Solution Humboldt : `java.lang.reflect.Proxy.newProxyInstance` + switch sur
`method.getName()` pour router uniquement les ~6 méthodes effectivement
consommées par le filter, avec `defaultForReturnType(m)` qui retourne des
valeurs sûres (`null`, `false`, `0`, `List.of()`, `Map.of()`,
`MultivaluedHashMap`) pour tout le reste.

Avantage : robuste face aux ajouts d'abstract methods upstream — pas besoin
de patcher le test à chaque release JAX-RS. Pas de dépendance Mockito.

Pattern à réutiliser pour `ContainerResponseContext`, `SecurityContext`,
`Application` etc. quand on testera M6c/M7.

### `UriInfo.getPath()` ne contient PAS le '/' initial — normaliser

Selon JAX-RS spec, `UriInfo.getPath()` retourne le path **relatif au base URI**,
SANS '/' initial. Mais la convention OTel HTTP semantic conventions exige
`url.path` AVEC '/' initial.

Fix dans `HumboldtServerRequestFilter` :
`path = raw.startsWith("/") ? raw : "/" + raw` **avant** de le poser dans
l'attribut ET dans le span name. Sinon tests échouent avec
`expected: </users/42> but was: <users/42>`.

## Refactor — OtlpHttpJsonSender mutualisé (2026-05-21)

### Pattern à 3 exemplaires = signal pour refactor

Quand un pattern est dupliqué dans 3+ classes (M3 Span, M4 Metric, M5 Log
exporters partageaient HttpClient + retry + headers + computeBackoffMillis
quasi-identiques), c'est le bon moment pour extraire un utilitaire commun.

Solution Humboldt : `OtlpHttpJsonSender` interne (package `.internal.`) qui
encapsule HttpClient + endpoint + headers + timeouts + retry. Les 3 exporters
deviennent ~85 lignes chacun (vs ~170 avant) et délèguent juste l'encoding.

Bénéfice mesuré : -255 lignes de code (3 × -85), 1 seul endroit à modifier
pour le retry/transport/protocol switch futur (M3b : passage à chappe-client,
ajout de OTEL_EXPORTER_OTLP_TIMEOUT/PROTOCOL en M7).

API publique inchangée — les builders `OtlpHttpXxxExporter.builder()`
gardent exactement la même signature. Les tests E2E passent sans modification
(109/109 toujours verts).

### Rétro-compat avec wrapper static

`OtlpHttpSpanExporter.computeBackoffMillis(int)` public static était utilisé
par les 2 autres exporters ET par un test E2E. Au lieu de casser la
rétrocompat, on garde la méthode et on la fait déléguer à
`OtlpHttpJsonSender.computeBackoffMillis()`. Coût : 3 lignes. Bénéfice :
pas de modification des appelants externes (et le test E2E continue de
fonctionner sans patch).

## Refactor P1 — OtlpJsonCommon mutualisé (2026-05-21)

### Bug latent révélé par le refactor

Lors de l'audit de duplication entre les 3 encoders OTLP/JSON (spans M3,
metrics M4, logs M5), constat : les versions de `writeAnyValue` dans
OtlpJsonMetricEncoder et OtlpJsonLogEncoder ne supportaient PAS les
attribute types array (STRING_ARRAY, LONG_ARRAY, BOOLEAN_ARRAY,
DOUBLE_ARRAY). Bug latent : un metric ou log avec un attribute
`tags=["a","b"]` aurait crashé avec une RuntimeException via le `default ->`.

Cause : OtlpJsonEncoder (M3) avait été écrit en premier avec la version
complète ; M4 et M5 ont copié-collé une version simplifiée par oubli.
Le refactor en `OtlpJsonCommon.writeAnyValue` ne supportant qu'UNE seule
version fixée — la plus complète — élimine le bug latent.

Test de régression ajouté (`encodes_array_attributes_as_otlp_arrayValue`)
qui prouve que STRING_ARRAY + LONG_ARRAY se sérialisent en
`arrayValue.values` selon la spec OTLP/JSON.

Pattern à retenir : **un refactor DRY révèle souvent des divergences**
entre les copies — il faut les analyser une par une au lieu de prendre
"la version la plus récente" par défaut.

### Mutualisation via héritage + callbacks fonctionnels (P2/P3)

Pour les `InMemory{Span,Metric,LogRecord}Exporter` (P2) et les
`Batch{Span,LogRecord}Processor` (P3), l'héritage classique fonctionne
bien car les sous-classes implémentent des INTERFACES différentes
(SpanExporter/MetricExporter/LogRecordExporter, SpanProcessor/LogRecordProcessor).

Pattern Humboldt :
- `InMemoryExporterBase<T>` abstract : storage `CopyOnWriteArrayList<T>` +
  helpers `addAll/flushBase/shutdownBase`. Sous-classes (~30 lignes) :
  appellent les helpers depuis leur impl d'interface.
- `AbstractBatchProcessor<T>` abstract : worker VT + queue + flush/shutdown.
  Constructeur prend `Consumer<List<T>> exportBatch + Supplier<CompletableResultCode>
  flushExporter + Runnable shutdownExporter` (callbacks fonctionnels qui
  encapsulent l'interface exporter spécifique). Sous-classes appellent
  `offer(T)` depuis leur callback (`onEnd` / `onEmit`).

Avantages des callbacks fonctionnels (vs abstract methods côté processor) :
- Pas besoin de templater Base avec `<X extends Exporter>` (couplage en moins)
- Construction explicite côté sous-classe (le constructeur de Builder passe
  les lambdas) — IDE lisibilité conservée
- Réutilisable au-delà du couple Span/Log si on ajoute un BatchMetricProcessor
  à signature différente

Mesures (refactor M-bloc P1+P2+P3) :
- P1 : −74 lignes nettes (JSON encoders)
- P2 : −90 lignes nettes (InMemory exporters)
- P3 : −80 lignes nettes (Batch processors)
- Total cleanup : **−244 lignes nettes** sur 12 modules, 0 régression

### Java unicode preprocessor mange les `\u00XX` même dans les commentaires

Surprenant mais documenté : Java exécute le préprocesseur unicode AVANT
le parser, sur tout le source (commentaires inclus). Donc une séquence
`\u00XX` dans un commentaire `/** ... */` est interprétée comme un vrai
caractère unicode. Si le résultat casse la syntaxe (ex. un `*/` accidentel
qui ferme prématurément un block comment), erreur compile
"illegal unicode escape" — vraiment cryptique.

Solution Humboldt : éviter `\u` dans les commentaires (utiliser `u00XX`
sans backslash, ou échapper le backslash en `\\u`).

### OTLP/JSON encoding manuel par StringBuilder

Pour M3 MVP, l'encoder OTLP/JSON est écrit à la main via StringBuilder (pas
de champollion JSON-P, pas de Jackson). Le schéma OTLP est fixe et limité
(~10 types de valeurs). Le surcoût d'apporter un parser JSON pour cet usage
est disproportionné. La spec : <https://github.com/open-telemetry/opentelemetry-proto/blob/main/docs/specification.md#json-protobuf-encoding>

Détails à retenir :
- `traceId`/`spanId` sont en **hex string** dans OTLP/JSON (en bytes dans protobuf)
- timestamps en `string` représentant un long nano (pas Number JSON)
- `kind` int : INTERNAL=1, SERVER=2, CLIENT=3, PRODUCER=4, CONSUMER=5
- `status.code` int : UNSET=0, OK=1, ERROR=2
- AnyValue : `{"stringValue":"..."}`, `{"intValue":"..."}` (long → string!), etc.
