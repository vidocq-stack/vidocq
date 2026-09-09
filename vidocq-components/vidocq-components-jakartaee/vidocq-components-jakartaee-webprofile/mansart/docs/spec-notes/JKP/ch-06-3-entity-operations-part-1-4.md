# 3. Entity Operations (part 1/4) — Normative Requirements

## 3.1. Overview

## 3.2. EntityManager Interface

3.2 — The persist, merge, remove, and refresh methods must be invoked within a transaction context when an entity manager with a transaction-scoped persistence context is used.
3.2 — If there is no transaction context when persist/merge/remove/refresh is invoked with transaction-scoped persistence context, the jakarta.persistence.TransactionRequiredException is thrown.
3.2 — Methods that specify a lock mode other than LockModeType.NONE must be invoked within a transaction.
3.2 — If there is no transaction or if the entity manager has not been joined to the transaction, the jakarta.persistence.TransactionRequiredException is thrown.
3.2 — The Query, TypedQuery, StoredProcedureQuery, CriteriaBuilder, Metamodel, and EntityTransaction objects obtained from an entity manager are valid while that entity manager is open.
3.2 — A property defined by this specification must be observed by the provider unless otherwise explicitly stated.
3.2 — A hint specifies a preference on the part of the application. A portable application must not depend on the observance of a hint.

## 3.3. Entity Instance's Life Cycle

3.3.2 — If X is a new entity, it becomes managed by the persist operation. The entity X will be entered into the database at or before transaction commit or as a result of the flush operation.
3.3.2 — If X is a detached object, the EntityExistsException may be thrown when the persist operation is invoked, or the EntityExistsException or another PersistenceException may be thrown at flush or commit time.
3.3.2 — For all entities Y referenced by a relationship from X, if the relationship to Y has been annotated with the cascade element value cascade=PERSIST or cascade=ALL, the persist operation is applied to Y.

## 3.3.3. Removal

3.3.3 — A managed entity instance becomes removed by invoking the remove method on it or by cascading the remove operation.
3.3.3 — If X is a managed entity, the remove operation causes it to become removed.
3.3.3 — If X is a detached entity, an IllegalArgumentException will be thrown by the remove operation (or the transaction commit will fail).
3.3.3 — A removed entity X will be removed from the database at or before transaction commit or as a result of the flush operation.
3.3.3 — After an entity has been removed, its state (except for generated state) will be that of the entity at the point at which the remove operation was called.

## 3.3.4. Synchronization to the Database

3.3.4 — The state of persistent entities is synchronized to the database at transaction commit.
3.3.4 — Synchronization to the database does not involve a refresh of any managed entities unless the refresh operation is explicitly invoked on those entities or cascaded to them as a result of the specification of the cascade=REFRESH or cascade=ALL annotation element value.
3.3.4 — It is the developer's responsibility to keep the in-memory references held on the owning side and those held on the inverse side consistent with each other when they change.
3.3.4 — If FlushModeType.COMMIT is specified, flushing will occur at transaction commit; the persistence provider is permitted, but not required, to perform the flush at other times.
3.3.4 — If there is no transaction active or if the persistence context has not been joined to the current transaction, the persistence provider must not flush to the database.
3.3.4 — If X is a managed entity, it is synchronized to the database by the flush operation.
3.3.4 — For all entities Y referenced by a relationship from X, if the relationship to Y has been annotated with the cascade element value cascade=PERSIST or cascade=ALL, the persist operation is applied to Y by flush.
3.3.4 — If Y is new or removed and the relationship to Y has not been annotated with cascade=PERSIST or cascade=ALL, an IllegalStateException will be thrown by the flush operation (and the transaction marked for rollback) or the transaction commit will fail.

## 3.3.5. Refreshing an Entity Instance

3.3.5 — If X is a managed entity, the state of X is refreshed from the database, overwriting changes made to the entity, if any, by the refresh operation.
3.3.5 — The refresh operation is cascaded to entities referenced by X if the relationship from X to these other entities is annotated with the cascade=REFRESH or cascade=ALL annotation element value.
3.3.5 — If X is a new, detached, or removed entity, the IllegalArgumentException is thrown.

## 3.3.6. Evicting an Entity Instance from the Persistence Context

3.3.6 — Applications must use the flush method prior to the detach method to ensure portable semantics if changes have been made to the entity (including removal of the entity).
3.3.6 — Portable applications must not assume that changes have not been written to the database if the flush method has not been called prior to detach.
3.3.6 — If X is a managed entity, the detach operation causes it to become detached.
3.3.6 — The detach operation is cascaded to entities referenced by X if the relationships from X to these other entities is annotated with the cascade=DETACH or cascade=ALL annotation element value.
3.3.6 — If X is a removed entity, the detach operation causes it to become detached.
3.3.6 — Portable applications should not pass removed entities that have been detached from the persistence context to further EntityManager operations.

## 3.3.7. Detached Entities

3.3.7 — Detached entity instances continue to live outside of the persistence context in which they were persisted or retrieved. Their state is no longer guaranteed to be synchronized with the database state.

### 3.3.7.1. Merging Detached Entity State

3.3.7.1 — If X is a detached entity, the state of X is copied onto a pre-existing managed entity instance X' of the same identity or a new managed copy X' of X is created.
3.3.7.1 — If X is a new entity instance, a new managed entity instance X' is created and the state of X is copied into the new managed entity instance X'.
3.3.7.1 — If X is a removed entity instance, an IllegalArgumentException will be thrown by the merge operation (or the transaction commit will fail).
3.3.7.1 — If X is a managed entity, it is ignored by the merge operation, however, the merge operation is cascaded to entities referenced by relationships from X if these relationships have been annotated with the cascade element value cascade=MERGE or cascade=ALL annotation.
3.3.7.1 — For all entities Y referenced by relationships from X having the cascade element value cascade=MERGE or cascade=ALL, Y is merged recursively as Y'.
3.3.7.1 — The persistence provider must not merge fields marked LAZY that have not been fetched: it must ignore such fields when merging.
3.3.7.1 — Any Version columns used by the entity must be checked by the persistence runtime implementation during the merge operation and/or at flush or commit time.

### 3.3.7.2. Detached Entities and Lazy Loading

3.3.7.2 — A vendor is required to support the serialization and subsequent deserialization and merging of detached entity instances (which may contain lazy properties or fields and/or relationships that have not been fetched) back into a separate JVM instance of that vendor's runtime, where both runtime instances have access to the entity classes and any required vendor persistence implementation classes.
3.3.7.2 — When interoperability across vendors is required, the application must not use lazy loading.

## 3.3.8. Managed Instances

3.3.8 — It is the responsibility of the application to insure that an instance is managed in only a single persistence context.

## 3.3.9. Load State

3.3.9 — An entity is considered to be loaded if all attributes with FetchType.EAGER —whether explicitly specified or by default—(including relationship and other collection-valued attributes) have been loaded from the database or assigned by the application.
3.3.9 — An attribute that is an embeddable is considered to be loaded if the embeddable attribute was loaded from the database or assigned by the application, and, if the attribute references an embeddable instance (i.e., is not null), the embeddable instance state is known to be loaded (i.e., all attributes of the embeddable with FetchType.EAGER have been loaded from the database or assigned by the application).
3.3.9 — A collection-valued attribute is considered to be loaded if the collection was loaded from the database or the value of the attribute was assigned by the application, and, if the attribute references a collection instance (i.e., is not null), each element of the collection (e.g. entity or embeddable) is considered to be loaded.
3.3.9 — A single-valued relationship attribute is considered to be loaded if the relationship attribute was loaded from the database or assigned by the application, and, if the attribute references an entity instance (i.e., is not null), the entity instance state is known to be loaded.
3.3.9 — The PersistenceUtil.isLoaded methods return true if the above conditions hold, and false otherwise.
