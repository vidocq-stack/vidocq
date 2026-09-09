# ch-07-3-entity-operations-part-2-4 — Normative Requirements

3.5.4.1 The implementation must ensure that the requirements of LockModeType.OPTIMISTIC_FORCE_INCREMENT are met when a versioned object is updated or removed, even if no explicit call to EntityManager.lock was made.

3.5.4.2 The entity manager must ensure that phenomenon P1 (dirty read) cannot occur when transaction T1 calls lock(entity, LockModeType.PESSIMISTIC_READ) or lock(entity, LockModeType.PESSIMISTIC_WRITE) on an object.

3.5.4.2 The entity manager must ensure that phenomenon P2 (non-repeatable read) cannot occur when transaction T1 calls lock(entity, LockModeType.PESSIMISTIC_READ) or lock(entity, LockModeType.PESSIMISTIC_WRITE) on an object.

3.5.4.2 Any pessimistic lock must be obtained immediately and retained until transaction T1 completes (commits or rolls back).

3.5.4.2 The persistence implementation must support calling lock(entity, LockModeType.PESSIMISTIC_READ) and lock(entity, LockModeType.PESSIMISTIC_WRITE) on a non-versioned entity as well as on a versioned entity.

3.5.4.2 When the lock cannot be obtained and the database locking failure results in transaction-level rollback, the provider must throw the PessimisticLockException and ensure that the JTA transaction or EntityTransaction has been marked for rollback.

3.5.4.2 When the lock cannot be obtained and the database locking failure results in only statement-level rollback, the provider must throw the LockTimeoutException (and must not mark the transaction for rollback).

3.5.4.2 When an application locks an entity with LockModeType.PESSIMISTIC_READ and later updates that entity, the lock must be converted to an exclusive lock when the entity is flushed to the database.

3.5.4.2 When lock conversion fails and the database locking failure results in transaction-level rollback, the provider must throw the PessimisticLockException and ensure that the JTA transaction or EntityTransaction has been marked for rollback.

3.5.4.2 When lock conversion fails and the database locking failure results in only statement-level rollback, the provider must throw the LockTimeoutException (and must not mark the transaction for rollback).

3.5.4.2 When lock(entity, LockModeType.PESSIMISTIC_READ), lock(entity, LockModeType.PESSIMISTIC_WRITE), or lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) is invoked on a versioned entity that is already in the persistence context, the provider must also perform optimistic version checks when obtaining the lock.

3.5.4.2 An OptimisticLockException must be thrown if optimistic version checks fail when obtaining a pessimistic lock on a versioned entity already in the persistence context.

3.5.4.2 If transaction T1 calls lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) on a versioned object, the entity manager must avoid phenomena P1 and P2 and must also force an update (increment) to the entity's version column.

3.5.4.2 When lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) cannot be supported on a non-versioned object, the implementation must throw the PersistenceException.

3.5.4.2 LockModeType.PESSIMISTIC_FORCE_INCREMENT must always prevent phenomena P1 and P2, whether for versioned or non-versioned objects.

3.5.4.2 If a versioned object locked with LockModeType.PESSIMISTIC_READ or LockModeType.PESSIMISTIC_WRITE is updated, then the implementation must ensure that the requirements of LockModeType.PESSIMISTIC_FORCE_INCREMENT are met.

3.5.5 The jakarta.persistence.lock.scope property, when specified, must be observed. The provider is permitted to lock more (but not fewer) rows than requested.

3.5.5 When the jakarta.persistence.lock.timeout hint is used in the createEntityManagerFactory method, the persistence.xml file, and the NamedQuery annotation, the timeout hint serves as a default value which can be selectively overridden by use in the methods of the EntityManager, Query, and TypedQuery interfaces.

3.5.5 Vendor-specific hints must not use the jakarta.persistence namespace.

3.5.5 Vendor-specific hints must be ignored if they are not understood.

3.5.5 If the same property or hint is specified more than once, the order of overriding applies in order of decreasing precedence: argument to method of EntityManager/Query/TypedQuery interface, specification to NamedQuery (annotation or XML), argument to createEntityManagerFactory method, specification in persistence.xml.

3.6.1 The entity listener class must have a public no-arg constructor.

3.6.1 If CDI is not enabled, the persistence provider must not invoke entity listeners that depend upon CDI injection.

3.6.1 In supporting injection into entity listeners, the persistence provider must behave as if it carries out the steps involving the use of the CDI SPI (obtain BeanManager, create AnnotatedType, create InjectionTarget, create CreationalContext, produce, inject, postConstruct).

3.6.1 When the listener instance is to be destroyed, the persistence provider must behave as if it carries out the steps of calling preDestroy, dispose, and release on the InjectionTarget and CreationalContext.

3.6.2 A lifecycle callback method must be either: annotated with annotations designating the callback events for which it is invoked, or mapped to a callback event type using the XML descriptor.

3.6.2 Callback methods must not be static or final.

3.6.2 A runtime exception thrown by a callback method that executes within a transaction causes that transaction to be marked for rollback if the persistence context is joined to the transaction.

3.6.2 A class is permitted to override an inherited callback method of the same callback type, and in this case, the overridden method is not invoked.

3.6.2 The XML descriptor may be used to override the lifecycle callback method invocation order specified in annotations.

3.6.5 Lifecycle callback methods must not use generics.

3.6.5 Lifecycle callback methods must not be native queries.

3.6.5 If a callback method throws a runtime exception, the transaction must be marked for rollback if the persistence context is joined to the transaction.

3.6.4 Callback methods are invoked by the persistence provider runtime in the order specified.

3.5.4.2 The jakarta.persistence.lock.scope property must be observed when specified with methods of the EntityManager interface that allow lock modes to be specified, the Query and TypedQuery setLockMode methods, and the NamedQuery annotation.

3.5.4.2 The jakarta.persistence.lock.timeout hint may be passed as a property to the Persistence.createEntityManagerFactory method and used in the properties element of the persistence.xml file.

3.5.4.2 When annotations are used, one or more entity listener classes are denoted using the EntityListeners annotation on the entity class or mapped superclass.

3.5.4.2 A single class must not have more than one lifecycle callback method for the same lifecycle event.

3.6.1 The entity listener class must have a public no-arg constructor.
