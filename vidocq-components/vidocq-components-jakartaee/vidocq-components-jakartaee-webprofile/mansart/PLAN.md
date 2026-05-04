# Mansart — Plan global

## Vision

Trois sous-projets indépendants en runtime, conçus pour être utilisés séparément ou ensemble :

```
                    ┌──────────────────────────────┐
   utilisateur  →   │   mansart-jakarta-data       │   ← Jakarta Data 1.0 (repositories)
                    └──────────────┬───────────────┘
                                   │ peut s'appuyer sur
                                   ▼
                    ┌──────────────────────────────┐
                    │   mansart-persistence        │   ← Jakarta Persistence 3.2 (JPA)
                    └──────────────┬───────────────┘
                                   │ consomme
                                   ▼
                          ┌────────────────┐
                          │   DataSource   │   ← standard javax.sql
                          │   (au choix)   │      Hikari, Agroal, ou…
                          └────────┬───────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │   mansart-pool               │   ← pool JDBC virtual-thread-native (optionnel, indep)
                    └──────────────────────────────┘

                          ┌────────────────┐
                          │  Dialect SPI   │   ← partagée par data + persistence
                          └────────┬───────┘
                                   │
                          ┌────────┴────────┐
                          │                 │
                          ▼                 ▼
                       H2 (M3)        PostgreSQL (M4)
```

### Pourquoi trois sous-projets

- `mansart-jakarta-data` est utilisable **sans JPA** — backend JDBC direct, métamodèle généré par APT, repositories compilés statiquement. Cible : applications Vidocq qui veulent du repository pattern sans le poids de JPA.
- `mansart-persistence` apporte JPA 3.2 quand l'utilisateur a besoin d'`EntityManager`, de cycle de vie complet, de cache de second niveau, ou de mapping ORM riche. Quand les deux modules sont présents, `mansart-jakarta-data` peut déléguer à `EntityManager` au lieu de JDBC pur.
- `mansart-pool` (optionnel) fournit un `DataSource` JDBC **virtual-thread-native** et zéro-dépendance. Aucune dépendance Maven entre les trois sous-projets — `mansart-jakarta-data` se fiche de quel pool l'utilisateur lui passe (Hikari, Agroal, Mansart Pool…).
- Cette séparation reflète la philosophie Jakarta Data elle-même (un même contrat repository, plusieurs providers de stockage).

## Ordre de travail

1. **mansart-jakarta-data** (M2 → M6) — démarre maintenant.
2. **mansart-pool** (MP1 → MP4) — démarre après M3 de Data (besoin du dialecte H2 pour benchmarker sérieusement). Indépendant — peut être livré seul.
3. **mansart-persistence** (M7+) — viendra après stabilisation du dialecte SPI et du TCK Data.

Voir `mansart-jakarta-data/PLAN.md` pour le plan détaillé du premier module.

## Décisions structurantes (à valider)

1. **Backend de référence pour Jakarta Data : JDBC pur**, pas JPA. Raison : éviter la dépendance circulaire avec `mansart-persistence`, garder un binaire minimal, et démontrer qu'on peut implémenter Jakarta Data sans ORM lourd.
2. **Génération statique des repositories** via APT. Une interface `@Repository` annotée produit une classe `XxxRepositoryImpl` à `compile`. Pas de proxy `java.lang.reflect.Proxy`, pas de runtime weaving.
3. **Métamodèle statique partagé** : APT produit `_Book`, `_Author`, etc. réutilisables par les deux modules (Data et Persistence) — même format que JPA static metamodel pour interop.
4. **Dialecte = SPI publique** chargée via `ServiceLoader` (`provides DialectFactory with …` dans `module-info.java`). H2 et PostgreSQL d'abord, MySQL/Oracle/SQL Server plus tard. La SPI est partagée par les deux modules.
5. **Pool de connexions = sous-projet peer indépendant** (`mansart-pool`), pas une dépendance de `mansart-jakarta-data`. L'utilisateur fournit son `DataSource` (Hikari, Agroal, Mansart Pool…) au runtime Data. Mansart Pool est virtual-thread-native, conçu post-Loom (pas de pinning), zéro-dep. Voir [`mansart-pool/PLAN.md`](./mansart-pool/PLAN.md).
6. **TCK Jakarta Data 1.0 hors reactor** (POM Model 4.0.0 standalone) — même contrainte ShrinkWrap que les autres TCK Vidocq.

## Hors scope (explicite)

- Multi-tenancy.
- Cache de second niveau (relève de JPA → `mansart-persistence` plus tard).
- Migrations de schéma (Flyway/Liquibase à intégrer côté application).
- NoSQL (Jakarta Data accepte d'autres backends mais ce n'est pas un objectif Mansart).
- Reactive / R2DBC (Jakarta Data 1.0 est synchrone ; Virtual Threads couvrent le besoin).
