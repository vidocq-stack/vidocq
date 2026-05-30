# Ravel

> Maurice Ravel (1875–1937) était maître de l'orchestration — il savait tisser des sources
> disparates en un tout cohérent. C'est exactement ce que fait un système de configuration.

**Ravel** est une implémentation [MicroProfile Config 3.1](https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html)
de l'écosystème [Vidocq](https://forge.vidocq.dev/vidocq).

## Principes

- **Zéro librairie tierce** — uniquement les specs Jakarta EE et MicroProfile sont autorisées
  en dépendance compile/runtime. Pas de Smallrye Config, pas de Helidon Config, pas d'Apache
  Commons Config.
- **Java 25 + Maven 4** — pinés via `.sdkmanrc`.
- **JPMS strict** — chaque module a son `module-info.java`, packages `internal.*` non exportés,
  SPI via `provides/uses`.
- **Virtual threads friendly** — pas de `synchronized`, pas de `ThreadLocal`. `ScopedValue` pour
  la propagation contextuelle (détection de cycles dans les expressions notamment).
- **TDD strict** — Red → Green → Refactor, citation systématique de la spec dans le JavaDoc des tests.
- **TCK 100 % PASS** — contrat dur sur `microprofile-config-tck:3.1.1` avant tout merge structurel.

## Modules

| Module | Description |
|---|---|
| `ravel-api` | Re-export de la spec `org.eclipse.microprofile.config` |
| `ravel-core` | Implémentation `Config` standalone : sources built-in, converters, profiles, expressions |
| `ravel-cdi-vauban` | Intégration CDI Vauban : `@ConfigProperty`, BCE, injection `Optional<T>` |
| `ravel-bench` | Benchmarks JMH — comparatif vs Smallrye Config |
| `ravel-tck` | Runner TCK officiel MicroProfile Config 3.1 (**hors reactor**, POM Model 4.0.0) |

## Démarrage

```bash
cd ravel
sdk env                         # Java 25 + Maven 3.9.16
mvn -ntp install -DskipTests    # build du reactor
mvn test                        # tests unitaires
```

## TCK officiel

L'artefact `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1` est public sur Maven
Central — pas d'installation manuelle nécessaire (la 3.1.1 est une re-release JPMS-friendly de
la spec 3.1, contenu identique).

```bash
./run-official-tck-mp-config-3.1.sh             # smoke
./run-official-tck-mp-config-3.1.sh all         # suite complète
./run-official-tck-mp-config-3.1.sh -Dtest=...  # ciblé
```

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — guide développement (contraintes, conventions, TDD)
- [`ROADMAP.md`](ROADMAP.md) — plan de phase M0 → M6
- [`BUG.md`](BUG.md) — bugs ouverts (créé au premier rapport)
- [`BENCH.md`](BENCH.md) — résultats JMH datés (créé au premier run)

## Licence

Apache License 2.0 — voir [`LICENSE`](LICENSE).
