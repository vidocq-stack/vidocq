# Ch-16-7: Entity Managers and Persistence Contexts — Normative Requirements

## Section 7.1 — Persistence Contexts

**7.1** Both container-managed entity managers and application-managed entity managers and their persistence contexts are required to be supported in Jakarta EE web containers and EJB containers.

**7.1** In Java SE environments and in Jakarta EE application client containers, only application-managed entity managers are required to be supported.

## Section 7.2 — Obtaining an EntityManager

**7.2** When application-managed entity managers are used, the application must use the entity manager factory to manage the entity manager and persistence context lifecycle.

**7.2** An entity manager must not be shared among multiple concurrently executing threads, as the entity manager and persistence context are not required to be threadsafe.

**7.2** Entity managers must only be accessed in a single-threaded manner.

## Section 7.4 — EntityManagerFactory Interface

**7.4** Entries that make use of the namespace `jakarta.persistence` and its subnamespaces must not be used for vendor-specific information. The namespace `jakarta.persistence` is reserved for use by this specification.

## Section 7.5 — Controlling Transactions

**7.5** A container-managed entity manager must be a JTA entity manager. JTA entity managers are only specified for use in Jakarta EE containers.

**7.5** Both JTA entity managers and resource-local entity managers are required to be supported in Jakarta EE web containers and EJB containers.

## Section 7.5.3 — The EntityTransaction Interface

**7.5.3** When a resource-local entity manager is used, and the persistence provider runtime throws an exception defined to cause transaction rollback, the persistence provider must mark the transaction for rollback.

**7.5.3** If the `EntityTransaction.commit` operation fails, the persistence provider must roll back the transaction.

## Section 7.6 — The runInTransaction and callInTransaction Methods

**7.6** When the argument function returns or throws an exception, this EntityManager must be closed before `runInTransaction` or `callInTransaction` returns.

**7.6** If the transaction type of the persistence unit is JTA and there is a JTA transaction already associated with the caller, and the argument function throws an exception, the JTA transaction must be marked for rollback, and the exception must be rethrown by `runInTransaction` or `callInTransaction`.

**7.6** If the transaction type of the persistence unit is resource-local, or if there is no JTA transaction already associated with the caller, and the argument function throws an exception, this transaction must be rolled back, and then the exception must be rethrown by `runInTransaction` or `callInTransaction`.

**7.6** If the argument function returns, then `runInTransaction` or `callInTransaction` must attempt to commit the transaction. If the attempt to commit the transaction fails, the exception must be rethrown.

## Section 7.7.1 — Persistence Context Synchronization Type

**7.7.1** A persistence context of type `SynchronizationType.UNSYNCHRONIZED` must not be flushed to the database unless it is joined to a transaction.

## Section 7.7.4 — Persistence Context Propagation

**7.7.4** Propagation of persistence contexts only applies within a local environment. Persistence contexts are not propagated to remote tiers.

---

**Total: 17 normative requirements**
