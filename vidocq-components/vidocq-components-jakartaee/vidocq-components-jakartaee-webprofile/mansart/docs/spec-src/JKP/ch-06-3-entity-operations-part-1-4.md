# 3. Entity Operations (part 1/4)

This chapter describes:

the use of the EntityManager and Query APIs to retrieve instances of
entity classes representing persistent state held in the database, and
of EntityGraph to control the limits of the object graph returned by
such operations,

the use of the EntityManager API to manage the lifecycle of entity
instances associated with a persistence context, and to control the
synchronization of state held in the persistence context with the
database,

the use of the second-level cache, and

entity listeners and lifeycle callbacks, attribute converters,
and integration with Bean Validation.

3.1. Overview

Every instance of EntityManager has an associated persistence context.
A persistence context is a set of entity instances in which for any given
persistent entity identity there is a unique entity instance. Within the
persistence context, the entity instances and their lifecycle are managed.
The entity instance lifecycle is defined in Section 3.3. The relationship
between entity managers and persistence contexts is described in Section 3.4,
and again in further detail in Chapter 7.

The EntityManager interface defines the methods used to interact with
its persistence context. The EntityManager API is used to create and
remove persistent entity instances, to find persistent entities by primary
key, and to query over persistent entity types. Section 3.2 describes the
EntityManager interface. Section 3.5 describes mechanisms for concurrency
control and locking. Section 3.12 provides a summary of exceptions.

The EntityManager acts as a factory for instances of Query, which are
used to control query execution. Query, TypedQuery, StoredProcedureQuery,
and related interfaces are described in Section 3.11. The Jakarta Persistence
query language is defined in Chapter 4 and APIs for the construction of
Criteria queries in Chapter 6. Section 3.8 describes the use of entity graphs
to control and limit the data fetched during find and query operations.

Each EntityManager belongs to an EntityManagerFactory with an associated
persistence unit. A persistence unit defines a set of related entities which
map to a single database. Entities belonging to the same persistence unit may
participate in associations. An EntityManager may only manage instances of
entities belonging to its persistence unit. The definition of persistence
units is described in Chapter 8. An EntityManagerFactory might have an
associated second-level cache. Section 3.10 describes mechanisms for portable
configuration of the second-level cache.

Jakarta Persistence features several mechanisms allowing user-written code
to react to events occurring within the persistence context. Section 3.6
describes entity listeners and lifecycle callback methods for entities.
Section 3.7 describes support for automatic use of Bean Validation. Section 3.8
describes mechanisms for defining conversions between entity and database
representations for attributes of basic types.

3.2. EntityManager Interface

The EntityManager interface may be found in Section B.1.

The persist, merge, remove, and
refresh methods must be invoked within a transaction context when an
entity manager with a transaction-scoped persistence context is used. If
there is no transaction context, the
jakarta.persistence.TransactionRequiredException is thrown.

Methods that specify a lock mode other than
LockModeType.NONE must be invoked within a transaction. If there is no
transaction or if the entity manager has not been joined to the
transaction, the jakarta.persistence.TransactionRequiredException is
thrown.

The find method (provided it is invoked
without a lock or invoked with LockModeType.NONE) and the
getReference method are not required to be invoked within a
transaction. If an entity manager with transaction-scoped persistence
context is in use, the resulting entities will be detached; if an entity
manager with an extended persistence context is used, they will be
managed. See Section 3.4 for entity manager use outside a
transaction.

The Query, TypedQuery,
StoredProcedureQuery, CriteriaBuilder, Metamodel, and
EntityTransaction objects obtained from an entity manager are valid
while that entity manager is open.

If the argument to the createQuery method
is not a valid Jakarta Persistence query string or a valid CriteriaQuery
object, the IllegalArgumentException may be thrown or the query
execution will fail and a PersistenceException will be thrown. If the
result class specification of a Jakarta Persistence query language query is
incompatible with the result of the query, the
IllegalArgumentException may be thrown when the createQuery method
is invoked or the query execution will fail and a PersistenceException
will be thrown when the query is executed. If a native query is not a
valid query for the database in use or if the result set specification
is incompatible with the result of the query, the query execution will
fail and a PersistenceException will be thrown when the query is
executed. The PersistenceException should wrap the underlying database
exception when possible.

Runtime exceptions thrown by the methods of
the EntityManager interface other than the LockTimeoutException will
cause the current transaction to be marked for rollback if the
persistence context is joined to that transaction.

The methods close, isOpen,
joinTransaction, and getTransaction are used to manage
application-managed entity managers and their lifecycle. See Section 7.2.2.

The EntityManager interface and other
interfaces defined by this specification contain methods that take
properties and/or hints as arguments. This specification distinguishes
between properties and hints as follows:

A property defined by this specification must
be observed by the provider unless otherwise explicitly stated.

A hint specifies a preference on the part of
the application. While a hint defined by this specification should be
observed by the provider if possible, a hint may or may not always be
observed. A portable application must not depend on the observance of a
hint.

For example:

@Stateless
public class OrderEntryBean implements OrderEntry {
 @PersistenceContext
 EntityManager em;

 public void enterOrder(int custID, Order newOrder) {
 Customer cust = em.find(Customer.class, custID);
 cust.getOrders().add(newOrder);
 newOrder.setCustomer(cust);
 em.persist(newOrder);
 }
}

The semantics of

public <T> TypedQuery<T> createQuery(String qlString, Class<T> resultClass)

method may be extended in a future release of this specification to
support other result types. Applications that specify other result types
(e.g., Tuple.class) will not be portable.

The semantics

public <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass)

method may be extended in a future release of this specification to
support other result types. Applications that specify other result types
(e.g., Tuple.class) will not be portable.

3.3. Entity Instance’s Life Cycle

This section describes the EntityManager
operations for managing an entity instance’s lifecycle. An entity
instance can be characterized as being new, managed, detached, or
removed.

A new entity instance has no persistent
identity, and is not yet associated with a persistence context.

A managed entity instance is an instance with
a persistent identity that is currently associated with a persistence
context.

A detached entity instance is an instance
with a persistent identity that is not (or no longer) associated with a
persistence context.

A removed entity instance is an instance with
a persistent identity, associated with a persistence context, that will
be removed from the database upon transaction commit.

The following subsections describe the effect
of lifecycle operations upon entities. Use of the cascade annotation
element may be used to propagate the effect of an operation to
associated entities. The cascade functionality is most typically used in
parent-child relationships.

3.3.1. Entity Instance Creation

Entity instances are created by means of the
new operation. An entity instance, when first created by new is not
yet persistent. An instance becomes persistent by means of the
EntityManager API.

3.3.2. Persisting an Entity Instance

A new entity instance becomes both managed
and persistent by invoking the persist method on it or by cascading
the persist operation.

The semantics of the persist operation,
applied to an entity X are as follows:

If X is a new entity, it becomes managed. The
entity X will be entered into the database at or before transaction
commit or as a result of the flush operation.

If X is a preexisting managed entity, it is
ignored by the persist operation. However, the persist operation is
cascaded to entities referenced by X, if the relationships from X to
these other entities are annotated with the cascade=PERSIST or
cascade=ALL annotation element value or specified with the equivalent
XML descriptor element.

If X is a removed entity, it becomes managed.

If X is a detached object, the
EntityExistsException may be thrown when the persist operation is
invoked, or the EntityExistsException or another
PersistenceException may be thrown at flush or commit time.

For all entities Y referenced by a
relationship from X, if the relationship to Y has been annotated with
the cascade element value cascade=PERSIST or cascade=ALL, the
persist operation is applied to Y.

3.3.3. Removal

A managed entity instance becomes removed by
invoking the remove method on it or by cascading the remove operation.

The semantics of the remove operation,
applied to an entity X are as follows:

If X is a new entity, it is ignored by the
remove operation. However, the remove operation is cascaded to entities
referenced by X, if the relationship from X to these other entities is
annotated with the cascade=REMOVE or cascade=ALL annotation element
value.

If X is a managed entity, the remove
operation causes it to become removed. The remove operation is cascaded
to entities referenced by X, if the relationships from X to these other
entities is annotated with the cascade=REMOVE or cascade=ALL
annotation element value.

If X is a detached entity, an
IllegalArgumentException will be thrown by the remove operation (or
the transaction commit will fail).

If X is a removed entity, it is ignored by the remove operation.

A removed entity X will be removed from the
database at or before transaction commit or as a result of the flush
operation.

After an entity has been removed, its state
(except for generated state) will be that of the entity at the point at
which the remove operation was called.

3.3.4. Synchronization to the Database

In general, a persistence context will be
synchronized to the database as described below. However, a persistence
context of type SynchronizationType.UNSYNCHRONIZED or an
application-managed persistence context that has been created outside
the scope of the current transaction will only be synchronized to the
database if it has been joined to the current transaction by the
application’s use of the EntityManager joinTransaction method.

The state of persistent entities is
synchronized to the database at transaction commit. This synchronization
involves writing to the database any updates to persistent entities and
their relationships as specified above.

An update to the state of an entity includes
both the assignment of a new value to a persistent property or field of
the entity as well as the modification of a mutable value of a
persistent property or field[32].

Synchronization to the database does not
involve a refresh of any managed entities unless the refresh operation
is explicitly invoked on those entities or cascaded to them as a result
of the specification of the cascade=REFRESH or cascade=ALL
annotation element value.

Bidirectional relationships between
managed entities will be persisted based on references held by the
owning side of the relationship. It is the developer’s responsibility to
keep the in-memory references held on the owning side and those held on
the inverse side consistent with each other when they change. In the
case of unidirectional one-to-one and one-to-many relationships, it is
the developer’s responsibility to insure that the semantics of the
relationships are adhered to.[33]

It is particularly important to ensure that
changes to the inverse side of a relationship result in appropriate
updates on the owning side, so as to ensure the changes are not lost
when they are synchronized to the database.

The persistence provider runtime is permitted
to perform synchronization to the database at other times as well when a
transaction is active and the persistence context is joined to the
transaction. The flush method can be used by the application to force
synchronization. It applies to entities associated with the persistence
context. The setFlushMode methods of the EntityManager, Query,
TypedQuery, and StoredProcedureQuery interfaces can be used to
control synchronization semantics. The effect of FlushModeType.AUTO is
defined in Section 3.11.2. If FlushModeType.COMMIT is specified, flushing will occur at
transaction commit; the persistence provider is permitted, but not
required, to perform to flush at other times. If there is no transaction
active or if the persistence context has not been joined to the current
transaction, the persistence provider must not flush to the database.

The semantics of the flush operation, applied
to an entity X are as follows:

If X is a managed entity, it is synchronized
to the database.

For all entities Y referenced by a
relationship from X, if the relationship to Y has been annotated with
the cascade element value cascade=PERSIST or cascade=ALL, the
persist operation is applied to Y.

For any entity Y referenced by a relationship
from X, where the relationship to Y has not been annotated with the
cascade element value cascade=PERSIST or cascade=ALL:

If Y is new or removed, an
IllegalStateException will be thrown by the flush operation (and the
transaction marked for rollback) or the transaction commit will fail.

If Y is detached, the semantics depend upon
the ownership of the relationship. If X owns the relationship, any
changes to the relationship are synchronized with the database;
otherwise, if Y owns the relationships, the behavior is undefined.

If X is a removed entity, it is removed from
the database. No cascade options are relevant.

3.3.5. Refreshing an Entity Instance

The state of a managed entity instance is
refreshed from the database by invoking the refresh method on it or by
cascading the refresh operation.

The semantics of the refresh operation,
applied to an entity X are as follows:

If X is a managed entity, the state of X is
refreshed from the database, overwriting changes made to the entity, if
any. The refresh operation is cascaded to entities referenced by X if
the relationship from X to these other entities is annotated with the
cascade=REFRESH or cascade=ALL annotation element value.

If X is a new, detached, or removed entity,
the IllegalArgumentException is thrown.

3.3.6. Evicting an Entity Instance from the Persistence Context

An entity instance is removed from the
persistence context by invoking the detach method on it or cascading
the detach operation. Changes made to the entity, if any (including
removal of the entity), will not be synchronized to the database after
such eviction has taken place.

Applications must use the flush method
prior to the detach method to ensure portable semantics if changes
have been made to the entity (including removal of the entity). Because
the persistence provider may write to the database at times other than
the explicit invocation of the flush method, portable applications
must not assume that changes have not been written to the database if
the flush method has not been called prior to detach.

The semantics of the detach operation,
applied to an entity X are as follows:

If X is a managed entity, the detach
operation causes it to become detached. The detach operation is cascaded
to entities referenced by X if the relationships from X to these other
entities is annotated with the cascade=DETACH or cascade=ALL
annotation element value. Entities which previously referenced X will
continue to reference X.

If X is a new or detached entity, it is
ignored by the detach operation.

If X is a removed entity, the detach
operation causes it to become detached. The detach operation is cascaded
to entities referenced by X if the relationships from X to these other
entities is annotated with the cascade=DETACH or cascade=ALL
annotation element value. Entities which previously referenced X will
continue to reference X. Portable applications should not pass removed
entities that have been detached from the persistence context to further
EntityManager operations.

3.3.7. Detached Entities

A detached entity results from transaction
commit if a transaction-scoped persistence context is used (see Section 3.4);
from transaction rollback (see Section 3.4.3); from detaching
the entity from the persistence context; from clearing the persistence
context; from closing an entity manager; or from serializing an entity
or otherwise passing an entity by value—e.g., to a separate application
tier, through a remote interface, etc.

Detached entity instances continue to live
outside of the persistence context in which they were persisted or
retrieved. Their state is no longer guaranteed to be synchronized with
the database state.

The application may access the available
state of available detached entity instances after the persistence
context ends. The available state includes:

Any persistent field or property not marked fetch=LAZY

Any persistent field or property that was
accessed by the application or fetched by means of an entity graph

If the persistent field or property is an
association, the available state of an associated instance may only be
safely accessed if the associated instance is available. The available
instances include:

Any entity instance retrieved using find().

Any entity instances retrieved using a query or explicitly requested in a fetch join.

Any entity instance for which an instance
variable holding non-primary-key persistent state was accessed by the
application.

Any entity instance that can be reached from
another available instance by navigating associations marked fetch=EAGER.

3.3.7.1. Merging Detached Entity State

The merge operation allows for the
propagation of state from detached entities onto persistent entities
managed by the entity manager.

The semantics of the merge operation applied
to an entity X are as follows:

If X is a detached entity, the state of X is
copied onto a pre-existing managed entity instance X' of the same
identity or a new managed copy X' of X is created.

If X is a new entity instance, a new managed
entity instance X' is created and the state of X is copied into the
new managed entity instance X'.

If X is a removed entity instance, an
IllegalArgumentException will be thrown by the merge operation (or the
transaction commit will fail).

If X is a managed entity, it is ignored by
the merge operation, however, the merge operation is cascaded to
entities referenced by relationships from X if these relationships have
been annotated with the cascade element value cascade=MERGE or
cascade=ALL annotation.

For all entities Y referenced by
relationships from X having the cascade element value cascade=MERGE
or cascade=ALL, Y is merged recursively as Y'. For all such Y
referenced by X, X' is set to reference Y'. (Note that if X is managed
then X is the same object as X'.)

If X is an entity merged to X', with a
reference to another entity Y, where cascade=MERGE or cascade=ALL is
not specified, then navigation of the same association from X' yields a
reference to a managed object Y' with the same persistent identity as Y.

The persistence provider must not merge
fields marked LAZY that have not been fetched: it must ignore such
fields when merging.

Any Version columns used by the entity must
be checked by the persistence runtime implementation during the merge
operation and/or at flush or commit time. In the absence of Version
columns there is no additional version checking done by the persistence
provider runtime during the merge operation.

3.3.7.2. Detached Entities and Lazy Loading

Serializing entities and merging those
entities back into a persistence context may not be interoperable across
vendors when lazy properties or fields and/or relationships are used.

A vendor is required to support the
serialization and subsequent deserialization and merging of detached
entity instances (which may contain lazy properties or fields and/or
relationships that have not been fetched) back into a separate JVM
instance of that vendor’s runtime, where both runtime instances have
access to the entity classes and any required vendor persistence
implementation classes.

When interoperability across vendors is
required, the application must not use lazy loading.

3.3.8. Managed Instances

It is the responsibility of the application
to insure that an instance is managed in only a single persistence
context. The behavior is undefined if the same Java instance is made
managed in more than one persistence context.

The contains() method can be used to
determine whether an entity instance is managed in the current
persistence context.

The contains method returns true:

If the entity has been retrieved from the
database or has been returned by getReference, and has not been
removed or detached.

If the entity instance is new, and the
persist method has been called on the entity or the persist operation
has been cascaded to it.

The contains method returns false:

If the instance is detached.

If the remove method has been called on the
entity, or the remove operation has been cascaded to it.

If the instance is new, and the persist
method has not been called on the entity or the persist operation has
not been cascaded to it.

Note that the effect of the cascading of
persist, merge, remove, or detach is immediately visible to the
contains method, whereas the actual insertion, modification, or
deletion of the database representation for the entity may be deferred
until the end of the transaction.

3.3.9. Load State

An entity is considered to be loaded if all
attributes with FetchType.EAGER —whether explictly specified or by
default—(including relationship and other collection-valued attributes)
have been loaded from the database or assigned by the application.
Attributes with FetchType.LAZY may or may not have been loaded. The
available state of the entity instance and associated instances is as
described in Section 3.3.7.

An attribute that is an embeddable is
considered to be loaded if the embeddable attribute was loaded from the
database or assigned by the application, and, if the attribute
references an embeddable instance (i.e., is not null), the embeddable
instance state is known to be loaded (i.e., all attributes of the
embeddable with FetchType.EAGER have been loaded from the database or
assigned by the application).

A collection-valued attribute is considered
to be loaded if the collection was loaded from the database or the value
of the attribute was assigned by the application, and, if the attribute
references a collection instance (i.e., is not null), each element of
the collection (e.g. entity or embeddable) is considered to be loaded.

A single-valued relationship attribute is
considered to be loaded if the relationship attribute was loaded from
the database or assigned by the application, and, if the attribute
references an entity instance (i.e., is not null), the entity instance
state is known to be loaded.

A basic attribute is considered to be loaded
if its state has been loaded from the database or assigned by the
application.

The PersistenceUtil.isLoaded methods can be
used to determine the load state of an entity and its attributes
regardless of the persistence unit with which the entity is associated.
The PersistenceUtil.isLoaded methods return true if the above
conditions hold, and false otherwise. If the persistence unit is known,
the PersistenceUnitUtil.isLoaded methods can be used instead. See Section 7.11.

Persistence provider contracts for
determining the load state of an entity or entity attribute are
described in Section 9.9.1.

3.4. Persistence Context Lifetime and Synchronization Type

The lifetime of a container-managed
persistence context can either be scoped to a transaction
(transaction-scoped persistence context), or have a lifetime scope that
extends beyond that of a single transaction (extended persistence
context). The enum PersistenceContextType is used to define the
persistence context lifetime scope for container-managed entity
managers. The persistence context lifetime scope is defined when the
EntityManager instance is created (whether explicitly, or in conjunction
with injection or JNDI lookup). See Section 7.7.

/**
 * Specifies whether a transaction-scoped or extended persistence
 * context is to be used in {@link PersistenceContext}. If not
 * specified, a transaction-scoped persistence context is used.
 *
 * @since 1.0
 */
public enum PersistenceContextType {

 /** Transaction-scoped persistence context */
 TRANSACTION,

 /** Extended persistence context */
 EXTENDED
}

By default, the lifetime of the persistence
context of a container-managed entity manager corresponds to the scope
of a transaction (i.e., it is of type
PersistenceContextType.TRANSACTION).

When an extended persistence context is used,
the extended persistence context exists from the time the EntityManager
instance is created until it is closed. This persistence context might
span multiple transactions and non-transactional invocations of the
EntityManager.

An EntityManager with an extended persistence
context maintains its references to the entity objects after a
transaction has committed. Those objects remain managed by the
EntityManager, and they can be updated as managed objects between
transactions.[34] Navigation from a managed object in
an extended persistence context results in one or more other managed
objects regardless of whether a transaction is active.

When an EntityManager with an extended
persistence context is used, the persist, remove, merge, and refresh
operations can be called regardless of whether a transaction is active.
The effects of these operations will be committed to the database when
the extended persistence context is enlisted in a transaction and the
transaction commits.

The scope of the persistence context of an
application-managed entity manager is extended. It is the responsibility
of the application to manage the lifecycle of the persistence context.

Container-managed persistence contexts are
described further in Section 7.7. Persistence contexts managed by
the application are described further in Section 7.8.

3.4.1. Synchronization with the Current Transaction

By default, a container-managed persistence
context is of SynchronizationType.SYNCHRONIZED and is automatically
joined to the current transaction. A persistence context of
SynchronizationType.UNSYNCHRONIZED will not be enlisted in the current
transaction, unless the EntityManager joinTransaction method is
invoked.

By default, an application-managed
persistence context that is associated with a JTA entity manager and
that is created within the scope of an active transaction is
automatically joined to that transaction. An application-managed JTA
persistence context that is created outside the scope of a transaction
or an application-managed persistence context of type
SynchronizationType.UNSYNCHRONIZED will not be joined to that
transaction unless the EntityManager joinTransaction method is
invoked.

An application-managed persistence context
associated with a resource-local entity manager is always automatically
joined to any resource-local transaction that is begun for that entity
manager.

Persistence context synchronization type is
described further in Section 7.7.1.

3.4.2. Transaction Commit

The managed entities of a transaction-scoped
persistence context become detached when the transaction commits; the
managed entities of an extended persistence context remain managed.

3.4.3. Transaction Rollback

For both transaction-scoped
persistence contexts and for extended persistence contexts that are
joined to the current transaction, transaction rollback causes all
pre-existing managed instances and removed
instances[35] to become detached. The instances'
state will be the state of the instances at the point at which the
transaction was rolled back. Transaction rollback typically causes the
persistence context to be in an inconsistent state at the point of
rollback. In particular, the state of version attributes and generated
state (e.g., generated primary keys) may be inconsistent. Instances that
were formerly managed by the persistence context (including new
instances that were made persistent in that transaction) may therefore
not be reusable in the same manner as other detached objects—for
example, they may fail when passed to the merge
operation.[36]

Because a transaction-scoped
persistence context’s lifetime is scoped to a transaction regardless of
whether it is joined to that transaction, the container closes the
persistence context upon transaction rollback. However, an extended
persistence context that is not joined to a transaction is unaffected by
transaction rollback.

3.5. Locking and Concurrency

This specification assumes the use of
optimistic concurrency control. It assumes that the databases to which
persistence units are mapped will be accessed by the implementation
using read-committed isolation (or a vendor equivalent in which
long-term read locks are not held), and that writes to the database will
typically occur only when the flush method has been invoked—whether
explicitly by the application, or by the persistence provider runtime in
accordance with the flush mode setting.

If a transaction is active and the
persistence context is joined to the transaction, a compliant
implementation of this specification is permitted to write to the
database immediately (i.e., whenever a managed entity is updated,
created, and/or removed), however, the configuration of an
implementation to require such non-deferred database writes is outside
the scope of this specification.[37]

In addition, both pessimistic and optimistic
locking are supported for selected entities by means of specified lock
modes. Optimistic locking is described in Section 3.5.1 and Section 3.5.2; pessimistic locking
in Section 3.5.3. Section 3.5.4 describes the setting of
optimistic and pessimistic lock modes. The configuration of the setting
of optimistic lock modes is described in Section 3.5.4.1,
and the configuration of the setting of pessimistic lock modes is
described in Section 3.5.4.2.

3.5.1. Optimistic Locking

Optimistic locking is a system of concurrency control where each
revision of an item of data is assigned a version number or timestamp.
When the data is read and then updated within a given unit of work,
the version or timestamp is:

read from the database when the data itself is read, and

verified and then updated in the database when the data is updated.

Similarly, when the data is read and then deleted within a given unit
of work, the version or timestamp is:

read from the database when the data itself is read, and

verified when the data is deleted.

An optimistic lock failure occurs when verification fails, that is,
if the version or timestamp held in the database changes between
reading the data (step 1), and attempting to update or delete the data
(step 2).

Thus, the unit of work is prevented from updating the data and creating
a new revision, or from deleting the data, unless the revision it
previously obtained is still the current revision. Optimistic lock
verification ensures that an update of a given item is successful only
when no intervening transaction has already updated the item, preventing
the loss of updates made by such intervening transactions.

The persistence provider is required to perform optimistic locking
automatically for every entity with a version, as defined in Section 2.5.
A portable application which wishes to take advantage of automatic
optimistic locking must specify a version field or property for each
optimistically-locked entity using the @Version annotation defined in
Section 11.1.57 or equivalent XML element.

When an optimistic lock failure is detected, the persistence provider
must:

throw an OptimisticLockException and

mark the current transaction for rollback.

A persistence provider might offer alternative implementations of
optimistic locking, which do not depend on the entity having a version,
but such functionality is not portable between providers.[38]

Applications are strongly encouraged to enable optimistic locking for
every entity which may be concurrently accessed or which may be merged
from a detached state. Failure to make use of optimistic locking often
leads to inconsistent entity state, lost updates, and other anomalies.
If an entity does not have a version, the application itself must bear
the burden of maintaining data consistency during optimistic units of
work.

For the purposes of versioning and optimistic locking, the state of a
given entity is considered to include:

every persistent field or property which is not a relationship to
another entity, and

every relationship owned by the entity, as defined by Section 2.11.
[39]

Unowned relationships are not considered part of the state of the entity.

3.5.2. Entity Versions and Optimistic Locking

The entity version must be updated by the persistence provider each
time the state of an entity instance is written to the database.
[40]
Furthermore, if the current persistence context contains a revision
of the entity instance when the instance is written to the database,
the persistence provider must verify that the revision held in the
persistence context is identical to the revision held in the database
by comparing the versions held in memory and in the database.
[41] If the versions do not match, the persistence
provider must thow an OptimisticLockException.

The persistence provider must examine the version field or property of
a detached entity instance when it is merged, as defined in Section 3.3.7.1,
and throw an OptimisticLockException if the instance being merged
holds a stale revision of the state of the entity—​that is, if the
entity was updated since the entity instance became detached. The
timing of this version check is provider-dependent:

the version check might occur synchronously with the call to merge(),
or

a provider might choose to delay the version check until a flush
operation occurs, as defined in Section 3.3.4, or until the transaction
commits.

If an update or merge operation involves entities with versions, and
entities without versions, the persistence provider runtime is only
required to perform optimistic lock verification for those entities
which do have a version, and the consistency of the whole object graph
is not guaranteed. The absence a version for some entity involved in
the update or merge operation does not impede completion of the
operation.

3.5.3. Pessimistic Locking

While optimistic locking is typically
appropriate in dealing with moderate contention among concurrent
transactions, in some applications it may be useful to immediately
obtain long-term database locks for selected entities because of the
often late failure of optimistic transactions. Such immediately obtained
long-term database locks are referred to here as “pessimistic”
locks.[42]

Pessimistic locking guarantees that once a
transaction has obtained a pessimistic lock on an entity instance:

no other transaction (whether a transaction
of an application using the Jakarta Persistence API or any other
transaction using the underlying resource) may successfully modify or
delete that instance until the transaction holding the lock has ended.

if the pessimistic lock is an exclusive
lock[43],
that same transaction may modify or delete
that entity instance.

When an entity instance is locked using
pessimistic locking, the persistence provider must lock the database
row(s) that correspond to the non-collection-valued persistent state of
that instance. If a joined inheritance strategy is used, or if the
entity is otherwise mapped to a secondary table, this entails locking
the row(s) for the entity instance in the additional table(s). Entity
relationships for which the locked entity contains the foreign key will
also be locked, but not the state of the referenced entities (unless
those entities are explicitly locked). Element collections and
relationships for which the entity does not contain the foreign key
(such as relationships that are mapped to join tables or unidirectional
one-to-many relationships for which the target entity contains the
foreign key) will not be locked by default.

Element collections and relationships owned
by the entity that are contained in join tables will be locked if the
jakarta.persistence.lock.scope property is specified with a value of
PessimisticLockScope.EXTENDED. The state of entities referenced by
such relationships will not be locked (unless those entities are
explicitly locked). This property may be passed as an argument to the
methods of the EntityManager, Query, and TypedQuery interfaces
that allow lock modes to be specified or used with the NamedQuery
annotation.

Locking such a relationship or element
collection generally locks only the rows in the join table or collection
table for that relationship or collection. This means that phantoms will
be possible.

The values of the
jakarta.persistence.lock.scope property are defined by the
PessimisticLockScope enum.

/**
 *
 * Defines the values of the {@code jakarta.persistence.lock.scope}
 * property for pessimistic locking. This property may be passed as an
 * argument to the methods of the {@link EntityManager}, {@link Query},
 * and {@link TypedQuery} interfaces that allow lock modes to be specified
 * or used with the {@link NamedQuery} annotation.
 *
 * @since 2.0
 */
public enum PessimisticLockScope implements FindOption, RefreshOption, LockOption {

 /**
 * This value defines the default behavior for pessimistic locking.
 *
 * <p>The persistence provider must lock the database row(s) that
 * correspond to the non-collection-valued persistent state of
 * that instance. If a joined inheritance strategy is used, or if
 * the entity is otherwise mapped to a secondary table, this
 * entails locking the row(s) for the entity instance in the
 * additional table(s). Entity relationships for which the locked
 * entity contains the foreign key will also be locked, but not
 * the state of the referenced entities (unless those entities are
 * explicitly locked). Element collections and relationships for
 * which the entity does not contain the foreign key (such as
 * relationships that are mapped to join tables or unidirectional
 * one-to-many relationships for which the target entity contains
 * the foreign key) will not be locked by default.
 */
 NORMAL,

 /**
 * In addition to the locking behavior specified for {@link #NORMAL},
 * element collections and relationships owned by the entity that
 * are contained in join tables are locked if the property
 * {@code jakarta.persistence.lock.scope} is specified with a value
 * of {@code PessimisticLockScope#EXTENDED}. The state of entities
 * referenced by such relationships is not locked (unless those
 * entities are explicitly locked). Locking such a relationship or
 * element collection generally locks only the rows in the join table
 * or collection table for that relationship or collection. This means
 * that phantoms are possible.
 */
 EXTENDED
}

This specification does not define the
mechanisms a persistence provider uses to obtain database locks, and a
portable application should not rely on how pessimistic locking is
achieved on the database.[44] In particular, a
persistence provider or the underlying database management system may
lock more rows than the ones selected by the application.

Whenever a pessimistically locked entity
containing a version attribute is updated on the database, the
persistence provider must also update (increment) the entity’s version
column to enable correct interaction with applications using optimistic
locking. See Section 3.5.2 and Section 3.5.4.

Pessimistic locking may be applied to
entities that do not contain version attributes. However, in this case
correct interaction with applications using optimistic locking cannot be
ensured.

3.5.4. Lock Modes

Lock modes are intended to provide a facility
that enables the effect of “repeatable read” semantics for the items
read, whether “optimistically” (as described in Section 3.5.4.1)
or “pessimistically” (as described in Section 3.5.4.2).

A lock mode may be explicitly specified as an argument to the
lock() method of EntityManager or to any other method of
EntityManager, Query, and TypedQuery which accepts a lock
mode, or via the NamedQuery annotation.

Lock mode values are defined by the LockModeType enum which may
be found in Section B.4. Six distinct lock modes are defined.
[45]
The lock mode type values READ and WRITE are synonyms for
OPTIMISTIC and OPTIMISTIC_FORCE_INCREMENT respectively.
The latter are to be preferred for new applications.

3.5.4.1. OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT

The lock modes OPTIMISTIC and
OPTIMISTIC_FORCE_INCREMENT are used for optimistic locking. The lock
mode type values READ and WRITE are synonymous with OPTIMISTIC and
OPTIMISTIC_FORCE_INCREMENT respectively.

The semantics of requesting locks of type
LockModeType.OPTIMISTIC and LockModeType.OPTIMISTIC_FORCE_INCREMENT
are the following.

If transaction T1 calls lock(entity, LockModeType.OPTIMISTIC) on a
versioned object, the entity manager
must ensure that neither of the following phenomena can occur:

P1 (Dirty read): Transaction T1 modifies a
row. Another transaction T2 then reads that row and obtains the modified
value, before T1 has committed or rolled back. Transaction T2 eventually
commits successfully; it does not matter whether T1 commits or rolls
back and whether it does so before or after T2 commits.

P2 (Non-repeatable read): Transaction T1
reads a row. Another transaction T2 then modifies or deletes that row,
before T1 has committed. Both transactions eventually commit
successfully.

This will generally be achieved by the entity
manager acquiring a lock on the underlying database row. While with
optimistic concurrency concurrency, long-term database read locks are
typically not obtained immediately, a compliant implementation is
permitted to obtain an immediate lock (so long as it is retained until
commit completes). If the lock is deferred until commit time, it must be
retained until the commit completes. Any implementation that supports
repeatable reads in a way that prevents the above phenomena is
permissible.

The persistence implementation is not
required to support calling lock(entity, LockModeType.OPTIMISTIC) on
a non-versioned object. When it cannot support such a lock call, it must
throw the PersistenceException. When supported, whether for versioned
or non-versioned objects, LockModeType.OPTIMISTIC must always prevent
the phenomena P1 and P2. Applications that call
lock(entity, LockModeType.OPTIMISTIC) on non-versioned objects are not
portable.

If transaction T1 calls lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
on a versioned object, the entity manager must avoid the phenomena P1 and P2
(as with LockModeType.OPTIMISTIC) and must also force an update (increment) to
the entity’s version column. A forced version update may be performed
immediately, or may be deferred until a flush or commit. If an entity is
removed before a deferred version update was to have been applied, the
forced version update is omitted.

The persistence implementation is not required to support calling
lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT) on a non-versioned
object. When it cannot support such a lock call, it must throw the
PersistenceException. When supported, whether for versioned or
non-versioned objects, LockModeType.OPTIMISTIC_FORCE_INCREMENT must
always prevent the phenomena P1 and P2. For non-versioned objects,
whether or not LockModeType.OPTIMISTIC_FORCE_INCREMENT has any
additional behavior is vendor-specific. Applications that call
`lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT)_ on non-versioned
objects will not be portable.

For versioned objects, it is permissible for
an implementation to use LockModeType.OPTIMISTIC_FORCE_INCREMENT where
LockModeType.OPTIMISTIC was requested, but not vice versa.
