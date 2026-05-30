# CLAUDE.md — mansart

Guide spécifique à ce sous-projet. Voir aussi le `CLAUDE.md` racine du workspace pour la philosophie transverse.

## Mission

Fournir à l'écosystème Vidocq trois briques de persistance, indépendantes en runtime :

- `mansart-jakarta-data` — Jakarta Data 1.0 (repositories déclaratifs).
- `mansart-persistence` — Jakarta Persistence 3.2 (JPA classique).
- `mansart-pool` — pool JDBC virtual-thread-native, zéro-dep, optionnel.

Les deux premiers partagent la même SPI dialecte SQL et le même métamodèle statique. `mansart-pool` est totalement découplé : il fournit juste un `javax.sql.DataSource` utilisable par n'importe quel client JDBC.

## Prérequis

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` à venir dans ce dossier).
- Pour les TCK : artefacts officiels Jakarta non publics à installer dans le M2 local (procédure documentée dans le runner TCK).

## Contraintes d'architecture à respecter

1. **Aucune réflexion runtime** sur les entités ni sur les interfaces `@Repository`. Tout passe par APT (`mansart-data-processor`) qui génère :
   - le métamodèle statique (`_Book`, `_Author`, …) avec attributs typés ;
   - une implémentation `class XxxRepositoryImpl implements XxxRepository` par interface annotée `@Repository`.
2. **Aucune génération de bytecode runtime** (pas d'ASM, pas de Byte Buddy, pas de proxy dynamique). Si un cas réclame du dynamique, utiliser la **Class-File API** (`java.lang.classfile`) — jamais ASM.
3. **Zéro dépendance externe** hors `jakarta.data-api`, `jakarta.persistence-api`, `jakarta.transaction-api`, `jakarta.inject-api`, `jakarta.cdi-api`. JDBC est dans le JDK. Drivers (`h2`, `postgresql`) restent en `<scope>provided</scope>` (l'application les apporte).
4. **JPMS strict** — chaque module a son `module-info.java`. La SPI dialecte (`mansart-data-dialect-spi`) est `exports` ; les dialectes (`mansart-data-dialect-h2`, `…-postgresql`) sont `provides DialectFactory with …` et sont découverts par `ServiceLoader`.
5. **Virtual Threads** — toute exécution `PreparedStatement.execute*` se fait depuis un virtual thread. La connexion JDBC ne doit jamais être détenue à travers un `synchronized` qui englobe une opération bloquante.

## Modules prévus (sous `mansart-jakarta-data/`)

| Sous-module | Rôle |
| --- | --- |
| `mansart-data-api` | Re-exposition `jakarta.data` + annotations Mansart spécifiques (`@Dialect`, `@JdbcRepository`). |
| `mansart-data-core` | Runtime : exécution des plans de requête, mapping result-set ↔ entité, gestion connexion. |
| `mansart-data-processor` | APT — métamodèle statique + génération des `*RepositoryImpl`. |
| `mansart-data-dialect-spi` | SPI : `Dialect`, `DialectFactory`, AST de requête neutre. |
| `mansart-data-dialect-h2` | Dialecte H2 (tests + embarqué). |
| `mansart-data-dialect-postgresql` | Dialecte PostgreSQL (référence prod). |
| `mansart-data-tests` | Tests unitaires (H2 in-memory). |
| `mansart-data-tck` | Runner TCK Jakarta Data 1.0 — **HORS reactor** (POM Model 4.0.0 standalone, comme les autres TCK Vidocq). |

## Conventions

- Tests d'intégration PostgreSQL via **Testcontainers** uniquement en `<scope>test</scope>` — jamais en runtime.
- Métamodèle généré sous `target/generated-sources/annotations/` ; les classes générées sont préfixées par `_` (convention JPA static metamodel) et marquées `@Generated`.
- Toute requête générée passe par l'AST `mansart-data-dialect-spi` — jamais de SQL inline dans `mansart-data-core`.
- Bugs reproductibles → `BUG.md` (skill `/log-bug`). Mesures perf → `BENCH.md` (skill `/log-bench`).

## Roadmap (jalons proposés)

- **M1 — Plan validé** *(en cours)* — voir `PLAN.md` et `mansart-jakarta-data/PLAN.md`.
- **M2 — APT métamodèle + repositories** : génération des `_Entity` et des `*RepositoryImpl` pour `BasicRepository`, `CrudRepository`, `@Find`, `@Save`, `@Delete`.
- **M3 — Dialecte H2 + tests unitaires** : CRUD complet, pagination offset/keyset, `@OrderBy`, `Sort`, `Limit`, `Page`.
- **M4 — Dialecte PostgreSQL** : portage du dialecte + tests Testcontainers + identifiants `RETURNING`.
- **M5 — `@Query` JDQL** : parsing JDQL → AST → SQL dialecte. (JPQL plus tard via `mansart-persistence`.)
- **M6 — TCK Jakarta Data 1.0** : harness Arquillian hors reactor, smoke + suite complète.
- **M7 — Pont `mansart-persistence`** : bootstrap JPA 3.2 minimal, partage du métamodèle, repositories typés `EntityManager`.
