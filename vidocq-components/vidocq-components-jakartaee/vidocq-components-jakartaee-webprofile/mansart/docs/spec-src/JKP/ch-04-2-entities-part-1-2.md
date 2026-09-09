# 2. Entities (part 1/2)

An entity is a lightweight persistent domain object.[1] Entities support
inheritance, polymorphic associations, and polymorphic queries.

The primary programming artifact is the entity class. An entity class
may make use of auxiliary classes that serve as helper classes or that
are used to represent the state of the entity.

This chapter describes requirements on entity classes and instances.

2.1. The Entity Class

The entity class must be annotated with the Entity annotation or
declared as an entity in the XML descriptor.

The entity class must be a top-level class or a static inner class.
An enum, record, or interface may not be designated as an entity.

The entity class must have a public or protected constructor with no
parameters, which is called by the persistence provider runtime to
instantiate the entity.[2] The entity class may have additional constructors for use by
the application.

The entity class must be non-final. Every method and persistent
instance variable of the entity class must be non-final.

An entity might be an abstract class, or it might be a concrete class.
An entity may extend a non-entity class, or it may extend another entity
class. A non-entity class may extend an entity class.

The persistent state of an entity is represented by instance variables,
which may correspond to JavaBeans properties. An instance variable may
be directly accessed only within the methods of the entity, by the
entity instance itself. An instance variable of an entity must not be
directly accessed by a client of the entity. The state of the entity is
available to clients only through the methods of the entity—that is,
via accessor (getter/setter) methods, or via other business methods.

2.2. Persistent Fields and Properties

The persistent state of an entity is accessed by the persistence provider
runtime via either:

property access using JavaBeans-style property accessors, or

field access, that is, direct access to instance variables.

The instance variables of a class must have private, protected, or package
visibility, independent of whether field access or property access is used.
When property access is used, the property accessor methods must be public
or protected.

The type of a persistent field or property of an entity class may be:

any basic type listed below in Section 2.6, including any Java enum type,

an entity type or a collection of some entity type, as specified in Section 2.11,

an embeddable class, as defined in Section 2.7, or

a collection of a basic type or embeddable type, as specified in Section 2.8.

Object/relational mapping metadata may be specified to customize the
object/relational mapping and the loading and storing of the entity state
and relationships, as specified in Chapter 11.

The placement of object/relational mapping annotations depends on whether
property access or field access is used:

When field access is used, mapping annotations must be placed on instance
variables, and the persistence provider runtime accesses instance variables
directly. Every non-transient instance variable not annotated with the
Transient annotation is persistent.

When property-based access is used, mapping annotations must be placed on
getter methods[3], and the persistence provider runtime accesses persistent state
via the property accessor methods. Every property not annotated with the
Transient annotation is persistent.

Mapping annotations must not be applied to fields or properties marked
transient or Transient, since those fields and properties are not
persistent.

Whether property access, field access, or a mix of the two options is used
by the provider to access the state of a given entity class or entity hierarchy
is determined by the rules defined in Section 2.3.

Terminology Note: The persistent fields and properties of an entity class
are generically referred to in this document as “attributes” of the class.

Collection-valued persistent fields and properties must be defined in
terms of one of the following collection-valued interfaces, regardless
of whether the entity class otherwise adheres to the JavaBeans method
conventions noted below, and of whether field or property access is used:
java.util.Collection, java.util.Set, java.util.List [4],
java.util.Map.

Use of the generic variants of these collection types is strongly encouraged,
for example, Set<Order> is preferred to the raw type Set.

Terminology Note: The terms “collection” and “collection-valued” are used
in this specification to denote any of the above types, unless further
qualified. In cases where a java.util.Collection type (or one of its
subtypes) is to be distinguished, the type is identified as such. The
terms “map” and “map collection” are used to denote to a collection of
type java.util.Map.

A collection implementation type such as HashSet or ArrayList may be
used by the application to initialize a collection-valued field or property
before the entity is made persistent. Once the entity becomes managed
(or detached), subsequent access to the collection must be through the
interface type.

2.2.1. Persistent Attribute Type

The enumeration jakarta.persistence.metamodel.Attribute.PersistentAttributeType
defines a classification of persistent entity attributes: BASIC for
basic attributes, EMBEDDED for embedded attributes, ELEMENT_COLLECTION
for element collections, and MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY,
and MANY_TO_MANY for associations of the indicated multiplicity.
Each persistent attribute of an entity belongs to exactly one of the
listed types.

It is an error for an attribute of an entity to be annotated with
mapping annotations indicating conflicting persistent attribute types.
For example, an field may not be annotated @Basic @Embedded,
@ManyToOne @ElementCollection, or @OneToOne @ManyToMany. The
persistence provider must detect such contradictory combinations of
mapping annotations and report the error.[5]

2.2.2. Property Access

When property access is used, persistent properties of the entity class
must follow the method signature conventions for JavaBeans read/write
properties, as defined by the JavaBeans Introspector class. For every
persistent property property of type T of the entity, there must be
a getter method, getProperty, and setter method setProperty. For
boolean properties, isProperty may be used as an alternative name for
the getter method.[6]

For single-valued persistent properties, these method signatures are:

T getProperty()

void setProperty(T t)

For collection-valued persistent properties, the type T in the method
signatures above must be one of the collection interface types listed
above in Section 2.2.

In addition to returning and setting the persistent state of the entity
instance, a property accessor method may contain additional logic, for
example, logic to perform validation. The persistence provider runtime
triggers execution of this logic when property-based access is used.

Therefore, caution should be exercised in adding business logic to
accessor methods when property access is used. The order in which the
persistence provider runtime calls these methods when loading or storing
persistent state is not defined. Logic contained in such methods should
therefore not rely on any specific invocation order.

If property access is used and lazy fetching is specified, portable
applications should not directly access the entity state underlying the
property methods of managed instances until after it has been fetched by
the persistence provider.[7]

If a persistence context is joined to a transaction, runtime exceptions
thrown by property accessor methods cause the current transaction to be
marked for rollback; any exception thrown by such methods when called by
the persistence runtime to load or store persistent state causes the
persistence runtime to mark the current transaction for rollback and to
throw a PersistenceException wrapping the application exception.

An entity subclass may override a property accessor method inherited
from a superclass. However, portable applications must not override
the object/relational mapping metadata applied to the persistent fields
and properties of entity superclasses.

For example:

@Entity
public class Customer implements Serializable {
 private Long id;
 private String name;
 private Address address;
 private Collection<Order> orders = new HashSet();
 private Set<PhoneNumber> phones = new HashSet();

 // No-arg constructor
 public Customer() {}

 @Id // property access is used
 public Long getId() {
 return id;
 }

 public void setId(Long id) {
 this.id = id;
 }

 public String getName() {
 return name;
 }

 public void setName(String name) {
 this.name = name;
 }

 public Address getAddress() {
 return address;
 }

 public void setAddress(Address address) {
 this.address = address;
 }

 @OneToMany
 public Collection<Order> getOrders() {
 return orders;
 }

 public void setOrders(Collection<Order> orders) {
 this.orders = orders;
 }

 @ManyToMany
 public Set<PhoneNumber> getPhones() {
 return phones;
 }

 public void setPhones(Set<PhoneNumber> phones) {
 this.phones = phones;
 }

 // Business method to add a phone number to the customer
 public void addPhone(PhoneNumber phone) {
 this.getPhones().add(phone);

 // Update the phone entity instance to refer to this customer
 phone.addCustomer(this);
 }
}

2.3. Access Type

An access type determines how the persistence provider runtime reads
and writes the persistent state of an entity from and to an instance of
the entity class, as specified above in Section 2.2.
AccessType enumerates the two possibilities:

public enum AccessType {
 FIELD,
 PROPERTY
}

The access type for a persistent attribute depends on the placement of
object/relational mapping annotations in the entity class, and may be
explicitly overridden via use of the Access annotation defined in
Section 11.1.1.

2.3.1. Default Access Type

By default, a single access type (FIELD or PROPERTY) is inferred for
an entity hierarchy. The default access type of an entity hierarchy is
determined by the placement of mapping annotations on the attributes of
the entity classes and mapped superclasses of the entity hierarchy which
do not explicitly specify an access type.

If mapping annotations are placed on instance variables, FIELD access
is inferred.

If mapping annotations are placed on getter methods, PROPERTY access
is inferred.

An access type may be explicitly specified by means of the Access
annotation[8], as described
below in Section 2.3.2.

Every class in an entity hierarchy whose access type is defaulted in this
way must be consistent in its placement of mapping annotations on either
fields or properties, such that a single, consistent default access type
applies within the hierarchy. Any embeddable class used by an entity within
the hierarchy has the same access type as the default access type of the
hierarchy unless the Access annotation is specified, as defined below.

It is an error if a default access type cannot be determined and an access
type is not explicitly specified by a class-level Access annotation or
the XML descriptor. The behavior of applications which mix the placement
of mapping annotations on fields and properties within an entity hierarchy
without explicitly specifying the class-level Access annotation is
undefined.[9]

2.3.2. Explicit Access Type

The access type of an individual entity class, mapped superclass, or
embeddable class may be specified for that class, independent of the
default for the entity hierarchy to which it belongs, by annotating the
class with the Access annotation.

When Access(FIELD) is applied to an entity class, mapped superclass,
or embeddable class, mapping annotations may be placed on the instance
variables of that class, and the persistence provider runtime accesses
persistent state via direct access to the instance variables declared
by the class. Every non-transient instance variable not annotated
with the Transient annotation is persistent.

When Access(PROPERTY) is applied to an entity class, mapped superclass,
or embeddable class, mapping annotations may be placed on the properties
of that class, and the persistence provider runtime accesses persistent
state via the properties declared by that class. Every property not
annotated with the Transient annotation is persistent.

The explicit access type may be overridden at the attribute level. That
is, a class which explicitly specifies an access type using the Access
annotation may also have fields or properties annotated Access, and so
the class may have a mix of access types.

When Access(FIELD) is specified at the class level, an individual
attribute within the class may be selectively designated for property
access by annotating a property getter Access(PROPERTY). Mapping
annotations for this attribute must be placed on the getter. If a
mapping annotation is placed on a property getter which is not
annotated Access(PROPERTY), the behavior is undefined.

When Access(PROPERTY) is specified at the class level, an individual
attribute within the class may be selectively designated for field
access by annotating an instance variable Access(FIELD). Mapping
annotations for this attribute must be placed on the field. If a mapping
annotation is placed on a field which is not annotated Access(FIELD),
the behavior is undefined.

It is permitted (but redundant) to place Access(FIELD) on a field whose
class has field access or Access(PROPERTY) on a property whose class has
property access. On the other hand, the behavior is undefined if:

Access(PROPERTY) annotates a field,

Access(FIELD) annotates a property getter, or

the Access annotation occurs on a property setter.

Portable application should avoid such misplaced @Access annotations.

When access types are combined within a class, the Transient annotation
should be used to avoid duplicate persistent mappings. For example:

@Entity @Access(PROPERTY)
public class Customer {
 private Long id;

 @Access(FIELD) // use field access for name
 private String name;

 @Id
 public Long getId() {
 return id;
 }

 public void setId(Long id) {
 this.id = id;
 }

 @Transient // suppress duplicated name attribute
 public String getName() {
 return name;
 }

 public void setName(String name) {
 this.name = name;
 }

 ...
}

The Access annotation does not affect the access type of other entity
classes or mapped superclasses in the entity hierarchy. In particular,
persistent state inherited from a superclass is always accessed according
to the access type of that superclass.

2.3.3. Access Type of an Embeddable Class

The access type of an embeddable class is determined by the access type of
the entity class, mapped superclass, or embeddable class in which it is
embedded (including as a member of an element collection) independent of
whether the access type of the containing class is explicitly specified or
defaulted. A different access type for an embeddable class can be specified
for that embeddable class by means of the Access annotation as described
above in Section 2.3.2.

2.3.4. Defaulted Access Types of Embeddable Classes and Mapped Superclasses

Care must be taken when implementing an embeddable class or mapped superclass
which is used both in a context of field access and in a context of property
access, and whose access type is not explicitly specified by means of the
Access annotation or XML mapping file.

Such a class should be implemented so that the number, names, and types of
its persistent attributes are independent of the access type in use. The
behavior of an embeddable class or mapped superclass whose attributes are
not independent of access type is undefined with regard to use with the
metamodel API if the class occurs in contexts of differing access types
within the same persistence unit.

2.4. Primary Keys and Entity Identity

Every entity must have a primary key. The value of its primary key uniquely
identifies an entity instance within a persistence context and to operations
of the EntityManager, as described in Chapter 3.

The primary key must be declared by:

the entity class that is the root of the entity hierarchy, or

a mapped superclass that is a (direct or indirect) superclass of all
entity classes in the entity hierarchy.

A primary key must be defined exactly once in each entity hierarchy.

A primary key comprises one or more fields or properties (“attributes”)
of the entity class.

A simple primary key is a single persistent field or property of the
entity class whose type is one of the legal simple primary key types
listed below. The Id annotation defined in Section 11.1.22 or id XML
element must be used to identify the simple primary key.

A composite primary key must correspond to either a single persistent
field or property, or to a set of fields or properties, as described
below.[10] A primary key
class must be defined to represent the composite primary key.

If the composite primary key corresponds to a single field or property
of the entity, the EmbeddedId annotation defined by Section 11.1.17
identifies the primary key, and the type of the annotated field or
property is the primary key class.

Otherwise, when the composite primary key corresponds to multiple
fields or properties, the Id annotation defined by Section 11.1.22
identifies the fields and properties which comprise the composite key,
and the IdClass annotation defined by Section 11.1.23 must specify the
primary key class.

A simple primary key or field or property belonging to a composite primary
key should have one of the following basic types:

any Java primitive type, or java.lang wrapper for a primitive type,
[11]

java.lang.String,

java.util.UUID,

java.time.LocalDate, java.util.Date, or java.sql.Date,

BigDecimal or BigInteger from java.math.

If a primary key field or property has type java.util.Date, the temporal
type must be explicitly specified as DATE using the Temporal annotation
defined by Section 11.1.54, or by equivalent XML.

If the primary key is a composite primary key derived from the primary
key of another entity, the primary key may contain an attribute whose
type is that of the primary key of the referenced entity, as specified
below in Section 2.4.2.

An entity with a primary key involving any type other than the types
listed above is not portable. If the primary key is generated by the
persistence provider, as defined by Section 11.1.21, and its type is not
long, int, java.util.UUID, java.lang.String, java.lang.Long,
or java.lang.Integer, the entity is not portable.

The application must not change the value of the primary key of an entity
instance after the instance is made persistent[12]. If the application does change the value of a
primary key of an entity instance after the entity instance is made
persistent, the behavior is undefined.[13]

2.4.1. Composite primary keys

The following rules apply to composite primary keys:

The primary key class may be a non-abstract regular Java class with a
public or protected constructor with no parameters. Alternatively, the
primary key class may be any Java record type, in which case it need not
have a constructor with no parameters.

The access type (FIELD or PROPERTY) of a primary key class is
determined by the access type of the entity for which it is the primary
key, unless the primary key is an embedded id and an explicit access type
is specified using the Access annotation, as defined in Section 2.3.2.

If property-based access is used, the properties of the primary key class
must be public or protected.

The primary key class must define equals and hashCode methods. The
semantics of value equality for these methods must be consistent with the
database equality for the database types to which the key is mapped.

A composite primary key must either be represented and mapped as an
embeddable class (see Section 11.1.17) or it must be represented as an id
class and mapped to multiple fields or properties of the entity class
(see Section 11.1.23).

If the composite primary key class is represented as an id class, the
names of primary key fields or properties of the primary key class and
those of the entity class to which the id class is mapped must correspond
and their types must be the same.

A primary key which corresponds to a derived identity must conform to the
rules specified below in Section 2.4.2.

2.4.2. Primary Keys Corresponding to Derived Identities

The identity of an entity is said to be partially derived from the
identity of a second entity when the child or dependent first entity
is the owner of a many-to-one or one-to-one relationship which targets
the parent second entity and the foreign key referencing the parent
entity forms part of the primary key of the dependent entity.

A derived identity might be represented as a simple primary key or as a
composite primary key, as described in Section 2.4.2.1 below. The dependent
entity class has a composite primary key if

it declares one or more primary key attributes in addition to those
corresponding to the primary key of the parent, or

the parent itself has a composite primary key

and then an embedded id or id class must be used to represent the primary
key of the dependent entity. In the case that the parent has a composite
key, it is not required that parent entity and dependent entity both use
embedded ids, nor that both use id classes.

A ManyToOne or OneToOne relationship which maps a primary key column
or columns may be declared using either:

the Id annotation, when no other Id or EmbeddedId attribute maps
the same primary key column or columns, or

the MapsId annotation, if some other attribute or attributes annotated
Id or EmbeddedId also map the primary key column or columns.

If a ManyToOne or OneToOne relationship declared by a dependent
entity is annotated Id or MapsId, an instance of the entity cannot be
made persistent until the relationship has been assigned a reference to an
instance of the parent entity, since the identity of the dependent entity
declaring the relationship is derived from the referenced parent entity.
[14]

A dependent entity may have more than one parent entity.

2.4.2.1. Specification of Derived Identities

If a dependent entity uses an id class to represent its primary key,
one of the two following rules must be observed:

The names and types of the attributes of the id class and the Id
attributes of the dependent entity class must correspond as follows:

The Id attribute of the dependent entity class and the corresponding
attribute in the id class must have the same name.

If an Id attribute of the dependent entity class is of basic type,
the corresponding attribute in the id class must have the same type.

If an Id attribute of the entity is a ManyToOne or OneToOne
relationship to the parent entity, the corresponding attribute in the
id class must be of the same Java type as the id class or embedded id
of the parent entity (if the parent entity has a composite primary key)
or the type of the Id attribute of the parent entity (if the parent
entity has a simple primary key).

Alternatively, if the dependent entity declares a single primary key
attribute, that is, a OneToOne relationship attribute annotated Id,
then the id class specified by the dependent entity must be the same as
the primary key class of the parent entity.

If a dependent entity uses an embedded id to represent its primary key,
the relationship attribute which targets the parent entity must be annotated
MapsId.

If the embedded id of the dependent entity is of the same Java type as
the primary key of the parent entity, then the relationship attribute maps
both the relationship to the parent and the primary key of the dependent
entity, the relationship attribute must be a OneToOne association, and
the MapsId annotation must leave the value element unspecified.
[15]

Otherwise, the value element of the MapsId annotation must specify
the name of the attribute within the embedded id to which the relationship
attribute corresponds and this attribute of the embedded id must be of the
same type as the primary key of the parent entity.

An attribute of an embedded id which corresponds to a relationship targeting
a parent entity is treated by the provider as “read only”—that is, any direct
mutation of the attribute is not propagated to the database.

If a dependent entity has a single primary key attribute annotated Id,
and the primary key of the parent entity is a simple primary key, then
the primary key of the dependent entity is a simple primary key of the
same Java type as that of the parent entity, the relationship attribute
must be a OneToOne association targeting the parent entity, and either:

the primary key attribute annotated Id is the relationship attribute
itself, or

the primary key attribute annotated Id has the same type as the simple
primary key of the parent entity, the relationship attribute is annotated
MapsId, and the value element of the MapsId annotation is left
unspecified.

Neither EmbeddedId nor IdClass is specified for the dependent entity.

2.4.2.2. Mapping of Derived Identities

A dependent entity has derived primary key attributes, and might also have
additional primary key attributes which are not derived from any parent
entity.

Any primary key attribute of a dependent entity which is derived from the
identity of a parent entity is mapped by annotations of the corresponding
ManyToOne or OneToOne relationship attribute. The default mapping for
this relationship is specified in Section 2.12. The default mapping may be
overridden by annotating the relationship attribute with the JoinColumn
or JoinColumns annotation.

If the dependent entity uses an id class, the Column annotation may be
used to override the default mapping of Id attributes which are not
derived from any parent entity.

If the dependent entity uses an embedded id to represent its primary key,
the AttributeOverride annotation applied to the EmbeddedId attribute
may be used to override the default mapping of embedded id attributes which
are not derived from any parent entity.

2.4.2.3. Examples of Derived Identities

The following examples illustrate the rules specified above.

Example 1:

The parent entity has a simple primary key:

@Entity
public class Employee {
 @Id long empId;
 String empName;

 // ...
}

Case (a): The dependent entity uses IdClass to represent a composite key:

public class DependentId {
 String name; // matches name of @Id attribute
 long emp; // matches name of @Id attribute and type of Employee PK
}

@Entity
@IdClass(DependentId.class)
public class Dependent {
 @Id String name;

 // id attribute mapped by join column default
 @Id @ManyToOne
 Employee emp;

 // ...
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.name = 'Joe' AND d.emp.empName = 'Sam'

Case(b): The dependent entity uses EmbeddedId to represent a composite key:

@Embeddable
public class DependentId {
 String name;
 long empPK; // corresponds to PK type of Employee
}

@Entity
public class Dependent {
 @EmbeddedId DependentId id;

 // id attribute mapped by join column default
 @MapsId("empPK") // maps empPK attribute of embedded id
 @ManyToOne
 Employee emp;

 // ...
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.id.name = 'Joe' AND d.emp.empName = 'Sam'

Example 2:

The parent entity uses IdClass:

public class EmployeeId {
 String firstName;
 String lastName;

 // ...
}

@Entity
@IdClass(EmployeeId.class)
public class Employee {
 @Id String firstName
 @Id String lastName

 // ...
}

Case (a): The dependent entity uses IdClass:

public class DependentId {
 String name; // matches name of attribute
 EmployeeId emp; //matches name of attribute and type of Employee PK
}

@Entity
@IdClass(DependentId.class)
public class Dependent {
 @Id
 String name;

 @Id
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 @ManyToOne
 Employee emp;
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.name = 'Joe' AND d.emp.firstName = 'Sam'

Case (b): The dependent entity uses
EmbeddedId. The type of the empPK attribute is the same as that of
the primary key of Employee. The EmployeeId class needs to be
annotated Embeddable or denoted as an embeddable class in the XML
descriptor.

@Embeddable
public class DependentId {
 String name;
 EmployeeId empPK;
}

@Entity
public class Dependent {
 @EmbeddedId
 DependentId id;

 @MapsId("empPK")
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 @ManyToOne
 Employee emp;

 // ...
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.id.name = 'Joe' AND d.emp.firstName = 'Sam'

Note that the following alternative query
will yield the same result:

SELECT d
FROM Dependent d
WHERE d.id.name = 'Joe' AND d.id.empPK.firstName = 'Sam'

Example 3:

The parent entity uses EmbeddedId:

@Embeddable
public class EmployeeId {
 String firstName;
 String lastName;

 // ...
}

@Entity
public class Employee {
 @EmbeddedId
 EmployeeId empId;

 // ...
}

Case (a): The dependent entity uses IdClass:

public class DependentId {
 String name; // matches name of @Id attribute
 EmployeeId emp; // matches name of @Id attribute and type of embedded id of Employee
}

@Entity
@IdClass(DependentId.class)
public class Dependent {
 @Id
 @Column(name="dep_name") // default column name is overridden
 String name;

 @Id
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 @ManyToOne Employee
 emp;
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.name = 'Joe' and d.emp.empId.firstName = 'Sam'

Case (b): The dependent entity uses EmbeddedId:

@Embeddable
public class DependentId {
 String name;
 EmployeeId empPK; // corresponds to PK type of Employee
}

@Entity
public class Dependent {
 // default column name for "name" attribute is overridden
 @AttributeOverride(name="name", column=@Column(name="dep_name"))
 @EmbeddedId DependentId id;

 @MapsId("empPK")
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })
 @ManyToOne
 Employee emp;

 // ...
}

Sample query:

SELECT d
FROM Dependent d
WHERE d.id.name = 'Joe' and d.emp.empId.firstName = 'Sam'

Note that the following alternative query will yield the same result:

SELECT d
FROM Dependent d
WHERE d.id.name = 'Joe' AND d.id.empPK.firstName = 'Sam'

Example 4:

The parent entity has a simple primary key:

@Entity
public class Person {
 @Id
 String ssn;

 // ...
}

Case (a): The dependent entity has a
single primary key attribute which is mapped by the relationship
attribute. The primary key of MedicalHistory is of type String.

@Entity
public class MedicalHistory {
 // default join column name is overridden
 @Id
 @OneToOne
 @JoinColumn(name="FK")
 Person patient;

 // ...
}

Sample query:

SELECT m
FROM MedicalHistory m
WHERE m.patient.ssn = '123-45-6789'

Case (b): The dependent entity has
a single primary key attribute corresponding to the relationship
attribute. The primary key attribute is of the same basic type as the
primary key of the parent entity. The MapsId annotation applied to the
relationship attribute indicates that the primary key is mapped by the
relationship attribute.[16]

@Entity
public class MedicalHistory {
 @Id
 String id; // overriding not allowed

 // ...

 // default join column name is overridden
 @MapsId
 @JoinColumn(name="FK")
 @OneToOne
 Person patient;

 // ...
}

Sample query:

SELECT m
FROM MedicalHistory m WHERE m.patient.ssn = '123-45-6789'

Example 5:

The parent entity uses IdClass. The
dependent’s primary key class is of same type as that of the parent
entity.

public class PersonId {
 String firstName;
 String lastName;
}

@Entity
@IdClass(PersonId.class)
public class Person {
 @Id
 String firstName;

 @Id
 String lastName;

 // ...
}

Case (a): The dependent entity uses IdClass:

@Entity
@IdClass(PersonId.class)
public class MedicalHistory {
 @Id
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 @OneToOne
 Person patient;

 // ...
}

Sample query:

SELECT m
FROM MedicalHistory m
WHERE m.patient.firstName = 'Charles'

Case (b): The dependent entity uses the
EmbeddedId and MapsId annotations. The PersonId class needs to be
annotated Embeddable or denoted as an embeddable class in the XML
descriptor.

@Entity
public class MedicalHistory {
 // all attributes map to relationship:
 AttributeOverride not allowed

 @EmbeddedId
 PersonId id;

 // ...

 @MapsId
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 @OneToOne Person patient;

 // ...
}

Sample query:

SELECT m
FROM MedicalHistory m
WHERE m.patient.firstName = 'Charles'

Note that the following alternative query
will yield the same result:

SELECT m
FROM MedicalHistory m
WHERE m.id.firstName = 'Charles'

Example 6:

The parent entity uses EmbeddedId. The
dependent’s primary key is of the same type as that of the parent.

@Embeddable
public class PersonId {
 String firstName;
 String lastName;
}

@Entity
public class Person {
 @EmbeddedId PersonId id;

 // ...
}

Case (a): The dependent class uses IdClass:

@Entity
@IdClass(PersonId.class)
public class MedicalHistory {
 @Id
 @OneToOne
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })

 Person patient;

 // ...
}

Case (b): The dependent class uses EmbeddedId:

@Entity
public class MedicalHistory {
 // All attributes are mapped by the relationship
 // AttributeOverride is not allowed
 @EmbeddedId PersonId id;

 // ...

 @MapsId
 @JoinColumns({
 @JoinColumn(name="FK1", referencedColumnName="firstName"),
 @JoinColumn(name="FK2", referencedColumnName="lastName")
 })
 @OneToOne
 Person patient;

 // ...
}

2.5. Entity Versions

An entity might have a version, a persistent field or property used by
the persistence provider to perform optimistic locking, as specified in
Section 3.5.2. The version field or property holds a version number or
timestamp identifying the revision of the entity data held by an entity
class instance. In the course of performing lifecycle operations involving
the entity instance, the persistence provider gets and sets the version
field or property of the entity instance to determine or modify its version
number or timestamp. The Version annotation defined in Section 11.1.57 or
version XML element must be used to explicitly identify the version field
or property of an entity.

An entity class may access the state of its version field or property or
export a method which allows other user-written code to access the version,
but user-written code must not directly modify the value of the version
field or property of an entity instance after the entity is made persistent.
[17] With the exception noted in Section 4.11, only
the persistence provider is permitted to set or update the entity version.
If the application does directly modify the value of the version field or
property of an entity instance after it is made persistent, the behavior is
undefined.

The version must be of one of the following basic types:

int, Integer, short, Short, long, Long, or

java.time.LocalDateTime, java.time.Instant, or java.sql.Timestamp.

A portable application must not declare a version field or property with any
other type.

An entity class should have at most one version. A portable application
must not define an entity class having more than one version field or
property.

The version should be declared by the root entity class in an entity class
hierarchy, or by one of its mapped superclasses. A portable application
must not declare a version field or property in a subclass of the root
class of an entity class hierarchy.

2.6. Basic Types

The following Java types are considered basic types:

any Java primitive type, or java.lang wrapper class for a primitive type,

java.lang.String,

java.util.UUID,

BigInteger or BigDecimal from java.math,

LocalDate, LocalTime, LocalDateTime,
OffsetTime, OffsetDateTime,
Instant, or Year from java.time,

Date or Calendar [18] from java.util [19],

Date, Time, or Timestamp from java.sql [20],

byte[] or Byte[], char[] or Character[] [21],

any Java enum type,

any other type which implements java.io.Serializable.

Persistence for basic types is defined in Section 11.1.6 and Section 11.1.18.

2.7. Embeddable Classes

An entity may use other fine-grained classes
to represent entity state. Instances of these classes, unlike entity
instances, do not have persistent identity of their own. Instead, they
exist only as part of the state of the entity to which they belong. An
entity may have collections of embeddables as well as single-valued
embeddable attributes. Embeddables may also be used as map keys and map
values. Embedded objects belong strictly to their owning entity, and are
not sharable across persistent entities. Attempting to share an embedded
object across entities has undefined semantics.

Embeddable classes must be annotated as
Embeddable or denoted in the XML descriptor as such. The access type
for an embedded object is determined as described in Section 2.3.

An embeddable class may be a regular Java class which adheres to the
requirements specified in Section 2.1 for entities, with the exception that
an embeddable class is not annotated as Entity, and an embeddable
class may not be abstract.

Alternatively, an embeddable class may be any Java record type.

An embeddable class may be used to represent
the state of another embeddable class.

An embeddable class (including an
embeddable class within another embeddable class) may contain a
collection of a basic type or other embeddable
class.[22]

An embeddable class may contain a
relationship to an entity or collection of entities. Since instances of
embeddable classes themselves have no persistent identity, the
relationship from the referenced entity is to the entity that
contains the embeddable instance(s) and not to the embeddable
itself.[23] An embeddable class that is used as an
embedded id or as a map key must not contain such a relationship.

Additional requirements and restrictions on
embeddable classes are described in Section 2.8.

2.8. Collections of Embeddable Classes and Basic Types

A persistent field or property of an entity
or embeddable class may correspond to a collection of a basic type or
embeddable class (“element collection”). Such a collection, when
specified as such by the ElementCollection annotation, is mapped by
means of a collection table, as defined in Section 11.1.8. If the
ElementCollection annotation (or XML equivalent) is not specified for
the collection-valued field or property, the rules of Section 2.10 apply.

An embeddable class (including an embeddable
class within another embeddable class) that is contained within an
element collection must not contain an element collection, nor may it
contain a relationship to an entity other than a many-to-one or
one-to-one relationship. The embeddable class must be on the owning side
of such a relationship and the relationship must be mapped by a foreign
key mapping. (See Section 2.11)

2.9. Map Collections

Collections of elements and entity
relationships can be represented as java.util.Map collections.

The map key and the map value independently
can each be a basic type, an embeddable class, or an entity.

The ElementCollection, OneToMany, and
ManyToMany annotations are used to specify the map as an element
collection or entity relationship as follows: when the map value is a
basic type or embeddable class, the ElementCollection annotation is
used; when the map value is an entity, the OneToMany or ManyToMany
annotation is used.

Bidirectional relationships represented as
java.util.Map collections support the use of the Map datatype on one
side of the relationship only.

2.9.1. Map Keys

If the map key type is a basic type, the
MapKeyColumn annotation can be used to specify the column mapping for
the map key. If the MapKeyColumn annotation is not specified, the
default values of the MapKeyColumn annotation apply as described in Section 11.1.34.

If the map key type is an embeddable class,
the mappings for the map key columns are defaulted according to the
default column mappings for the embeddable class. (See Section 11.1.9). The
AttributeOverride and AttributeOverrides annotations can be used to
override these mappings, as described in Section 11.1.4 and Section 11.1.5. If an
embeddable class is used as a map key, the embeddable class must
implement the hashCode and equals methods consistently with the
database columns to which the embeddable is
mapped[24].

If the map key type is an entity, the
MapKeyJoinColumn and MapKeyJoinColumns annotations are used to
specify the column mappings for the map key. If the primary key of the
referenced entity is a simple primary key and the MapKeyJoinColumn
annotation is not specified, the default values of the
MapKeyJoinColumn annotation apply as described in Section 11.1.36.

If Java generic types are not used in the
declaration of a relationship attribute of type java.util.Map, the
MapKeyClass annotation must be used to specify the type of the key of
the map.

The MapKey annotation is used to specify
the special case where the map key is itself the primary key or a
persistent field or property of the entity that is the value of the map.
The MapKeyClass annotation is not used when MapKey is specified.

2.9.2. Map Values

When the value type of the map is a basic
type or an embeddable class, a collection table is used to map the map.
If Java generic types are not used, the targetClass element of the
ElementCollection annotation must be used to specify the value type
for the map. The default column mappings for the map value are derived
according to the default mapping rules for the CollectionTable
annotation defined in Section 11.1.8. The Column annotation is used to override
these defaults for a map value of basic type. The AttributeOverride(s) and AssociationOverride(s) annotations are used to override
the mappings for a map value that is an embeddable class.

When the value type of the map is an entity,
a join table is used to map the map for a many-to-many relationship or,
by default, for a one-to-many unidirectional relationship. If the
relationship is a bidirectional one-to-many/many-to-one relationship, by
default the map is mapped in the table of the entity that is the value
of the map. If Java generic types are not used, the targetEntity
element of the OneToMany or ManyToMany annotation must be used to
specify the value type for the map. Default mappings are described in
Section 2.12.

2.10. Mapping Defaults for Non-Relationship Fields or Properties

If a persistent field or property other than a relationship property is
not annotated with one of the mapping annotations defined in Chapter 11
(and no equivalent mapping information is specified in any XML descriptor),
the following default mapping rules are applied in order:

If the type of the field or property is a class annotated with the
Embeddable annotation, the field or property is mapped as if it were
annotated with the Embedded annotation. See Section 11.1.15 and Section 11.1.16.

Otherwise, if the type of the field or property is one of the one of
the basic types listed in Section 2.6, it is mapped in the same way as if
it were annotated as Basic. See Section 11.1.6, Section 11.1.18, Section 11.1.29,
and Section 11.1.54.

It is an error if no annotation is present and neither of the above rules
apply.

2.11. Entity Relationships

Relationships among entities may be
one-to-one, one-to-many, many-to-one, or many-to-many. Relationships are
polymorphic.

If there is an association between two
entities, one of the following relationship modeling annotations must be
applied to the corresponding persistent property or field of the
referencing entity: OneToOne, OneToMany, ManyToOne,
ManyToMany. For associations that do not specify the target type
(e.g., where Java generic types are not used for collections), it is
necessary to specify the entity that is the target of the
relationship.[25] Equivalent XML elements may be used
as an alternative to these mapping annotations.

These annotations mirror common practice in
relational database schema modeling. The use of the relationship
modeling annotations allows the object/relationship mapping of
associations to the relational database schema to be fully defaulted, to
provide an ease-of-development facility. This is described in Section 2.12.
