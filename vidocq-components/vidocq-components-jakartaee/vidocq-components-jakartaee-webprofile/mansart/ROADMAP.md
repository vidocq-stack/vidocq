# Mansart — Roadmap

Stack Jakarta Data 1.0 + Jakarta Persistence 3.2 + pool JDBC virtual-thread-native,
zéro dépendance hors specs Jakarta. Trois sous-projets indépendants en runtime.

> Vision et décisions structurantes : voir [`PLAN.md`](PLAN.md). Vue produit : [`README.md`](README.md).

## Vue d'ensemble

| Sous-projet | Spec | Statut |
|---|---|---|
| `mansart-jakarta-data` | Jakarta Data 1.0 (repositories) | ✅ M3-M4 livrés, intégré au runtime Vidocq |
| `mansart-pool` | — (post-Loom JDBC pool) | ✅ M2 livré (H2), M5 PostgreSQL livré |
| `mansart-dialect-spi` | — (SPI partagée) | ✅ M1 livré (H2 + PostgreSQL) |
| `mansart-persistence` | Jakarta Persistence 3.2 (JPA) | ⏸️ **M7 suspendu** — mansart-data fait le job pour les besoins runtime actuels |
| `mansart-transactions` | JTA minimal | ✅ extension livrée dans le runtime |

## Phases livrées

### M1 — Dialect SPI ✅
- Interface `Dialect` + `DialectFactory` chargée via `ServiceLoader`
- Implémentations H2 et PostgreSQL (génération SQL, types, paginated select,
  upsert/merge, identity)
- SPI partagée par `mansart-jakarta-data` et `mansart-persistence` (interop format
  proche du JPA static metamodel)

### M2 — Mansart Pool MVP (H2) ✅
- Pool JDBC virtual-thread-native (pas de ThreadLocal qui pin, pas de pool thread
  classique en interne)
- Configuration min/max, idle eviction, leak detection
- Zéro dépendance, indépendant du reste de Mansart (utilisable hors Mansart avec
  n'importe quel `DataSource`)
- Voir `mansart-pool/PLAN.md` pour les détails d'architecture

### M3 — Mansart Jakarta Data backend JDBC + H2 ✅
- Backend de référence : **JDBC pur**, pas JPA (évite la dépendance circulaire,
  binaire minimal, démontre Jakarta Data sans ORM)
- Génération statique des repositories via APT (`@Repository` → `XxxRepositoryImpl`
  à compile time, pas de proxy `java.lang.reflect.Proxy`)
- Métamodèle statique `_Book`, `_Author` produit par APT
- Query methods : findBy, countBy, deleteBy, BasicRepository, PageableRepository
- Pagination, Sort, Limit, Order

### M4 — PostgreSQL dialect ✅
- Adapter `Dialect` PostgreSQL complet
- Tests d'intégration via testcontainers (out-of-band, pas de dep test sur Mansart core)

### M5 — Mansart Pool PostgreSQL ✅
- Validation du pool avec PostgreSQL en charge
- Métriques (connections active, waiters, eviction rate)

### Extension Vidocq ✅
- `vidocq-runtime-mansart-data-extension` : intégration au runtime MicroProfile
  via SPI Vidocq (cf. [vidocq runtime ROADMAP](../vidocq/ROADMAP.md))
- `vidocq-runtime-mansart-pool-extension`
- `vidocq-runtime-mansart-transactions-extension`
- Exemple : `vidocq-runtime-mansart-h2-example`

## Phases en cours / planifiées

### M6 — TCK Jakarta Data 1.0 ⏳

**Objectif** : passer le TCK officiel Jakarta Data 1.0 sur les profils applicables.

- [ ] `mansart-jakarta-data-tck/` **hors reactor** (POM Model 4.0.0 standalone) —
      même contrainte ShrinkWrap que `cassini-tck` / `champollion-tck` / `humboldt-tck`
- [ ] Script `run-official-tck-data-1.0.sh` à la racine
- [ ] Catégoriser les expected failures et challenges officiels dans `TCK.md`
- [ ] Cible : 100 % PASS sur les tests applicables au backend JDBC (les tests
      dépendants d'un EntityManager seront en exclusion explicite tant que M7 est
      suspendu)

### M7 — Mansart Persistence (JPA 3.2) ⏸️ SUSPENDU

**Statut** : non démarré, suspendu jusqu'à un besoin concret côté runtime Vidocq
(`vidocq-runtime-mansart-h2-example` fonctionne aujourd'hui avec `mansart-data`
seul). Décision de priorisation revisitée si une extension MP ou un consommateur
externe le réclame.

Scope envisagé quand on démarrera :
- [ ] `EntityManager`, `EntityManagerFactory`, JPQL, Criteria API
- [ ] Cycle de vie (`@PrePersist`, `@PostLoad`, etc.)
- [ ] Relations `@OneToMany`/`@ManyToOne`/`@ManyToMany`, fetch lazy/eager
- [ ] Cache L2 optionnel (intégration avec Caffeine ou implémentation maison)
- [ ] TCK Jakarta Persistence 3.2 hors reactor

### M8 — Performance & footprint (TBD)
- [ ] Benchmarks JMH `mansart-jakarta-data` vs Spring Data JDBC, Eclipselink, Hibernate
- [ ] Benchmarks `mansart-pool` vs HikariCP, Agroal (focus virtual threads, pinning)
- [ ] Footprint mémoire AOT GraalVM (zéro réflexion runtime déjà respecté côté APT)

## Backlog technique transverse

- [ ] Dialectes additionnels : MySQL, Oracle, SQL Server, MariaDB (au besoin)
- [ ] Migrations DDL : intégration Flyway ou implémentation maison minimale
- [ ] Observability : intégration avec Humboldt (spans JDBC, métriques pool)

## Hors scope (explicite — cf. PLAN.md)

- ❌ ORM lourd (lazy loading dynamique, dirty checking sophistiqué) → JPA dans
      M7 si besoin, pas dans `mansart-data`
- ❌ Cache L2 par défaut → opt-in via SPI quand M7
- ❌ Reactive (Mutiny, R2DBC) → philosophie Vidocq = virtual threads, pas reactive

## Bugs

Pas de `BUG.md` aujourd'hui. Créer le fichier si une régression reproductible
apparaît (suivre le pattern des autres sous-projets : id court, date, symptôme,
repro, statut).

## Conventions de tracking

- **Cette roadmap** : milestones M-x, vision moyen terme.
- **`PLAN.md`** : architecture, décisions structurantes, vision globale.
- **`<sous-projet>/PLAN.md`** : architecture spécifique (existe pour `mansart-pool/`).
- **`TCK.md`** : sera créé en M6 (statut conformité Jakarta Data).
- **`BENCH.md`** : sera créé en M8 (chiffres JMH reproductibles).
