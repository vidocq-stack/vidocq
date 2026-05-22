# Vidocq Runtime — Roadmap

Runtime MicroProfile 7.1 Java SE modulaire bâti sur Vauban (CDI 4.1 Lite).
Chaque spec MicroProfile est livrée comme **extension** indépendante chargée via
ServiceLoader (modèle inspiré de Quarkus). Le runtime lui-même reste minimal :
SPI, lifecycle, packaging (jlink/fat jar via `vidocq-runtime-maven-plugin`).

> Vue produit : voir [`README.md`](README.md). Documentation utilisateur : `docs/`.

## Statut des spec MicroProfile 7.1

| Spec MicroProfile | Implémentation | Extension Vidocq | Statut |
|---|---|---|---|
| **Rest Client 4.0** | [cyrano](../cyrano) | `vidocq-runtime-cyrano-extension` | ✅ livré |
| **Telemetry 2.1** | [humboldt](../humboldt) | `vidocq-runtime-humboldt-extension` | ✅ livré (M7 TCK en cours) |
| **Health 4.0** | [knock](../knock) | `vidocq-runtime-knock-extension` | ✅ livré |
| **Config 3.1** | _smallrye config_ | — | ❌ à packager comme extension |
| **Fault Tolerance 4.1** | _smallrye fault tolerance_ | — | ❌ à packager comme extension |
| **JWT Auth 2.1** | — | — | ❌ TODO (heisenberg ?) |
| **OpenAPI 4.0** | — | — | ❌ TODO |
| **Metrics 5.1** | — | — | ❌ TODO (souvent fusionné avec Telemetry) |

## Extensions hors spec MicroProfile

Briques infra additionnelles livrées comme extensions Vidocq, utiles à
l'écosystème :

| Brique | Extension Vidocq | Statut |
|---|---|---|
| Serveur HTTP/1.1+H2+WS+gRPC | [chappe](../chappe) | `vidocq-runtime-chappe-extension` ✅ |
| JAX-RS 4.0 (transport via chappe) | [cassini](../cassini) | `vidocq-runtime-cassini-rest-extension` ✅ |
| Jakarta Data 1.0 (repositories) | [mansart-jakarta-data](../mansart) | `vidocq-runtime-mansart-data-extension` ✅ |
| Pool JDBC virtual-thread-native | [mansart-pool](../mansart) | `vidocq-runtime-mansart-pool-extension` ✅ |
| Transactions JTA | [mansart-transactions](../mansart) | `vidocq-runtime-mansart-transactions-extension` ✅ |
| Jakarta Persistence 3.2 (JPA) | [mansart-persistence](../mansart) | ❌ M7 mansart en attente |

## Modules du reactor

```
vidocq-runtime-spi/                       SPI publique d'extension (Extension, Phase)
vidocq-runtime-core/                      Orchestrateur lifecycle, ServiceLoader, scan
vidocq-runtime-core-extensions/           Extensions livrées (cf. tableau ci-dessus)
vidocq-runtime-maven-plugin/              Plugin Maven : packaging fat jar + jlink
vidocq-runtime-examples/                  Exemples (cassini-rest, mansart-h2, external-rest-lib)
vidocq-runtime-integration-tests/         IT Arquillian + cross-extension (humboldt+cassini)
```

## Backlog

### Court terme (extensions MP manquantes)

- [ ] **`vidocq-runtime-config-extension`** — wrapper SmallRye Config / config naïve maison.
      Pré-requis CDI pour `@ConfigProperty` ; doit s'intégrer au lifecycle d'init Vauban.
- [ ] **`vidocq-runtime-fault-tolerance-extension`** — `@Retry`, `@Timeout`, `@Bulkhead`,
      `@CircuitBreaker`. Compatibilité virtual threads : éviter ThreadLocal pinning,
      privilégier `ScopedValue`. Wrapper SmallRye envisageable.
- [ ] **`vidocq-runtime-jwt-extension`** — gating auth Bearer JWT. Implémentation
      probablement dans un nouveau repo (placeholder `heisenberg` ?). Spec MP JWT Auth 2.1.
- [ ] **`vidocq-runtime-openapi-extension`** — génération OpenAPI 3.1 depuis les
      ressources JAX-RS Cassini. Spec MP OpenAPI 4.0.
- [ ] **`vidocq-runtime-metrics-extension`** — soit séparé soit fusionné dans humboldt.
      Décision : à trancher selon l'évolution de la spec MP Metrics 5.1 vs Telemetry.

### Moyen terme (intégration & packaging)

- [ ] Plugin Maven `vidocq-runtime-maven-plugin` : commande `vidocq:jlink` complète
      (cf. `JLINK.md`), démonstration sur `vidocq-runtime-examples`.
- [ ] CLI `vidocq` standalone (similar to `chappe serve`) qui scan extensions au
      classpath et lance le runtime sans Maven plugin.
- [ ] Documentation utilisateur Antora exhaustive (`docs/`) : guide démarrage,
      référence extensions, recettes intégration cross-extension.

### Long terme (qualité)

- [ ] TCK MicroProfile 7.1 par spec implémentée. Cf. `TCK.md` pour le tracking
      (chaque extension porte son TCK runner hors-reactor, modèle `champollion-tck`).
- [ ] Benchmarks JMH end-to-end (cold start, throughput cross-extension, footprint
      mémoire vs Quarkus/Helidon). Pas de chiffre dans la doc sans entrée `BENCH.md`.
- [ ] AOT GraalVM native-image : prérequis = pas de réflexion runtime dans aucune
      extension. Validation incrémentale (chappe + cassini d'abord).

## Bugs & incidents

Voir `CHAPPE-BUGS.md`, `VAUBAN-BUGS.md` à la racine pour les anomalies historiques
remontées sur les briques sous-jacentes. Pas de `BUG.md` pour le runtime lui-même
à ce jour — créer un fichier si une régression Vidocq apparaît.

## Conventions de tracking

- **Cette roadmap** : vision moyen/long terme, à jour côté statut des spec MP.
- **`tasks/todo.md`** (par sous-projet) : TODOs court terme actifs, peut être absent.
- **`BUG.md`** (par sous-projet) : régressions reproductibles, traçabilité incident.
- Les chiffres de performance vont dans `BENCH.md` (par sous-projet), jamais dans
  README/commit sans entrée correspondante.
