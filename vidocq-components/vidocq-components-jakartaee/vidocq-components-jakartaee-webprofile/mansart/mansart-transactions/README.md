# mansart-transactions

Implémentation **Jakarta Transactions 2.0** pour l'écosystème Vidocq — locale d'abord (1PC),
puis multi-resource (2PC), virtual-thread-native, packaged en plusieurs jars légers.

## Modules

| Module | Rôle |
| --- | --- |
| `mansart-transactions-api` | Re-exposition `jakarta.transaction-api` 2.0 + helpers SPI Mansart. |
| `mansart-transactions-core` | `TransactionManager` / `UserTransaction` / `TransactionSynchronizationRegistry` — implémentation `ScopedValue`. 1PC d'abord, 2PC plus tard. |
| `mansart-transactions-cdi` | Bootstrap CDI 4.1 — interceptor `@Transactional` (REQUIRED, REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) + scope `@TransactionScoped`, plug via `BuildCompatibleExtension`. |
| `mansart-transactions-tests` | Tests d'intégration H2 + `mansart-pool` (résolution résources, callbacks `Synchronization`, comportements de propagation). |
| `mansart-transactions-tck` | Runner TCK officiel — **HORS reactor** (modelVersion 4.0.0 standalone, voir CLAUDE.md racine). |

## Build

```bash
cd mansart-transactions && sdk env
./mvnw -ntp install -DskipTests   # ou : mvn -ntp install -DskipTests
mvn test                          # tests unitaires + intégration
```

## Architecture rapide

- **Contexte de transaction** porté par `ScopedValue<TransactionContext>` — un binding par virtual
  thread. Pas de `ThreadLocal`, pas de pinning.
- **Résources** enrôlées dynamiquement via `Transaction.enlistResource(XAResource)` ; en mode 1PC
  une seule resource active à la fois, prepare/commit dégénèrent en `commit()` direct.
- **Synchronisations** appelées à `beforeCompletion` (write-flush) et `afterCompletion`
  (cleanup, callbacks utilisateur).
- **2PC** (M4) : journal de recovery sur disque, restart-safe, tests de crash injectés.

## Roadmap

- **M1** — `MansartTransactionManager` : `begin/commit/rollback`, `STATUS_ACTIVE` /
  `STATUS_NO_TRANSACTION`. Tests TDD : `TransactionManagerSmokeTest`.
- **M2** — `Synchronization` : `registerSynchronization`, `beforeCompletion`,
  `afterCompletion(int status)`.
- **M3** — `suspend` / `resume` (TX inheritance via virtual threads).
- **M4** — Multi-resource : `enlistResource(XAResource)`, `delistResource(XAResource, int)`,
  prepare/commit/rollback à 2 phases.
- **M5** — Recovery log + tests de crash.
- **M6** — TCK Jakarta Transactions 2.0 (smoke puis full).
- **M7** — CDI : interceptor `@Transactional`, scope `@TransactionScoped`, BCE Vauban.

## TDD

Chaque jalon démarre par les tests qui décrivent le comportement attendu, puis l'implémentation
les fait passer un à un. Le seed M1 est `mansart-transactions-core/src/test/java/.../TransactionManagerSmokeTest.java` —
il échoue actuellement (squelette) pour amorcer le cycle rouge → vert → refactor.

## Bugs / Bench

- `BUG.md` — bugs reproductibles (tracker interne).
- `BENCH.md` — comparatif Narayana / Atomikos / JBoss TM (à venir).
