# mansart-pool — Plan

Pool de connexions JDBC **virtual-thread-native**, **zéro dépendance**, indépendant des autres modules Mansart.

## Pourquoi

HikariCP, Agroal et c3p0 ont été conçus pour des modèles à threads plateforme :
- HikariCP a souffert pendant longtemps de pinning sous virtual threads (bloc `synchronized` sur `ConcurrentBag`) — corrigé mais hérité d'un design pre-Loom.
- Agroal est mieux mais reste lié à WildFly/Quarkus.
- Aucun n'utilise `ScopedValue` pour propager la connexion dans une transaction.

Mansart Pool est conçu **post-Loom** depuis la première ligne : pas de `synchronized` autour d'une opération bloquante, `ScopedValue` pour le contexte, `StructuredTaskScope` pour le housekeeping. Sert d'abord les besoins de Mansart (tests, exemples, déploiement par défaut), reste utilisable seul par n'importe quelle app JDBC.

## Positionnement dans le workspace

Sous-projet **peer** de `mansart-jakarta-data` et `mansart-persistence` — pas un sous-module. Reactor Maven indépendant, déploiement séparé.

```
mansart/
├── mansart-jakarta-data/     ← consomme un DataSource standard (peu importe lequel)
├── mansart-persistence/      ← idem
└── mansart-pool/             ← FOURNIT un DataSource (utilisable hors Mansart)
```

Aucun lien de dépendance Maven entre les trois — découplage strict. `mansart-jakarta-data/mansart-data-tests` peut **optionnellement** dépendre de `mansart-pool` en `<scope>test</scope>` pour les tests d'intégration.

## Modules

```
mansart-pool/
├── pom.xml                          ← reactor mansart-pool
├── mansart-pool-api/                ← PoolConfig, PoolMetrics, PoolException (zéro dep hors java.sql)
├── mansart-pool-core/               ← MansartDataSource (impl javax.sql.DataSource)
└── mansart-pool-tests/              ← unit + JMH micro-bench
```

## API publique (mansart-pool-api)

```java
public final class MansartPool {
    public static MansartDataSource of(PoolConfig config) { … }
}

public record PoolConfig(
    String  jdbcUrl,
    String  username,
    String  password,
    int     minIdle,                  // par défaut 0 (truly elastic)
    int     maxSize,                  // par défaut 10
    Duration acquireTimeout,          // par défaut 5s
    Duration idleTimeout,             // par défaut 10min
    Duration maxLifetime,             // par défaut 30min
    Duration validationTimeout,       // par défaut 1s
    ValidationMode validation,        // NEVER | ON_BORROW | PERIODIC (default: ON_BORROW si validationQuery != null)
    String  validationQuery,          // null = utilise Connection.isValid()
    Duration leakDetectionThreshold,  // par défaut DISABLED
    Map<String,String> driverProperties
) {
    public static Builder builder() { … }
}

public sealed interface PoolMetrics {
    int active();
    int idle();
    int waiting();
    long totalBorrows();
    long totalTimeouts();
    Duration meanBorrowDuration();
    // … snapshot immutable
}
```

## Architecture runtime

### Structure

```
MansartDataSource (impl DataSource)
    │
    ├── Deque<PooledConnection> idle           (ConcurrentLinkedDeque, lock-free)
    ├── Set<PooledConnection>   inUse          (ConcurrentHashMap.newKeySet)
    ├── Semaphore               permits        (taille = maxSize, fair=false)
    ├── ScopedValue<Connection> CURRENT        (pour propager dans une tx)
    └── ScheduledTask           housekeeper    (virtual thread, StructuredTaskScope)
```

### Acquisition (`getConnection()`)

```
1. permits.tryAcquire(acquireTimeout)        ← block virtual thread, jamais synchronized
   ↓ timeout → PoolException(ACQUIRE_TIMEOUT)
2. PooledConnection pc = idle.pollFirst()    ← lock-free
   if pc != null && validate(pc):
       inUse.add(pc); return pc.proxy()
3. pc = createNew()                          ← driver.connect(...)
   inUse.add(pc); return pc.proxy()
```

**Pas de `synchronized` sur le chemin chaud.** Le `Semaphore` est le seul point de blocage et il est virtual-thread-friendly (utilise `LockSupport.park`, pas un mutex natif).

### Restitution (`Connection.close()` sur le proxy)

```
1. pc.reset()                                ← rollback si dirty, autoCommit=true, default isolation
2. inUse.remove(pc)
3. if pc.aliveAndYoung(): idle.offerFirst(pc)
   else: pc.realClose()
4. permits.release()
```

Le proxy est généré au build via **Class-File API (JEP 484)** — **pas** via `java.lang.reflect.Proxy` (philosophie Vidocq). Une seule classe `PooledConnectionProxy` qui implémente `Connection` et délègue à `pc.delegate`, en interceptant `close()` pour appeler la restitution.

### Housekeeper

Virtual thread démarré au `MansartDataSource.start()` :
- Toutes les `idleTimeout / 4`, scanne `idle` et ferme les connexions inactives depuis trop longtemps.
- Toutes les `maxLifetime / 4`, ferme les connexions qui ont dépassé leur durée de vie.
- Si `leakDetectionThreshold` activé, scanne `inUse` et logge (System.Logger WARNING) les connexions empruntées depuis trop longtemps avec leur stack trace de borrow.

### Intégration `ScopedValue` pour transactions

```java
public static <T> T inTransaction(MansartDataSource ds, SqlCallable<T> body) throws SQLException {
    Connection c = ds.getConnection();
    c.setAutoCommit(false);
    try {
        T result = ScopedValue.where(MansartPool.CURRENT, c).call(body);
        c.commit();
        return result;
    } catch (Throwable t) {
        c.rollback(); throw t;
    } finally {
        c.close();   // retour au pool
    }
}

// Dans le code utilisateur, n'importe où dans le virtual thread enfant :
Connection c = MansartPool.CURRENT.orElseGet(() -> ds.getConnection());
```

`mansart-jakarta-data/RepositoryRuntime` peut utiliser ce `ScopedValue` quand `mansart-pool` est présent (détection via `ServiceLoader<TransactionContextProvider>`), et tomber sur le mode auto-commit sinon.

## Décisions de conception

| Choix | Justification |
| --- | --- |
| `Semaphore` au lieu de `BlockingQueue` | Sépare la gestion du quota de la file des connexions ; permet de créer à la demande. |
| `ConcurrentLinkedDeque` LIFO pour `idle` | Réutilise la connexion la plus chaude (cache CPU + JIT du driver). |
| Proxy via Class-File API | Pas de `Proxy.newProxyInstance` (réflexion runtime) ; AOT-compatible. |
| Pas de JTA en v1 | Hors-scope. Si besoin XA, Mansart Pool sera enrichi en v1.1 ou délégué à un pool externe. |
| Validation par défaut = `Connection.isValid(1)` | Standard JDBC 4, pas de query magique. Override possible. |
| `System.Logger` (pas SLF4J) | Zéro-dep + JPMS-friendly. |
| Pas de pool de prepared statements | Les drivers modernes (PG, H2) le font côté serveur ou côté driver. Garder le pool simple. |

## Plan de travail (jalons)

### MP1 — API + skeleton (semaine A)
- [ ] `pom.xml` parent + 3 sous-modules.
- [ ] `module-info.java` pour chacun.
- [ ] `mansart-pool-api` : `PoolConfig`, `PoolConfig.Builder`, `PoolMetrics`, `PoolException`, `ValidationMode`.
- [ ] Tests JUnit 6 sur la builder + validations de config (cohérence min/max, durées positives, etc.).

### MP2 — Implémentation core (semaine B)
- [ ] `MansartDataSource` : acquisition/restitution, semaphore, deque, validation `Connection.isValid()`.
- [ ] Proxy `PooledConnectionProxy` généré par Class-File API au compile-time (au sein du module `mansart-pool-core`, via un goal Maven custom — ou reportable en v1.1 et en attendant un `record`-based wrapper qui implémente toutes les méthodes `Connection` à la main).
- [ ] Housekeeper virtual thread.
- [ ] Tests : 1000 borrows parallèles sur H2 in-memory, vérifier 0 pinning (`-Djdk.tracePinnedThreads=full`), 0 timeout.

### MP3 — Métriques + leak detection (semaine C)
- [ ] `PoolMetrics` snapshot live.
- [ ] Leak detector + log structuré.
- [ ] Bench JMH : comparatif vs HikariCP (acquisition latence p50/p99, throughput sous virtual threads).
- [ ] Entrée `BENCH.md` initiale.

### MP4 — Intégration mansart-jakarta-data (semaine D)
- [ ] Dans `mansart-jakarta-data/mansart-data-tests`, ajouter un profil `-Pmansart-pool` qui utilise `MansartDataSource` au lieu d'un `JdbcDataSource` brut.
- [ ] `TransactionContextProvider` SPI dans `mansart-data-core`, implémentation `MansartPoolTransactionContext` dans `mansart-pool-core`, découverte ServiceLoader.

## Décisions ouvertes (avant MP1)

1. **Génération du proxy `Connection`** : Class-File API au build (clean mais lourd à mettre en place pour un seul proxy) **ou** classe écrite à la main qui implémente les ~50 méthodes de `java.sql.Connection` (verbeux mais simple, pas de Maven plugin) ? Proposition : à la main pour MP2, migrer vers Class-File API en MP3 si on en génère plusieurs (proxies pour `PreparedStatement`, `Statement`, `ResultSet`).
2. **Driver loading** : `Driver.connect(url)` direct (passe par `DriverManager`) ou exiger un `XADataSource`/`ConnectionPoolDataSource` ? Proposition : `Driver.connect()` direct en v1, pas de XA.
3. **Configuration via `vidocq.properties` ?** : si `vidocq` (le sous-projet d'orchestration) est présent, est-ce qu'on lit la config depuis ses properties ? Proposition : non, pas de couplage. `mansart-pool` reste utilisable hors Vidocq. Une extension `vidocq-runtime-mansart-pool` pourra faire le pont plus tard.

→ Ces décisions n'ont pas besoin d'être tranchées tout de suite — elles concernent la phase pool, qui démarre **après** M3 de `mansart-jakarta-data` (on a besoin du dialecte H2 pour tester sérieusement).
