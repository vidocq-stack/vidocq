# 3. Entity Operations (part 2/4)

If a versioned object is otherwise updated or
removed, then the implementation must ensure that the requirements of
LockModeType.OPTIMISTIC_FORCE_INCREMENT are met, even if no explicit
call to EntityManager.lock was made.

For portability, an application should not
depend on vendor-specific hints or configuration to ensure repeatable
read for objects that are not updated or removed via any mechanism other
than the use of version attributes and the EntityManager lock method.
However, it should be noted that if an implementation has acquired
up-front pessimistic locks on some database rows, then it is free to
ignore lock(entity, LockModeType.OPTIMISTIC) calls on the entity
objects representing those rows.

3.5.4.2. PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT

The lock modes PESSIMISTIC_READ,
PESSIMISTIC_WRITE, and PESSIMISTIC_FORCE_INCREMENT are used to
immediately obtain long-term database locks.[46]

The semantics of requesting locks of type
LockModeType.PESSIMISTIC_READ, LockModeType.PESSIMISTIC_WRITE, and
LockModeType.PESSIMISTIC_FORCE_INCREMENT are the following.

If transaction T1 calls lock(entity, LockModeType.PESSIMISTIC_READ) or
lock(entity, LockModeType.PESSIMISTIC_WRITE) on an object, the entity
manager must ensure that neither of the following phenomena can occur:

P1 (Dirty read): Transaction T1 modifies a
row. Another transaction T2 then reads that row and obtains the modified
value, before T1 has committed or rolled back.

P2 (Non-repeatable read): Transaction T1
reads a row. Another transaction T2 then modifies or deletes that row,
before T1 has committed or rolled back.

Any such lock must be obtained immediately
and retained until transaction T1 completes (commits or rolls back).

Avoidance of phenomena P1 and P2 is generally
achieved by the entity manager acquiring a long-term lock on the
underlying database row(s). Any implementation that supports pessimistic
repeatable reads as described above is permissible.

A lock with LockModeType.PESSIMISTIC_WRITE
can be obtained on an entity instance to force serialization among
transactions attempting to update the entity data. A lock with
LockModeType.PESSIMISTIC_READ can be used to query data using
repeatable-read semantics without the need to reread the data at the end
of the transaction to obtain a lock, and without blocking other
transactions reading the data. A lock with
LockModeType.PESSIMISTIC_WRITE can be used when querying data and
there is a high likelihood of deadlock or update failure among
concurrent updating transactions.

The persistence implementation must support
calling lock(entity, LockModeType.PESSIMISTIC_READ) and lock(entity,
LockModeType.PESSIMISTIC_WRITE) on a non-versioned entity as well as on
a versioned entity.

It is permissible for an implementation to
use LockModeType.PESSIMISTIC_WRITE where
LockModeType.PESSIMISTIC_READ was requested, but not vice versa.

When the lock cannot be obtained, and the
database locking failure results in transaction-level rollback, the
provider must throw the PessimisticLockException and ensure that the
JTA transaction or EntityTransaction has been marked for rollback.

When the lock cannot be obtained, and the
database locking failure results in only statement-level rollback, the
provider must throw the LockTimeoutException (and must not mark the
transaction for rollback).

When an application locks an entity with
LockModeType.PESSIMISTIC_READ and later updates that entity, the lock
must be converted to an exclusive lock when the entity is flushed to the
database.[47] If the lock conversion fails, and the
database locking failure results in transaction-level rollback, the
provider must throw the PessimisticLockException and ensure that the
JTA transaction or EntityTransaction has been marked for rollback. When
the lock conversion fails, and the database locking failure results in
only statement-level rollback, the provider must throw the
LockTimeoutException (and must not mark the transaction for
rollback).

When lock(entity, LockModeType.PESSIMISTIC_READ),
lock(entity, LockModeType.PESSIMISTIC_WRITE), or
lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
is invoked on a versioned
entity that is already in the persistence context, the provider must
also perform optimistic version checks when obtaining the lock. An
OptimisticLockException must be thrown if the version checks fail.
Depending on the implementation strategy used by the provider, it is
possible that this exception may not be thrown until flush is called or
commit time, whichever occurs first.

If transaction T1 calls
lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) on a versioned
object, the entity manager must avoid the phenomenon P1 and P2 (as with
LockModeType.PESSIMISTIC_READ and LockModeType.PESSIMISTIC_WRITE)
and must also force an update (increment) to the entity’s version
column.

The persistence implementation is not required to support calling
lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) on a non-versioned
object. When it cannot support such a lock call, it must throw the
PersistenceException. When supported, whether for versioned or
non-versioned objects, LockModeType.PESSIMISTIC_FORCE_INCREMENT must
always prevent the phenomena P1 and P2. For non-versioned objects,
whether or not LockModeType.PESSIMISTIC_FORCE_INCREMENT has any
additional behavior is vendor-specific. Applications that call
lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT) on
non-versioned objects will not be portable.

For versioned objects, it is permissible for
an implementation to use LockModeType.PESSIMISTIC_FORCE_INCREMENT
where LockModeType.PESSIMISTIC_READ or
LockModeType.PESSIMISTIC_WRITE was requested, but not vice versa.

If a versioned object locked with
LockModeType.PESSIMISTIC_READ or LockModeType.PESSIMISTIC_WRITE is
updated, then the implementation must ensure that the requirements of
LockModeType.PESSIMISTIC_FORCE_INCREMENT are met.

3.5.4.3. Lock Mode Properties and Uses

The following property is defined by this
specification for use in pessimistic locking, as described in Section 3.5.3:

jakarta.persistence.lock.scope

This property may be used with the methods of
the EntityManager interface that allow lock modes to be specified, the
Query and TypedQuery setLockMode methods, and the NamedQuery
annotation. When specified, this property must be observed. The provider
is permitted to lock more (but not fewer) rows than requested.

The following hint is defined by this
specification for use in pessimistic locking.

jakarta.persistence.lock.timeout // time in milliseconds

This hint may be used with the methods of the
EntityManager interface that allow lock modes to be specified, the
Query.setLockMode method and the NamedQuery annotation. It may also
be passed as a property to the Persistence.createEntityManagerFactory
method and used in the properties element of the persistence.xml
file. See Section 3.2, Section 3.11.3, Section 8.2.1.11, Section 9.7,
and Section 10.4.1. When used in
the createEntityManagerFactory method, the persistence.xml file, and
the NamedQuery annotation, the timeout hint serves as a default value
which can be selectively overridden by use in the methods of the
EntityManager, Query, and TypedQuery interfaces as specified
above. When this hint is not specified, database timeout values are
assumed to apply.

A timeout value of 0 is used to specify “no wait” locking.

Portable applications should not rely on this
hint. Depending on the database in use and the locking mechanisms used
by the persistence provider, the hint may or may not be observed.

Vendors are permitted to support the use of
additional, vendor-specific locking hints. Vendor-specific hints must
not use the jakarta.persistence namespace. Vendor-specific hints must be
ignored if they are not understood.

If the same property or hint is specified
more than once, the following order of overriding applies, in order of
decreasing precedence:

argument to method of EntityManager, Query, or TypedQuery interface

specification to NamedQuery (annotation or XML)

argument to createEntityManagerFactory method

specification in persistence.xml

3.5.5. OptimisticLockException

Provider implementations may defer writing to
the database until the end of the transaction, when consistent with the
lock mode and flush mode settings in effect. In this case, an optimistic
lock check may not occur until commit time, and the
OptimisticLockException may be thrown in the “before completion” phase
of the commit. If the OptimisticLockException must be caught or
handled by the application, the flush method should be used by the
application to force the database writes to occur. This will allow the
application to catch and handle optimistic lock exceptions.

The OptimisticLockException provides an API
to return the object that caused the exception to be thrown. The object
reference is not guaranteed to be present every time the exception is
thrown but should be provided whenever the persistence provider can
supply it. Applications cannot rely upon this object being available.

In some cases an OptimisticLockException
will be thrown and wrapped by another exception, such as a
RemoteException, when VM boundaries are crossed. Entities that may be
referenced in wrapped exceptions should implement Serializable so that
marshalling will not fail.

An OptimisticLockException always causes
the transaction to be marked for rollback.

Refreshing objects or reloading objects in a
new transaction context and then retrying the transaction is a potential
response to an OptimisticLockException.

3.6. Entity Listeners and Callback Methods

A method may be designated as a lifecycle
callback method to receive notification of entity lifecycle events. A
lifecycle callback method can be defined on an entity class, a mapped
superclass, or an entity listener class associated with an entity or
mapped superclass. An entity listener class is a class whose methods are
invoked in response to lifecycle events on an entity. Any number of
entity listener classes can be defined for an entity class or mapped
superclass.

Default entity listeners—entity listener
classes whose callback methods apply to all entities in the persistence
unit—can be specified by means of the XML descriptor.

Lifecycle callback methods and entity
listener classes are defined by means of metadata annotations or the XML
descriptor. When annotations are used, one or more entity listener
classes are denoted using the EntityListeners annotation on the entity
class or mapped superclass. If multiple entity listeners are defined,
the order in which they are invoked is determined by the order in which
they are specified in the EntityListeners annotation. The XML
descriptor may be used as an alternative to specify the invocation order
of entity listeners or to override the order specified in metadata
annotations.

Any subset or combination of annotations may
be specified on an entity class, mapped superclass, or listener class. A
single class must not have more than one lifecycle callback method for
the same lifecycle event. The same method may be used for multiple
callback events.

Multiple entity classes and mapped
superclasses in an inheritance hierarchy may define listener classes
and/or lifecycle callback methods directly on the class. Section 3.6.4
describes the rules that apply to method invocation order in this case.

3.6.1. Entity Listeners

The entity listener class must have a public no-arg constructor.

Entity listener classes in Jakarta EE
environments support dependency injection through the Contexts and
Dependency Injection API (CDI) [7] when CDI is
enabled[48]. An entity listener class that makes use
of CDI injection may also define lifecycle callback methods annotated
with the PostConstruct and PreDestroy annotations. These methods
will be invoked after injection has taken place and before the entity
listener instance is destroyed respectively.

The persistence provider is responsible for
using the CDI SPI to create instances of the entity listener class; to
perform injection upon such instances; to invoke their PostConstruct
and PreDestroy methods, if any; and to dispose of the entity listener
instances.

The persistence provider is only required to
support CDI injection into entity listeners in Jakarta EE container
environments[49]. If the CDI is not enabled, the
persistence provider must not invoke entity listeners that depend upon
CDI injection.

An entity listener is a noncontextual object.
In supporting injection into entity listeners, the persistence provider
must behave as if it carries out the following steps involving the use
of the CDI SPI. (See [7]).

Obtain a BeanManager instance. (See Section 9.1)

Create an AnnotatedType instance for the entity listener class.

Create an InjectionTarget instance for the annotated type.

Create a CreationalContext.

Instantiate the listener by calling the InjectionTarget produce method.

Inject the listener instance by calling the
InjectionTarget inject method on the instance.

Invoke the PostConstruct callback, if any,
by calling the InjectionTarget postConstruct method on the instance.

When the listener instance is to be
destroyed, the persistence provider must behave as if it carries out the
following steps.

Call the InjectionTarget preDestroy method on the instance.

Call the InjectionTarget dispose method on the instance

Call the CreationalContext release method.

Persistence providers may optimize the steps
above, e.g. by avoiding calls to the actual CDI SPI and relying on
container-specific interfaces instead, as long as the outcome is the
same.

Entity listeners that do not make use of CDI
injection are stateless. The lifecycle of such entity listeners is
unspecified.

When invoked from within a Jakarta EE
environment, the callback listeners for an entity share the enterprise
naming context of the invoking component, and the entity callback
methods are invoked in the transaction and security contexts of the
calling component at the time at which the callback method is invoked.
[50]

3.6.2. Lifecycle Callback Methods

Entity lifecycle callback methods can be defined on an entity listener
class and/or directly on an entity class or mapped superclass.

A lifecycle callback method must be either:

annotated with annotations designating the callback events for which
it is invoked, or

mapped to a callback event type using the XML descriptor.

The same annotations (and XML elements) are used to declare:

callback methods of an entity class or mapped superclass, and

callback methods of an entity listener class.

The signatures of the callback methods differ between these two cases:

a callback method defined by an entity class or mapped superclass has
the signature:

void <METHOD>()

a callback method defined by an entity listener class has the signature:

void <METHOD>(S)

where S is any supertype of the entity class or mapped superclass to
which the entity listener is applied. At runtime, the argument to the
entity listener callback method is the entity instance for which the
callback method is being invoked.

Callback methods can have public, private, protected, or package level
access, but must not be static or final.

The following annotations designate lifecycle event callback methods of
the corresponding types.

PrePersist

PostPersist

PreRemove

PostRemove

PreUpdate

PostUpdate

PostLoad

The following rules apply to lifecycle callback methods:

Lifecycle callback methods may throw unchecked/runtime exceptions.
A runtime exception thrown by a callback method that executes within
a transaction causes that transaction to be marked for rollback if
the persistence context is joined to the transaction.

Lifecycle callbacks can invoke JNDI, JDBC, JMS, and enterprise beans.

A lifecycle callback method may modify the non-relationship state of
the entity on which it is invoked.

In general, the lifecycle method of a portable application should not
invoke EntityManager or query operations, access other entity
instances, or modify relationships within the same persistence
context[51].

3.6.3. Semantics of the Life Cycle Callback Methods for Entities

The PrePersist and PreRemove callback
methods are invoked for a given entity before the respective
EntityManager persist and remove operations for that entity are
executed. For entities to which the merge operation has been applied and
causes the creation of newly managed instances, the PrePersist
callback methods will be invoked for the managed instance after the
entity state has been copied to it. These PrePersist and PreRemove
callbacks will also be invoked on all entities to which these operations
are cascaded. The PrePersist and PreRemove methods will always be
invoked as part of the synchronous persist, merge, and remove operations.
Primary key values generated using the SEQUENCE, TABLE, or UUID
strategy are available in the PrePersist method. Primary key values
generated using the IDENTITY strategy are not available in the
PrePersist method.

The PostPersist and PostRemove callback
methods are invoked for an entity after the entity has been made
persistent or removed. These callbacks will also be invoked on all
entities to which these operations are cascaded. The PostPersist and
PostRemove methods will be invoked after the database insert and
delete operations respectively. These database operations may occur
directly after the persist, merge, or remove operations have been
invoked or they may occur directly after a flush operation has occurred
(which may be at the end of the transaction). Generated primary key
values are always available in the PostPersist method.

The PreUpdate and PostUpdate callbacks
occur before and after the database update operations to entity data
respectively. These database operations may occur at the time the entity
state is updated or they may occur at the time state is flushed to the
database (which may be at the end of the transaction).

Note that it is implementation-dependent as
to whether PreUpdate and PostUpdate callbacks occur when an entity
is persisted and subsequently modified in a single transaction or when
an entity is modified and subsequently removed within a single
transaction. Portable applications should not rely on such behavior.

The PostLoad method for an entity is
invoked after the entity has been loaded into the current persistence
context from the database or after the refresh operation has been
applied to it. The PostLoad method is invoked before a query result is
returned or accessed or before an association is traversed.

It is implementation-dependent as to whether
callback methods are invoked before or after the cascading of the
lifecycle events to related entities. Applications should not depend on
this ordering.

For example:

@Entity
@EntityListeners(com.acme.AlertMonitor.class)
public class Account {
 Long accountId;
 Integer balance;
 boolean preferred;

 @Id
 public Long getAccountId() { ... }

 // ...

 public Integer getBalance() { ... }

 // ...

 @Transient // because status depends upon non-persistent context
 public boolean isPreferred() { ... }

 // ...

 public void deposit(Integer amount) { ... }

 public Integer withdraw(Integer amount) throws NSFException { ... }

 @PrePersist
 protected void validateCreate() {
 if (getBalance() < MIN_REQUIRED_BALANCE)
 throw new AccountException("Insufficient balance to open an account");
 }

 @PostLoad
 protected void adjustPreferredStatus() {
 preferred = (getBalance() >= AccountManager.getPreferredStatusLevel());
 }
}

public class AlertMonitor {
 @PostPersist
 public void newAccountAlert(Account acct) {
 Alerts.sendMarketingInfo(acct.getAccountId(), acct.getBalance());
 }
}

3.6.4. Multiple Lifecycle Callback Methods for an Entity Lifecycle Event

If multiple callback methods are defined for
an entity lifecycle event, the ordering of the invocation of these
methods is as follows.

Default listeners, if any, are invoked first,
in the order specified in the XML descriptor. Default listeners apply to
all entities in the persistence unit, unless explicitly excluded by
means of the ExcludeDefaultListeners annotation or
exclude-default-listeners XML element.

The lifecycle callback methods defined on the
entity listener classes for an entity class or mapped superclass are
invoked in the same order as the specification of the entity listener
classes in the EntityListeners annotation.

If multiple classes in an inheritance
hierarchy—entity classes and/or mapped superclasses—define entity
listeners, the listeners defined for a superclass are invoked before the
listeners defined for its subclasses in this order. The
ExcludeSuperclassListeners annotation or
exclude-superclass-listeners XML element may be applied to an entity
class or mapped superclass to exclude the invocation of the listeners
defined by the entity listener classes for the superclasses of the
entity or mapped superclass. The excluded listeners are excluded from
the class to which the ExcludeSuperclassListeners annotation or
element has been specified and its subclasses[52].
The ExcludeSuperclassListeners annotation (or
exclude-superclass-listeners XML element) does not cause default
entity listeners to be excluded from invocation.

If a lifecycle callback method for the
same lifecycle event is also specified on the entity class and/or one or
more of its entity or mapped superclasses, the callback methods on the
entity class and/or superclasses are invoked after the other lifecycle
callback methods, most general superclass first. A class is permitted to
override an inherited callback method of the same callback type, and in
this case, the overridden method is not invoked[53].

Callback methods are invoked by the
persistence provider runtime in the order specified. If the callback
method execution terminates normally, the persistence provider runtime
then invokes the next callback method, if any.

The XML descriptor may be used to override
the lifecycle callback method invocation order specified in annotations.

For example:

There are several entity classes and listeners for animals:

@Entity
public class Animal {

 // ...

 @PostPersist
 protected void postPersistAnimal() {
 // ...
 }
}

@Entity
@EntityListeners(PetListener.class)
public class Pet extends Animal {
 // ...
}

@Entity
@EntityListeners({CatListener.class, CatListener2.class})
public class Cat extends Pet {
 // ...
}

public class PetListener {
 @PostPersist
 protected void postPersistPetListenerMethod(Object pet) {
 // ...
 }
}

public class CatListener {
 @PostPersist
 protected void postPersistCatListenerMethod(Object cat) {
 // ...
 }
}

public class CatListener2 {
 @PostPersist
 protected void postPersistCatListener2Method(Object cat) {
 // ...
 }
}

If a PostPersist event occurs on an
instance of Cat, the following methods are called in order:

postPersistPetListenerMethod

postPersistCatListenerMethod

postPersistCatListener2Method

postPersistAnimal

Assume that SiameseCat is defined as a
subclass of Cat:

@EntityListeners(SiameseCatListener.class)
@Entity
public class SiameseCat extends Cat {
 // ...

 @PostPersist
 protected void postPersistSiameseCat() {
 // ...
 }
}

public class SiameseCatListener {
 @PostPersist
 protected void postPersistSiameseCatListenerMethod(Object cat) {
 // ...
 }
}

If a PostPersist event occurs on an
instance of SiameseCat, the following methods are called in order:

postPersistPetListenerMethod

postPersistCatListenerMethod

postPersistCatListener2Method

postPersistSiameseCatListenerMethod

postPersistAnimal

postPersistSiameseCat

Assume the definition of SiameseCat were instead:

@EntityListeners(SiameseCatListener.class)
@Entity
public class SiameseCat extends Cat {
 // ...

 @PostPersist
 protected void postPersistAnimal() {
 // ...
 }
}

In this case, the following methods would be
called in order, where postPersistAnimal is the PostPersist method
defined in the SiameseCat class:

postPersistPetListenerMethod

postPersistCatListenerMethod

postPersistCatListener2Method

postPersistSiameseCatListenerMethod

postPersistAnimal

3.6.5. Exceptions

Lifecycle callback methods may throw runtime
exceptions. A runtime exception thrown by a callback method that
executes within a transaction causes that transaction to be marked for
rollback if the persistence context is joined to the transaction. No
further lifecycle callback methods will be invoked after a runtime
exception is thrown.

3.6.6. Specification of Callback Listener Classes and Lifecycle Methods in the XML Descriptor

The XML descriptor can be used as an
alternative to metadata annotations to specify entity listener classes
and their binding to entities or to override the invocation order of
lifecycle callback methods as specified in annotations.

3.6.6.1. Specification of Callback Listeners

The entity-listener XML descriptor element
is used to specify the lifecycle listener methods of an entity listener
class. The lifecycle listener methods are specified by using the
pre-persist, post-persist, pre-remove, post-remove,
pre-update, post-update, and/or post-load elements.

An entity listener class can define multiple
callback methods. However, at most one method of an entity listener
class can be designated as a pre-persist method, post-persist method,
pre-remove method, post-remove method, pre-update method, post-update
method, and/or post-load method, regardless of whether the XML
descriptor is used to define entity listeners or whether some
combination of annotations and XML descriptor elements is used.

3.6.6.2. Specification of the Binding of Entity Listener Classes to Entities

The entity-listeners subelement of the
persistence-unit-defaults element is used to specify the default
entity listeners for the persistence unit.

The entity-listeners subelement of the
entity or mapped-superclass element is used to specify the entity
listener classes for the respective entity or mapped superclass and its
subclasses.

The binding of entity listeners to entity
classes is additive. The entity listener classes bound to the
superclasses of an entity or mapped superclass are applied to it as
well.

The exclude-superclass-listeners element
specifies that the listener methods for superclasses are not to be
invoked for an entity class (or mapped superclass) and its subclasses.

The exclude-default-listeners element
specifies that default entity listeners are not to be invoked for an
entity class (or mapped superclass) and its subclasses.

Explicitly listing an excluded default or
superclass listener for a given entity class or mapped superclass causes
it to be applied to that entity or mapped superclass and its subclasses.

In the case of multiple callback methods for
a single lifecycle event, the invocation order rules described in Section 3.6.4 apply.

3.7. Bean Validation

This specification defines support for use of
Bean Validation [5] within Jakarta Persistence
applications.

Managed classes (entities, mapped
superclasses, and embeddable classes) may be configured to include Bean
Validation constraints.

Automatic validation using these constraints
is achieved by specifying that Jakarta Persistence delegate validation to
the Bean Validation implementation upon the pre-persist, pre-update, and
pre-remove entity lifecycle events described in Section 3.6.3.

Validation can also be achieved by the
application calling the validate method of a Validator instance upon
an instance of a managed class, as described in the Bean Validation
specification [5].

3.7.1. Automatic Validation Upon Lifecycle Events

This specification supports the use of bean
validation for the automatic validation of entities upon the
pre-persist, pre-update, and pre-remove lifecycle validation events.
These lifecycle validation events occur immediately after the point at
which all the PrePersist, PreUpdate, and PreRemove lifecycle
callback method invocations respectively have been completed, or
immediately after the point at which such lifecycle callback methods
would have been completed (in the event that such callback methods are
not present).

In the case where an entity is persisted and
subsequently modified in a single transaction or when an entity is
modified and subsequently removed in a single transaction, it is
implementation dependent as to whether the pre-update validation event
occurs. Portable applications should not rely on this behavior.

3.7.1.1. Enabling Automatic Validation

The validation-mode element of the
persistence.xml file determines whether the automatic lifecycle event
validation is in effect. The values of the validation-mode element are
AUTO, CALLBACK, NONE. The default validation mode is AUTO.

If the application creates the entity manager
factory using the Persistence.createEntityManagerFactory method, the
validation mode can be specified using the
jakarta.persistence.validation.mode map key, which will override the
value specified (or defaulted) in the persistence.xml file. The map
values for this key are "auto", "callback", "none".

If the auto validation mode is specified by
the validation-mode element or the jakarta.persistence.validation.mode
property, or if neither the validation-mode element nor the
jakarta.persistence.validation.mode property is specified, and a Bean
Validation provider is present in the environment, the persistence
provider must perform the automatic validation of entities as described
in Section 3.7.1.2. If no Bean Validation provider is
present in the environment, no lifecycle event validation takes place.

If the callback validation mode is specified
by the validation-mode element or the
jakarta.persistence.validation.mode property, the persistence provider
must perform the lifecycle event validation as described in Section 3.7.1.2.
It is an error if there is no Bean Validation
provider present in the environment, and the provider must throw the
PersistenceException if the jakarta.persistence.validation.mode
property value "callback" has been passed to the
Persistence.createEntityManagerFactory method.

If the none validation mode is specified by
the validation-mode element or the jakarta.persistence.validation.mode
property, the persistence provider must not perform lifecycle event
validation.

3.7.1.2. Requirements for Automatic Validation upon Lifecycle Events

For each event type, a list of groups is
targeted for validation. By default, the default Bean Validation group
(the group Default) will be validated upon the pre-persist and
pre-update lifecycle validation events, and no group will be validated
upon the pre-remove event.

This default validation behavior can be
overridden by specifying the target groups using the following
validation properties in the persistence.xml file or by passing these
properties in the configuration of the entity manager factory through
the createEntityManagerFactory method:

jakarta.persistence.validation.group.pre-persist

jakarta.persistence.validation.group.pre-update

jakarta.persistence.validation.group.pre-remove

The value of a validation property must be a
list of the targeted groups. A targeted group must be specified by its
fully qualified class name. Names must be separated by a comma.

When one of the above events occurs for an
entity, the persistence provider must validate that entity by obtaining
a Validator instance from the validator factory in use (see Section 3.7.2) and
invoking its validate method with the targeted groups. If the list of
targeted groups is empty, no validation is performed. If the set of
ConstraintViolation objects returned by the validate method is not
empty, the persistence provider must throw the
jakarta.validation.ConstraintViolationException containing a reference
to the returned set of ConstraintViolation objects, and must mark the
transaction for rollback if the persistence context is joined to the
transaction.

The validator instance that is used for
automatic validation upon lifecycle events must use a
TraversableResolver that has the following behavior:

Attributes that have not been loaded must not
be loaded.

Validation cascade (@Valid) must not
occur for entity associations (single- or multi-valued).

These requirements guarantee that no unloaded
attribute or association will be loaded by side effect and that no
entity will be validated more than once during a given flush cycle.

Embeddable attributes must be validated only
if the Valid annotation has been specified on them.

It is the responsibility of the persistence
provider to pass an instance implementing the
jakarta.validation.TraversableResolver interface to the Bean Validation
provider by calling
ValidatorFactory.usingContext().traversableResolver(tr).getValidator() where tr is the resolver having the behavior described above.

3.7.2. Providing the ValidatorFactory

In Jakarta EE environments, a ValidatorFactory
instance is made available by the Jakarta EE container. The container is
responsible for passing this validator factory to the persistence
provider via the map that is passed as an argument to the
createContainerEntityManagerFactory call. The map key used by the
container must be the standard property name
jakarta.persistence.validation.factory.

In Java SE environments, the application can
pass the ValidatorFactory instance via the map that is passed as an
argument to the Persistence.createEntityManagerFactory call. The map
key used must be the standard property name
jakarta.persistence.validation.factory. If no ValidatorFactory
instance is provided by the application, and if a Bean Validation
provider is present in the classpath, the persistence provider must
instantiate the ValidatorFactory using the default bootstrapping
approach defined by the Bean Validation specification
[5], namely Validation.buildDefaultValidatorFactory().

3.8. Entity Graphs

An entity graph is a template that captures
the path and boundaries for an operation or query. It is defined in the
form of metadata or an object created by the dynamic EntityGraph API.

Entity graphs are used in the specification
of “fetch plans” for query or find operations.

The EntityGraph, AttributeNode, and Subgraph interfaces found in
Appendix B are used to dynamically construct entity graphs.

The annotations NamedEntityGraph, NamedAttributeNode, and
NamedSubgraph described in Section 10.3 are used to statically define
entity graphs. The named-entity-graph XML element and its subelements
may be used to override these annotations or to define additional named
entity graphs.

The semantics of entity graphs with regard to
find and query operations are described in Section 3.8.1.

3.8.1. Use of Entity Graphs in find and query operations

An entity graph can be used with the find
method or as a query hint to override or augment FetchType semantics.

The standard properties
jakarta.persistence.fetchgraph and jakarta.persistence.loadgraph are
used to specify such graphs to queries and find operations.

The default fetch graph for an entity or
embeddable is defined to consist of the transitive closure of all of its
attributes that are specified as FetchType.EAGER (or defaulted as
such).

The persistence provider is permitted to
fetch additional entity state beyond that specified by a fetch graph or
load graph. It is required, however, that the persistence provider fetch
all state specified by the fetch or load graph.

3.8.1.1. Fetch Graph Semantics

When the jakarta.persistence.fetchgraph
property is used to specify an entity graph, attributes that are
specified by attribute nodes of the entity graph are treated as
FetchType.EAGER and attributes that are not specified are treated as
FetchType.LAZY.

The following rules apply, depending on
attribute type. The rules of this section are applied recursively.

A primary key or version attribute never
needs to be specified in an attribute node of a fetch graph. (This
applies to composite primary keys as well, including embedded id primary
keys.) When an entity is fetched, its primary key and version attributes
are always fetched. It is not incorrect, however, to specify primary key
attributes or version attributes.

Attributes other than primary key and version
attributes are assumed not to be fetched unless the attribute is
specified. The following rules apply to the specification of attributes.

If the attribute is an embedded attribute,
and the attribute is specified in an attribute node, but a subgraph is
not specified for the attribute, the default fetch graph for the
embeddable is fetched. If a subgraph is specified for the attribute, the
attributes of the embeddable are fetched according to their
specification in the corresponding subgraph.

If the attribute is an element collection of
basic type, and the attribute is specified in an attribute node, the
element collection together with its basic elements is fetched.

If the attribute is an element collection of
embeddables, and the attribute is specified in an attribute node, but a
subgraph is not specified for the attribute, the element collection
together with the default fetch graph of its embeddable elements is
fetched. If a subgraph is specified for the attribute, the attributes of
the embeddable elements are fetched according to the corresponding
subgraph specification.

If the attribute is a one-to-one or
many-to-one relationship, and the attribute is specified in an attribute
node, but a subgraph is not specified for the attribute, the default
fetch graph of the target entity is fetched. If a subgraph is specified
for the attribute, the attributes of the target entity are fetched
according to the corresponding subgraph specification.

If the attribute is a one-to-many or
many-to-many relationship, and the attribute is specified in an
attribute node, but a subgraph is not specified, the collection is
fetched and the default fetch graphs of the referenced entities are
fetched. If a subgraph is specified for the attribute, the entities in
the collection are fetched according to the corresponding subgraph
specification.

If the key of a map which has been specified
in an attribute node is a basic type, it is fetched. If the key of a map
which has been specified in an attribute node is an embedded type, the
default fetch graph is fetched for the embeddable. Otherwise, if the key
of the map is an entity, and a map key subgraph is not specified for the
attribute node, the map key is fetched according to its default fetch
graph. If a key subgraph is specified for the map key attribute, the map
key attribute is fetched according to the map key subgraph
specification.

Examples:

@NamedEntityGraph
@Entity
public class Phonenumber {
 @Id
 protected String number;

 protected PhoneTypeEnum type;

 // ...
}

In the above example, only the number attribute would be eagerly fetched.

@NamedEntityGraph(
 attributeNodes={@NamedAttributeNode("projects")}
)
@Entity
public class Employee {
 @Id
 @GeneratedValue
 protected long id;

 @Basic
 protected String name;

 @Basic
 protected String employeeNumber;

 @OneToMany()
 protected List<Dependents> dependents;

 @OneToMany()
 protected List<Project> projects;

 @OneToMany()
 protected List<PhoneNumber> phoneNumbers;

 // ...
}

@Entity
@Inheritance
public class Project {
 @Id
 @GeneratedValue
 protected long id;

 String name;

 @OneToOne(fetch=FetchType.EAGER)
 protected Requirements doc;

 // ...
}

@Entity
public class LargeProject extends Project {
 @OneToOne(fetch=FetchType.LAZY)
 protected Employee approver;

 // ...
}

@Entity
public class Requirements {
 @Id
 protected long id;

 @Lob
 protected String description;

 @OneToOne(fetch=FetchType.LAZY)
 protected Approval approval

 // ...
}

In the above example, the Employee entity’s
primary key will be fetched as well as the related Project instances,
whose default fetch graph (id, name, and doc attributes) will
be fetched. The related Requirements object will be fetched according
to its default fetch graph.

If the approver attribute of LargeProject
were FetchType.EAGER, and if any of the projects were instances of
LargeProject, their approver attributes would also be fetched.
Since the type of the approver attribute is Employee, the
approver’s default fetch graph (id, name, and employeeNumber
attributes) would also be fetched.

3.8.1.2. Load Graph Semantics

When the jakarta.persistence.loadgraph
property is used to specify an entity graph, attributes that are
specified by attribute nodes of the entity graph are treated as
FetchType.EAGER and attributes that are not specified are treated
according to their specified or default FetchType.

The following rules apply. The rules of this
section are applied recursively.

A primary key or version attribute never
needs to be specified in an attribute node of a load graph. (This
applies to composite primary keys as well, including embedded id primary
keys.) When an entity is fetched, its primary key and version attributes
are always fetched. It is not incorrect, however, to specify primary key
attributes or version attributes.

If the attribute is an embedded attribute,
and the attribute is specified in an attribute node, but a subgraph is
not specified for the attribute, the default fetch graph for the
embeddable is fetched. If a subgraph is specified for the attribute,
attributes that are specified by the subgraph are also fetched.

If the attribute is an element collection of
basic type, and the attribute is specified in an attribute node, the
element collection together with its basic elements is fetched.

If the attribute is an element collection of
embeddables, and the attribute is specified in an attribute node, the
element collection together with the default fetch graph of its
embeddable elements is fetched. If a subgraph is specified for the
attribute, attributes that are specified by the subgraph are also
fetched.

If the attribute is a one-to-one or
many-to-one relationship, and the attribute is specified in an attribute
node, the default fetch graph of the target entity is fetched. If a
subgraph is specified for the attribute, attributes that are specified
by the subgraph are also fetched.

If the attribute is a one-to-many or
many-to-many relationship, and the attribute is specified in an
attribute node, the collection is fetched and the default fetch graphs
of the referenced entities are fetched. If a subgraph is specified for
the attribute, attributes that are specified by the subgraph are also
fetched.

If the key of a map which has been specified
in an attribute node is a basic type, it is fetched. If the key of a map
which has been specified in an attribute node is an embedded type, the
default fetch graph is fetched for the embeddable. Otherwise, if the key
of the map is an entity, the map key is fetched according to its default
fetch graph. If a key subgraph is specified for the map key attribute,
additional attributes are fetched as specified in the key subgraph.

Examples:

@NamedEntityGraph
@Entity
public class Phonenumber {
 @Id
 protected String number;

 protected PhoneTypeEnum type;

 // ...
}

In the above example, the number and type attributes are fetched.

@NamedEntityGraph(
 attributeNodes={@NamedAttributeNode("projects")}
)
@Entity
public class Employee {
 @Id
 @GeneratedValue
 protected long id;

 @Basic
 protected String name;

 @Basic
 protected String employeeNumber;

 @OneToMany()
 protected List<Dependents> dependents;

 @OneToMany()
 protected List<Project> projects;

 @OneToMany()
 protected List<PhoneNumber> phoneNumbers;

 // ...
}

@Entity
@Inheritance
public class Project {
 @Id
 @GeneratedValue
 protected long id;

 String name;

 @OneToOne(fetch=FetchType.EAGER)
 protected Requirements doc;

 // ...
}

@Entity
public class LargeProject extends Project {
 @OneToOne(fetch=FetchType.LAZY)
 protected Employee approver;

 // ...
}

@Entity
public class Requirements {
 @Id
 protected long id;

 @Lob
 protected String description;

 @OneToOne(fetch=FetchType.LAZY)
 protected Approval approval

 // ...
}

In the above example, the default fetch graph
(id, name, employeeNumber attributes) of Employee is fetched.
The default fetch graphs of the related Project instances (id,
name, and doc attributes) and their Requirements instances (id
and description attributes) are also fetched.

3.9. Type Conversion of Basic Attributes

The attribute conversion facility allows the developer to define custom
attribute converters. A converter is a class whose methods convert
between:

the target type of the converter, an arbitrary Java type which may be
used as the type of a persistent field or property, and

a basic type (see Section 2.6) used as an intermediate step in mapping to
the database representation.
