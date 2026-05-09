# Mansart

<p align="center">
  <img src="mansart-logo.png" alt="Mansart" width="300">
</p>

Implémentation Jakarta Data 1.0 + Jakarta Persistence 3.2 pour l'écosystème Vidocq.

Le nom rend hommage à **Jules Hardouin-Mansart**, architecte du XVIIᵉ siècle (Versailles, Invalides, place Vendôme) — l'idée de structures durables, modulaires et soigneusement assemblées colle au rôle de la couche persistance dans la stack.

## Sous-projets

| Sous-projet | Description |
| --- | --- |
| `mansart-jakarta-data` | Implémentation **Jakarta Data 1.0** (repositories, pagination, query methods). Backend JDBC direct, dialectes pluggables. |
| `mansart-persistence`  | Implémentation **Jakarta Persistence 3.2** (JPA), réutilisée par `mansart-jakarta-data` pour les repositories typés entité quand l'app fournit déjà un `EntityManager`. |
| `mansart-pool`         | Pool JDBC **virtual-thread-native** post-Loom, zéro dépendance, optionnel et indépendant. Utilisable hors Mansart. |

## Philosophie (héritée de Vidocq)

- **JPMS strict**, pas de classpath.
- **Class-File API (JEP 484) + APT** pour générer les implémentations de `@Repository` et le métamodèle statique à la compilation. Aucune réflexion runtime, aucun proxy dynamique.
- **Zéro dépendance externe** hors specs Jakarta. JDBC natif, pas de Hibernate, pas de Spring Data, pas de QueryDSL.
- **Virtual Threads** pour toutes les exécutions de requête.
- **TDD + Arquillian** pour le harness TCK.

## Statut

🚧 En conception — voir [`PLAN.md`](./PLAN.md) pour la vision globale et [`mansart-jakarta-data/PLAN.md`](./mansart-jakarta-data/PLAN.md) pour le plan détaillé du premier module.

## Dialectes prioritaires

1. **H2** (mode embarqué — cible des tests unitaires et du TCK in-memory).
2. **PostgreSQL** (cible production de référence).

Les autres (MySQL, MariaDB, Oracle, SQL Server, SQLite, DuckDB) viendront après stabilisation de la SPI dialecte.
