# mansart-transactions — Plan

## Mission

Implémentation **Jakarta Transactions 2.0** complète, mais montée par jalons utilisables seuls :
1PC d'abord (la majorité des apps), puis 2PC + recovery (le reste). Virtual-thread-native depuis
la première ligne. Réutilise `mansart-pool` pour les tests d'intégration JDBC.

## Pourquoi un nouveau projet

- Narayana (RedHat) et Atomikos sont massifs, hérités d'EE7 et d'EJB, design antérieur à Loom.
- Bitronix est mort.
- Aucun n'utilise `ScopedValue` — tous reposent sur `ThreadLocal`, source de pinning sous virtual
  threads dès qu'on combine TX + JDBC.
- Le besoin Mansart : un TM léger, qui fait juste ce que `mansart-jakarta-data` /
  `mansart-persistence` consomment, et qui passe le TCK officiel.

## Modules

```
mansart-transactions/
├── mansart-transactions-api/        ← re-exposition jakarta.transaction-api
├── mansart-transactions-core/       ← TM/UT/TSR + ScopedValue context
├── mansart-transactions-cdi/        ← @Transactional + @TransactionScoped (BCE)
├── mansart-transactions-tests/      ← intégration H2 + mansart-pool
└── mansart-transactions-tck/        ← TCK officiel — HORS reactor
```

## Roadmap

### M1 — Local single-thread (TM, UT, status)

`MansartTransactionManager.begin/commit/rollback`. `getStatus()` reflète l'état
(`STATUS_ACTIVE`, `STATUS_MARKED_ROLLBACK`, `STATUS_NO_TRANSACTION`).
Contexte stocké dans `ScopedValue<TransactionContext>` ré-assigné autour de chaque opération.
Tests TDD : `TransactionManagerSmokeTest` (déjà écrit, rouge).

### M2 — Synchronisations

`Transaction.registerSynchronization(Synchronization)`. Ordre d'appel respecté
(`beforeCompletion` AVANT le flush des resources, `afterCompletion(int)` APRÈS).
Tests TDD : `SynchronizationOrderingTest`.

### M3 — Suspend / resume

`TransactionManager.suspend()` retourne le `Transaction` actuel et clear le contexte ;
`resume(Transaction)` ré-installe. Permet `TxType.REQUIRES_NEW` plus tard.
Tests TDD : `SuspendResumeTest` + scénarios threads parallèles.

### M4 — Multi-resource (2PC dégénéré → 2PC vrai)

`Transaction.enlistResource(XAResource)`. En 1PC dégénéré (1 resource), le commit applique
direct sans prepare. Dès qu'on enrôle 2+ resources, on bascule en vrai 2PC :
prepare/commit/rollback en deux passes.
Tests TDD : `TwoResourceCommitTest`, `TwoResourceRollbackOnPrepareTest`.

### M5 — Recovery log

Journal append-only sur disque (`tx-recovery.log`). À chaque prepare, on persiste l'intent.
Au démarrage, scan + replay : commit ou rollback selon l'état du journal.
Tests TDD : `RecoveryAfterCrashIT` (kill -9 entre prepare et commit).

### M6 — TCK officiel Jakarta Transactions 2.0

Câble le harness, premier run smoke, fix-jusqu'à-passer. Cible : 100% PASS sur le profil "core".

### M7 — CDI interceptor + TransactionScoped

- `MansartTransactionsExtension` (BCE) déclare le bean `TransactionManager` synthétique.
- `TransactionalInterceptor` intercepte `@Transactional` sur les méthodes/classes managed.
  Six TxTypes : REQUIRED (par défaut), REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS.
  `rollbackOn` / `dontRollbackOn` honorés.
- `TransactionScopedContext` (Context CDI) — beans créés au `begin`, détruits à
  `afterCompletion`.
Tests TDD côté `mansart-transactions-cdi` + intégration via Vauban dans `mansart-transactions-tests`.

## Hors scope (pour le moment)

- **JTS** (Java Transaction Service / CORBA OTS) — Jakarta Transactions 2.0 a déprécié l'API
  liée au protocole CORBA. Pas d'investissement.
- **Distribuées XA cross-process** — Mansart est in-process, le 2PC s'arrête à la frontière JVM.
  Une JVM = un coordinator local.
- **Last-resource commit optimization** (LLR) — peut s'ajouter en M4+ si besoin.

## Dépendances inter-Mansart

- `mansart-transactions` → indépendant (peer de `mansart-pool` et `mansart-jakarta-data`).
- `mansart-jakarta-data` consommera `mansart-transactions-core` via la SPI standard
  `TransactionSynchronizationRegistry` pour participer aux TX actives (M3 du plan
  `mansart-jakarta-data`).
- `mansart-pool` reste agnostique — il expose juste un `DataSource`. L'enrôlement JDBC
  dans une TX active est fait par un wrapper `XADataSource` adapter qu'on ajoutera à
  `mansart-transactions-core` (sub-module ou utility).

## Conventions

- Zéro réflexion, zéro génération bytecode runtime — Class-File API si jamais nécessaire.
- Zéro dep externe hors `jakarta.transaction-api`, `jakarta.cdi-api`, `jakarta.inject-api`,
  `jakarta.interceptor-api`, `jakarta.annotation-api`. Pas d'Apache Commons, pas de SLF4J.
- Tests d'intégration sur **vraie** DB H2 — pas de mock JDBC.
- Bugs reproductibles → `BUG.md`. Mesures perf → `BENCH.md`.
