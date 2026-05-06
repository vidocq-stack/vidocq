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
├── mansart-transactions-core/       ← TM/UT/TSR + 2PC + recovery log
├── mansart-transactions-cdi/        ← @Transactional + @TransactionScoped (BCE Vauban)
├── mansart-transactions-jdbc/       ← ConnectionXAResource — adapte une Connection en XAResource (1PC)
├── mansart-transactions-tests/      ← intégration cross-module + H2 réel
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

### M5b — Auto-recovery driver-side *(livré)*

- ✅ `MansartTransactionManager.recover(XAResource...)` — croise les in-doubt records du
  journal Mansart avec les Xid remontés par `XAResource.recover(TMSTARTRSCAN|TMENDRSCAN)`
  de chaque driver fourni.
- ✅ COMMITTING + Xid présent côté driver → `commit(xid, false)` (replay du commit durable).
- ✅ PREPARED + Xid présent côté driver → `rollback(xid)` (pas de décision durable).
- ✅ Xid absent côté driver → reste dans `RecoveryReport.stillInDoubt` pour inspection humaine.
- ✅ Tolère `XAException` (driver scan ou commit/rollback) — surface comme still-in-doubt.
- ✅ Idempotent : un second appel résout ce que le premier n'avait pas pu (drivers reconnectés).
- ✅ 6 tests TDD : COMMITTING→commit, PREPARED→rollback, Xid orphelin, multi-drivers
  dispatch, COMPLETED ignoré, no-resources fallback.

### M6 — TCK officiel Jakarta Transactions 2.0 *(infra livrée)*

- ✅ `install-tck.sh` : auto-récupération depuis Eclipse Foundation, idempotent (verify/force/install).
- ✅ Adaptateur Java (`MansartTckProvider`, `MansartUserTransaction`).
- ✅ Smoke wiring 5/5 : `./run-official-tck-transactions-2.0.sh smoke` (lancé en CI).
- ✅ **M6b** : wrapper Maven antrun pour la suite tsharness (profile `full-tck`). Templating
  `ts.jte` Mansart-friendly (impl.vi=none, jta.classes pointant sur le M2), exec
  `ant build.all.tests` qui compile toutes les fixtures TCK contre le classpath Mansart.
  `BUILD SUCCESSFUL` validé sur les ~7 répertoires JTA EE + signature tests.
- ⏳ **M6c** : pilotage de chaque `ant runclient` leaf-par-leaf (~40 tests JTA EE) +
  parsing du rapport tsharness HTML pour résumer pass/fail dans
  `target/tck-report-transactions.txt`. La cdi en M7 + jdbc en M8 fournissent déjà tout
  le harness côté SUT — reste juste à itérer sur ts.jte par leaf et collecter.

### M7 — CDI interceptor + TransactionScoped *(livré)*

- ✅ `MansartTransactionsProducer` (@ApplicationScoped) — produit `TransactionManager`,
  `UserTransaction`, `TransactionSynchronizationRegistry` à partir d'un singleton TM partagé
  avec l'extension portable.
- ✅ `TransactionalInterceptor` + 5 sous-classes (`Required`/`RequiresNew`/`Mandatory`/`Never`/
  `NotSupported`/`Supports`) — six bindings distincts requis car
  `Transactional.value()` n'est PAS `@Nonbinding` dans jakarta.transaction-api 2.0.x. La logique
  reste dans le parent ; les sous-classes ne portent que le binding + `@Priority`.
  Couvre `rollbackOn` / `dontRollbackOn` (spec §3.7.1).
- ✅ `TransactionScopedContext` (`AlterableContext`) — instance par TX, destruction via
  `Synchronization.afterCompletion()`. Lance `ContextNotActiveException` hors TX.
- ✅ `MansartTransactionsExtension` (BCE) — enregistre `@TransactionScoped` via
  `MetaAnnotations.addContext(scope, isNormal, contextClass)` (API CDI 4.1 standard).
  Vauban honore nativement cette API (`VaubanMetaAnnotations` ligne 51), pas besoin
  d'`Extension` portable legacy ni de Weld.
- ✅ Tests Vauban (CDI 4.1 Lite, container natif Vidocq) via `vauban-junit` :
  14 tests interceptor + 4 tests scope = 18/18 verts.

### M8 — JDBC adapter `ConnectionXAResource` *(livré)*

- ✅ `mansart-transactions-jdbc/ConnectionXAResource` — wrappe une `java.sql.Connection`
  en `XAResource` (1PC only). Permet à `@Transactional` de fonctionner avec n'importe
  quel `DataSource` simple (pas besoin de `XADataSource`).
- ✅ `start()` flippe `autoCommit=false`, `commit()/rollback()` délèguent à JDBC,
  `autoCommit` original restauré au cleanup.
- ✅ Tests H2 in-memory : COMMIT persiste, ROLLBACK annule, autoCommit pre-existant honoré.
- ✅ `H2SingleResourceCommitTest` — débloqué (était `@Disabled` depuis M2).
- ⏳ Pour vrai 2PC multi-resources : nécessite un driver `XADataSource` (out of scope
  pour ce module ; le TM gère déjà le protocole).

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
