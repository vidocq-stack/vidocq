# 11. Metadata for Object/Relational Mapping (part 2/4)

Type
Name
Description
Default

EnumType

value

(Optional) The type used in mapping an enum type.

ORDINAL

If a persistent field or property of enum type has no explicit Enumerated
annotation, and if no converter is applied to the field or property:

if the enum type has a final field of type java.lang.String annotated
EnumeratedValue, the enumerated type is inferred to be STRING;

otherwise, the enumerated type is taken to be ORDINAL.

The enum type may have a final field annotated EnumeratedValue.
This field, if it exists, controls the mapping of enum values to database
column values:

if the enum type does have a field annotated EnumeratedValue,
each enum value is mapped to the value of the annotated field,
or, otherwise,

if the enumerated type is ORDINAL, each enum value is mapped
to the value of the ordinal field, but

if the enumerated type is STRING, each enum value is mapped
to the value of the name field.

Example:

public enum EmployeeStatus {FULL_TIME, PART_TIME, CONTRACT}
public enum SalaryRate {JUNIOR, SENIOR, MANAGER, EXECUTIVE}

@Entity
public class Employee {
 // ...

 public EmployeeStatus getStatus() {...}

 @Enumerated(STRING)
 public SalaryRate getPayScale() {...}

 // ...
}

If the status property is mapped to a column
of integer type, and the payscale property to a column of varchar type,
an instance that has a status of PART_TIME and a pay rate of JUNIOR
will be stored with STATUS set to 1 and PAYSCALE set to "JUNIOR".

11.1.19. EnumeratedValue Annotation

The EnumeratedValue annotation specifies that an annotated field of
a Java enum type is the source of database column values when the enum
occurs as the declared type of an Enumerated property or field. The
annotated field must be declared final, and must be of type:

byte, short, or int for an ORDINAL enumerated type, or

java.lang.String for a STRING enumerated type.

The field must not be set to null, and must hold a distinct value for
each value of the enum type.

If the type of the field annotated EnumeratedValue disagrees with
the enumerated type mapping specified by the Enumerated annotation,
the behavior is undefined. Portable applications should ensure that
the type of the field annotated EnumeratedValue agrees with the
type mapping wherever the enum type is used in a field or property
explicitly annotated Enumerated.

If a converter is applied to an Enumerated field or property, the
EnumeratedValue annotation is ignored for that field or property.

Example:

enum Status {
 OPEN(0), CLOSED(1), CANCELLED(-1);

 @EnumeratedValue
 final int intValue;

 Status(int intValue) {
 this.intValue = intValue;
 }
}

11.1.20. ForeignKey Annotation

The ForeignKey annotation is used to
specify the handling of foreign key constraints when schema generation
is in effect. If this annotation is not specified, the persistence
provider’s default foreign key strategy will be used.

@Target({})
@Retention(RUNTIME)
public @interface ForeignKey {
 String name() default "";
 ConstraintMode value() default CONSTRAINT;
 String foreignKeyDefinition() default "";
 String options() default "";
}

The name element specifies a name for the
foreign key constraint.

The ConstraintMode enum is used to control
the application of constraints.

public enum ConstraintMode {CONSTRAINT, NO_CONSTRAINT, PROVIDER_DEFAULT}

The enum values have the following semantics:
A value of CONSTRAINT will cause the persistence provider to generate
a foreign key constraint. A value of NO_CONSTRAINT will result in no
constraint being generated. A value of PROVIDER_DEFAULT will result in
the provider’s default behavior (which may or may not result in the
generation of a constraint for any given join column or set of join
columns).

The syntax used in the foreignKeyDefinition
element should follow the SQL syntax used by the target database for
foreign key constraints. For example, this may be similar to the
following:

FOREIGN KEY (<COLUMN expression> {, <COLUMN expression>}... )
REFERENCES <TABLE identifier> [ (<COLUMN expression> {, <COLUMN expression>}... ) ]
[ ON UPDATE <referential action> ]
[ ON DELETE <referential action> ]

If the ForeignKey annotation is specified
with a ConstraintMode value of CONSTRAINT, but the
foreignKeyDefinition element is not specified, the provider will
generate a foreign key constraint whose update and delete actions it
determines most appropriate for the join column(s) to which the foreign
key constraint is applied

Table 19 lists the annotation elements that may be specified
for the ForeignKey annotation.

Table 19. ForeignKey Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the foreign key constraint.

A provider-generated name.

ConstraintMode

value

(Optional) Whether to generate a constraint.

CONSTRAINT

String

foreignKeyDefinition

(Optional) The foreign key constraint definition.

Provider-default. If the value of the ConstraintMode element is` NO_CONSTRAINT`,
the provider must not generate a foreign key constraint.

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

11.1.21. GeneratedValue Annotation

The GeneratedValue annotation specifies a generation strategy for the
values of primary keys. The GeneratedValue annotation may be applied
to a primary key property or field of an entity or mapped superclass in
conjunction with the Id annotation.[115] The persistence provider is only required to
support the use of the GeneratedValue annotation for simple primary
keys. Use of the GeneratedValue annotation for derived primary keys
is not supported.

Table 20 lists the annotation elements that may be specified for the
GeneratedValue annotation and their default values.

The types of primary key generation are defined by the GenerationType
enum:

public enum GenerationType { TABLE, SEQUENCE, IDENTITY, UUID, AUTO };

The TABLE generator type value indicates that the persistence
provider must assign primary keys for the entity using an underlying
database table to ensure uniqueness.

The SEQUENCE and IDENTITY values specify the use of a database
sequence or identity column, respectively.[116]

The further specification of table generators and sequence generators
is described in Section 11.1.49 and Section 11.1.52.

A TABLE, SEQUENCE, or IDENTITY generator may be used to generate
values for a primary key property or field of type java.lang.Long,
java.lang.Integer, long, or int.

The UUID value indicates that the persistence provider should assign
an RFC 4122 Universally Unique IDentifier.

A UUID generator may be used to generate values for a primary key
property or field of type java.util.UUID or java.lang.String.

The AUTO value indicates that the persistence provider should pick
an appropriate strategy given the type of the primary key property or
field, and the capabilities of the particular database. In the case of
a field or property of type java.util.UUID or java.lang.String,
the AUTO strategy is equivalent to UUID. In the case of a field or
property of type java.lang.Long, java.lang.Integer, long, or int,
the AUTO strategy may select between TABLE, SEQUENCE, or IDENTITY.

The AUTO generation strategy may expect a database resource to exist,
or it may attempt to create one. A vendor may provide documentation on
how to create such resources in the event that it does not support
schema generation or cannot create the schema resource at runtime.

This specification does not define the exact behavior of these strategies.

However, if the persistence provider stores a value generated according
to the UUID strategy in a column of type VARCHAR or equivalent, the
value must be stored in its canonical representation, unless the
application explicitly indicates that some other representation is
preferred.

The name member specifies the name of a generator to use, and defaults
to the entity name of the entity in which the GeneratedValue annotation
occurs. If the name is not specified, and if there is no generator with
the defaulted name, then the persistence provider supplies a default id
generator, of a type compatible with the value of the strategy member.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface GeneratedValue {
 GenerationType strategy() default AUTO;
 String generator() default "";
}

Table 20. GeneratedValue Annotation Elements

Type
Name
Description
Default

GenerationType

strategy

(Optional) The primary key generation strategy that the persistence
provider must use to generate the annotated entity primary key.

GenerationType.AUTO

String

generator

(Optional) The name of the primary key generator to use as specified
in the SequenceGenerator or TableGenerator annotation which
declares the generator.

The entity name of the entity in which the annotation occurs.

Example 1:

@Id
@GeneratedValue(strategy=SEQUENCE, generator="CUST_SEQ")
@Column(name="CUST_ID")
public Long getId() { return id; }

Example 2:

@Id
@GeneratedValue(strategy=TABLE, generator="CUST_GEN")
@Column(name="CUST_ID")
Long id;

11.1.22. Id Annotation

The Id annotation declares a primary key property or field of an entity.
The Id annotation may be applied to a property or field of:

an entity class that is the root of an entity hierarchy, or

a mapped superclass that is a superclass of all entity classes in an
entity hierarchy.

The field or property to which the Id annotation is applied should have
one of the legal simple primary key types listed in Section 2.4.[117][118]

The mapped column for the primary key of the entity is assumed to be the
primary key of the primary table. If no Column annotation is specified,
the primary key column name is assumed to be the name of the primary key
property or field.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Id {}

Example:

@Id
public Long getId() { return id; }

11.1.23. IdClass Annotation

The IdClass annotation is applied to an
entity class or a mapped superclass to specify a composite primary key
class that is mapped to multiple fields or properties of the entity.

The names of the fields or properties in the
primary key class and the primary key fields or properties of the entity
must correspond and their types must match according to the rules
specified in Section 2.4 and Section 2.4.2.

The Id annotation must also be applied to
the corresponding fields or properties of the entity.

@Target({TYPE})
@Retention(RUNTIME)
public @interface IdClass {
 Class value();
}

Table 21 lists the annotation elements that may be specified
for the IdClass annotation.

Table 21. IdClass Annotation Elements

Type
Name
Description
Default

Class

value

(Required) The composite primary key class.

Example:

@IdClass(com.acme.EmployeePK.class)
@Entity
public class Employee {
 @Id
 String empName;

 @Id
 Date birthDay;

 // ...
}

11.1.24. Index Annotation

The Index annotation is used in schema
generation. Note that it is not necessary to specify an index for a
primary key, as the primary key index will be created automatically,
however, the Index annotation may be used to specify the ordering of the
columns in the index for the primary key.

@Target({})
@Retention(RUNTIME)
public @interface Index {
 String name() default "";
 String columnList();
 boolean unique() default false;
 String options() default "";
}

The syntax of the columnList element is a
column_list, as follows:

column_list::= index_column [,index_column]*
index_column::= column_name [ASC | DESC]

The persistence provider must observe the specified ordering of the columns.

If ASC or DESC is not specified, ASC
(ascending order) is assumed.

Table 22 lists the annotation elements that may be specified
for the Index annotation.

Table 22. Index Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the index.

A provider-generated name.

String

columnList

(Required) The names of the columns to be
included in the index.

boolean

unique

(Optional) Whether the index is unique.

false

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

11.1.25. Inheritance Annotation

The Inheritance annotation defines the
inheritance strategy to be used for an entity class hierarchy. It is
specified on the entity class that is the root of the entity class
hierarchy.

If the Inheritance annotation is not
specified or if no inheritance type is specified for an entity class
hierarchy, the SINGLE_TABLE mapping strategy is used.

Support for the combination of inheritance
strategies is not required by this specification. Portable applications
should only use a single inheritance strategy within an entity
hierarchy.

The three inheritance mapping strategies are
the single table per class hierarchy, joined subclass, and table per
concrete class strategies. See Section 2.14 for a more detailed discussion of
inheritance strategies.

The inheritance strategy options are defined
by the InheritanceType enum:

public enum InheritanceType { SINGLE_TABLE, JOINED, TABLE_PER_CLASS };

Support for the TABLE_PER_CLASS mapping
strategy is optional in this release.

Table 23 lists the annotation elements that may be specified
for the Inheritance annotation and their default values.

@Target({TYPE})
@Retention(RUNTIME)
public @interface Inheritance {
 InheritanceType strategy() default SINGLE_TABLE;
}

Table 23. Inheritance Annotation Elements

Type
Name
Description
Default

InheritanceType

strategy

(Optional) The inheritance strategy to use for the entity inheritance hierarchy.

InheritanceType.SINGLE_TABLE

Example:

@Entity
@Inheritance(strategy=JOINED)
public class Customer { ... }

@Entity
public class ValuedCustomer extends Customer { ... }

11.1.26. JoinColumn Annotation

The JoinColumn annotation is used to
specify a column for joining an entity association or element
collection.

Table 24 lists the annotation elements that may be specified
for the JoinColumn annotation and their default values.

If the JoinColumn annotation itself is
defaulted, a single join column is assumed and the default values
described in Table 24 apply.

The name annotation element defines the
name of the foreign key column. The remaining annotation elements (other
than referencedColumnName) refer to this column and have the same
semantics as for the Column annotation.

If the referencedColumnName element is
missing, the foreign key is assumed to refer to the primary key of the
referenced table.

Support for referenced columns that are not
primary key columns of the referenced table is optional. Applications
that use such mappings will not be portable.

The foreignKey annotation element is used
to specify or control the generation of a foreign key constraint when
schema generation is in effect. If this element is not specified, the
persistence provider’s default foreign key strategy will apply.

If more than one JoinColumn annotation is
applied to a field or property, both the name and the
referencedColumnName elements must be specified in each such
JoinColumn annotation.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(JoinColumns.class)
public @interface JoinColumn {
 String name() default "";
 String referencedColumnName() default "";
 boolean unique() default false;
 boolean nullable() default true;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
 CheckConstraint[] check() default {}
 String comment() default "";
}

Table 24. JoinColumn Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the foreign key
column. The table in which it is found depends upon the context. If the
join is for a OneToOne or ManyToOne mapping using a foreign key mapping
strategy, the foreign key column is in the table of the source entity or
embeddable. If the join is for a unidirectional OneToMany mapping using
a foreign key mapping strategy, the foreign key is in the table of the
target entity. If the join is for a ManyToMany mapping or for a OneToOne
or bidirectional ManyToOne/OneToMany mapping using a join table, the
foreign key is in a join table. If the join is for an element
collection, the foreign key is in a collection table.

(Default only applies if a single join
column is used.) The concatenation of the following: the name of the
referencing relationship property or field of the referencing entity or
embeddable class; “_”; the name of the referenced primary key column. If
there is no such referencing relationship property or field in the
entity, or if the join is for an element collection, the join column
name is formed as the concatenation of the following: the name of the
entity; “_”; the name of the referenced primary key column.

String

referencedColumnName

(Optional) The name of the column referenced
by this foreign key column. When used with entity relationship mappings
other than the cases described below, the referenced column is in the
table of the target entity. When used with a unidirectional OneToMany
foreign key mapping, the referenced column is in the table of the source
entity. When used inside a JoinTable annotation, the referenced key
column is in the entity table of the owning entity, or inverse entity if
the join is part of the inverse join definition. When used in a
collection table mapping, the referenced column is in the table of the
entity containing the collection.

(Default
only applies if single join column is being used.) The same name as the
primary key column of the referenced table.

boolean

unique

(Optional) Whether the property is a unique
key. This is a shortcut for the UniqueConstraint annotation at the table
level and is useful for when the unique key constraint is only a single
field. It is not necessary to explicitly specify this for a join column
that corresponds to a primary key that is part of a foreign key.

false

boolean

nullable

(Optional) Whether the foreign key column is nullable.

true

boolean

insertable

(Optional) Whether the column is included in
SQL INSERT statements generated by the persistence provider.

true

boolean

updatable

(Optional) Whether the column is included in
SQL UPDATE statements generated by the persistence provider.

true

String

columnDefinition

(Optional) The SQL fragment that is used
when generating the DDL for the column.

Generated SQL for the column.

String

table

(Optional) The name of the table that contains the column.

If the join is for a
OneToOne or ManyToOne mapping using a foreign key mapping strategy, the
name of the table of the source entity or embeddable. If the join is for
a unidirectional OneToMany mapping using a foreign key mapping strategy,
the name of the table of the target entity. If the join is for a
ManyToMany mapping or for a OneToOne or bidirectional ManyToOne/
OneToMany mapping using a join table, the name of the join table. If the
join is for an element collection, the name of the collection table.

ForeignKey

foreignKey

(Optional) The foreign key constraint for
the join column. This is used only if table generation is in effect.

Provider’s default

CheckConstraint[]

check

(Optional) Check constraints for the column. These are
only used if table generation is in effect.

No check constraint

String

comment

(Optional) Comment for the column. This is
only used if table generation is in effect.

No comment

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

Example 1:

@ManyToOne
@JoinColumn(name="ADDR_ID")
public Address getAddress() { return address; }

Example 2: Unidirectional One-to-Many association using a foreign key mapping.

In Customer class:

@OneToMany
@JoinColumn(name="CUST_ID") // join column is in table for Order
public Set<Order> getOrders() { return orders; }

11.1.27. JoinColumns Annotation

Composite foreign keys are supported by means
of the JoinColumns annotation. The JoinColumns annotation groups
JoinColumn annotations for the same relationship.

When the JoinColumns annotation is used,
both the name and the referencedColumnName elements must be
specified in each of the grouped JoinColumn annotations.

The foreignKey annotation element is used
to specify or control the generation of a foreign key constraint when
schema generation is in effect. If both this element and the
foreignKey element of any of the JoinColumn elements referenced by
the value element are specified, the behavior is undefined. If no
foreignKey annotation element is specified in either location, the
persistence provider’s default foreign key strategy will apply.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface JoinColumns {
 JoinColumn[] value();
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
}

Table 25 lists the annotation elements that may
be specified for the JoinColumns annotation.

Table 25. JoinColumns Annotation Elements

Type
Name
Description
Default

JoinColumn[]

value

(Required) The join columns that map the relationship.

ForeignKey

foreignKey

(Optional) The foreign key constraint
specification for the join columns. This is used only if table
generation is in effect.

Provider’s default

Example:

@ManyToOne
@JoinColumns({
 @JoinColumn(name="ADDR_ID", referencedColumnName="ID"),
 @JoinColumn(name="ADDR_ZIP", referencedColumnName="ZIP")
})
public Address getAddress() { return address; }

11.1.28. JoinTable Annotation

The JoinTable annotation is used in the
mapping of entity associations. A JoinTable annotation is specified on
the owning side of the association. A join table is typically used in
the mapping of many-to-many and unidirectional one-to-many associations.
It may also be used to map bidirectional many-to-one/one-to-many
associations, unidirectional many-to-one relationships, and one-to-one
associations (both bidirectional and unidirectional).

Table 26 lists the annotation elements that may be specified
for the JoinTable annotation and their default values.

If the JoinTable annotation is not
explicitly specified for the mapping of a many-to-many or unidirectional
one-to-many relationship, the default values of the annotation elements
apply.

The name of the join table is assumed to be
the table names of the associated primary tables concatenated together
(owning side first) using an underscore.

The foreignKey element is used to specify
or control the generation of a foreign key constraint for the columns
corresponding to the joinColumns element when table generation is in
effect. If both this element and the foreignKey element of any of the
joinColumns elements are specified, the behavior is undefined. If no
foreignKey annotation element is specified in either location, the
persistence provider’s default foreign key strategy will apply. The
inverseForeignKey element applies to the generation of a foreign key
constraint for the columns corresponding to the inverseJoinColumns
element, and similar rules apply.

When a join table is used in mapping a
relationship with an embeddable class on the owning side of the
relationship, the containing entity rather than the embeddable class is
considered the owner of the relationship.

This annotation may not be applied to a persistent field or property
not annotated @ManyToOne, @OneToOne, @ManyToMany, or @OneToMany.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface JoinTable {
 String name() default "";
 String catalog() default "";
 String schema() default "";
 JoinColumn[] joinColumns() default {};
 JoinColumn[] inverseJoinColumns() default {};
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
 ForeignKey inverseForeignKey() default @ForeignKey(PROVIDER_DEFAULT);
 UniqueConstraint[] uniqueConstraints() default {};
 Index[] indexes() default {};
 CheckConstraint[] check() default {}
 String comment() default "";
 String options() default "";
}

Table 26. JoinTable Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the join table.

The concatenated names of the two associated
primary entity tables (owning side first), separated by an underscore.

String

catalog

(Optional) The catalog of the table.

Default catalog.

String

schema

(Optional) The schema of the table.

Default schema for user.

JoinColumn[]

joinColumns

(Optional) The foreign key columns of the
join table which reference the primary table of the entity owning the
association (i.e. the owning side of the association).

The same defaults as for JoinColumn.

JoinColumn[]

inverseJoinColumns

(Optional) The foreign key columns of the
join table which reference the primary table of the entity that does not
own the association (i.e. the inverse side of the association).

The same defaults as for JoinColumn.

ForeignKey

foreignKey

(Optional) The foreign key constraint
specification for the join columns. This is used only if table
generation is in effect.

Provider’s default.

ForeignKey

inverseForeignKey

(Optional) The foreign key constraint
specification for the inverse join columns. This is used only if table
generation is in effect.

Provider’s default.

UniqueConstraint[]

uniqueConstraints

(Optional) Unique constraints that are to be
placed on the table. These are only used if table generation is in
effect.

No additional constraints

Index[]

indexes

(Optional) Indexes for the table. These are
only used if table generation is in effect.

No additional indexes

CheckConstraint[]

check

(Optional) Check constraints for the table. These are
only used if table generation is in effect.

No check constraint

String

comment

(Optional) Comment for the table. This is
only used if table generation is in effect.

No comment

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

Example:

@JoinTable(
 name="CUST_PHONE",
 joinColumns=@JoinColumn(name="CUST_ID", referencedColumnName="ID"),
 inverseJoinColumns=@JoinColumn(name="PHONE_ID", referencedColumnName="ID")
)

11.1.29. Lob Annotation

A Lob annotation specifies that a
persistent property or field should be persisted as a large object to a
database-supported large object type. Portable applications should use
the Lob annotation when mapping to a database Lob type. The Lob
annotation may be used in conjunction with the Basic annotation or
with the `ElementCollection`[119] annotation when the
element collection value is of basic type. A Lob may be either a binary
or character type. The Lob type is inferred from the type of the
persistent field or property and, except for string and character types,
defaults to Blob.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Lob {

}

Example 1:

@Lob
@Basic(fetch=EAGER)
@Column(name="REPORT")
protected String report;

Example 2:

@Lob @Basic(fetch=LAZY)
@Column(name="EMP_PIC", columnDefinition="BLOB NOT NULL")
protected byte[] pic;

11.1.30. ManyToMany Annotation

A ManyToMany annotation defines a
many-valued association with many-to-many multiplicity. If the
collection is defined using generics to specify the element type, the
associated target entity class does not need to be specified; otherwise
it must be specified.

Every many-to-many association has two sides,
the owning side and the non-owning, or inverse, side. If the association
is bidirectional, either side may be designated as the owning side. If
the relationship is bidirectional, the non-owning side must use the
mappedBy element of the ManyToMany annotation to specify the
relationship field or property of the owning side.

The join table for the relationship, if not
defaulted, is specified on the owning side.

The ManyToMany annotation may be used
within an embeddable class contained within an entity class to specify a
relationship to a collection of entities[120]. If the
relationship is bidirectional and the entity containing the embeddable
class is the owner of the relationship, the non-owning side must use the
mappedBy element of the ManyToMany annotation to specify the
relationship field or property of the embeddable class. The dot ("."
) notation syntax must be used in the mappedBy element to indicate the
relationship attribute within the embedded attribute. The value of each
identifier used with the dot notation is the name of the respective
embedded field or property.

Table 27 lists these annotation elements that may be
specified for the ManyToMany annotation and their default values.

The cascade element specifies the set of
cascadable operations that are propagated to the associated entity. The
operations that are cascadable are defined by the CascadeType enum:

public enum CascadeType {ALL, PERSIST, MERGE, REMOVE, REFRESH, DETACH};

The value cascade=ALL is equivalent to
cascade={PERSIST, MERGE, REMOVE, REFRESH, DETACH}.

When the collection is a java.util.Map,
the cascade element applies to the map value.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface ManyToMany {
 Class targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default LAZY;
 String mappedBy() default "";
}

The EAGER strategy is a requirement on the
persistence provider runtime that the associated entity must be eagerly
fetched. The LAZY strategy is a hint to the persistence provider
runtime that the associated entity should be fetched lazily when it is
first accessed. The implementation is permitted to eagerly fetch
associations for which the LAZY strategy hint has been specified.

Table 27. ManyToMany Annotation Elements

Type
Name
Description
Default

Class

targetEntity

(Optional) The entity class that is the
target of the association. Optional only if the collection-valued
relationship property is defined using Java generics. Must be specified
otherwise.

The parameterized type of the collection when defined using generics.

CascadeType[]

cascade

(Optional) The operations that must be
cascaded to the target of the association.

No operations are cascaded.

FetchType

fetch

(Optional) Whether the association should be
lazily loaded or must be eagerly fetched. The EAGER strategy is a
requirement on the persistence provider runtime that the associated
entities must be eagerly fetched. The LAZY strategy is a hint to the
persistence provider runtime.

LAZY

String

mappedBy

The field or property that owns the
relationship. Required unless the relationship is unidirectional.

Example 1:

In Customer class:

@ManyToMany
@JoinTable(name="CUST_PHONES")
public Set<PhoneNumber> getPhones() { return phones; }

In PhoneNumber class:

@ManyToMany(mappedBy="phones")
public Set<Customer> getCustomers() { return customers; }

Example 2:

In Customer class:

@ManyToMany(targetEntity=com.acme.PhoneNumber.class)
public Set getPhones() { return phones; }

In PhoneNumber class:

@ManyToMany(targetEntity=com.acme.Customer.class, mappedBy="phones")
public Set getCustomers() { return customers; }

Example 3:

In Customer class:

@ManyToMany
@JoinTable(
 name="CUST_PHONE",
 joinColumns=@JoinColumn(name="CUST_ID", referencedColumnName="ID"),
 inverseJoinColumns=@JoinColumn(name="PHONE_ID",referencedColumnName="ID")
)
public Set<PhoneNumber> getPhones() { return phones; }

In PhoneNumberClass:

@ManyToMany(mappedBy="phones")
public Set<Customer> getCustomers() { return customers; }

Example 4:

Embeddable class used by the Employee entity
specifies a many-to-many relationship.

@Entity
public class Employee {
 @Id
 int id;

 @Embedded
 ContactInfo contactInfo;

 // ...
}

@Embeddable
public class ContactInfo {
 @ManyToOne
 Address address; // Unidirectional

 @ManyToMany
 List<PhoneNumber> phoneNumbers; // Bidirectional
}

@Entity
public class PhoneNumber {
 @Id
 int phNumber;

 @ManyToMany(mappedBy="contactInfo.phoneNumbers")
 Collection<Employee> employees;
}

11.1.31. ManyToOne Annotation

The ManyToOne annotation defines a
single-valued association to another entity class that has many-to-one
multiplicity. It is not normally necessary to specify the target entity
explicitly since it can usually be inferred from the type of the object
being referenced.

The ManyToOne annotation may be used within
an embeddable class to specify a relationship from the embeddable class
to an entity class. If the relationship is bidirectional, the non-owning
OneToMany entity side must use the mappedBy element of the
OneToMany annotation to specify the relationship field or property of
the embeddable field or property on the owning side of the relationship.
The dot (“.”) notation syntax must be used in the mappedBy element
to indicate the relationship attribute within the embedded attribute.
The value of each identifier used with the dot notation is the name of
the respective embedded field or property.

Table 28 lists the annotation elements that may be specified
for the ManyToOne annotation and their default values.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface ManyToOne {
 Class targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default EAGER;
 boolean optional() default true;
}

The operations that can be cascaded are
defined by the CascadeType enum, defined in Section 11.1.30.

The EAGER strategy is a requirement on the
persistence provider runtime that the associated entity must be eagerly
fetched. The LAZY strategy is a hint to the persistence provider
runtime that the associated entity should be fetched lazily when it is
first accessed. The implementation is permitted to eagerly fetch
associations for which the LAZY strategy hint has been specified.

Table 28. ManyToOne Annotation Elements

Type
Name
Description
Default

Class

targetEntity

(Optional) The entity class that is the target of the association.

The type of the field or property that stores the association.

CascadeType[]

cascade

(Optional) The operations that must be
cascaded to the target of the association.

No operations are cascaded.

FetchType

fetch

(Optional) Whether the association should be
lazily loaded or must be eagerly fetched. The EAGER strategy is a
requirement on the persistence provider runtime that the associated
entity must be eagerly fetched. The LAZY strategy is a hint to the
persistence provider runtime.

EAGER

boolean

optional

(Optional) Whether the association is
optional. If set to false then a non-null relationship must always
exist.

true

Example 1:

@ManyToOne(optional=false)
@JoinColumn(name="CUST_ID", nullable=false, updatable=false)
public Customer getCustomer() { return customer; }

Example 2:

@Entity
public class Employee {
 @Id
 int id;

 @Embedded
 JobInfo jobInfo;

 // ...
}

@Embeddable
public class JobInfo {
 String jobDescription;

 @ManyToOne
 ProgramManager pm; // Bidirectional
}

@Entity
public class ProgramManager {
 @Id
 int id;

 @OneToMany(mappedBy="jobInfo.pm")
 Collection<Employee> manages;
}

11.1.32. MapKey Annotation

The MapKey annotation is used to specify
the map key for associations of type java.util.Map when the map key is
itself the primary key or a persistent field or property of the entity
that is the value of the map.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface MapKey {
 String name() default "";
}

The name element designates the name of the
persistent field or property of the associated entity that is used as
the map key. If the name element is not specified, the primary key of
the associated entity is used as the map key. If the primary key is a
composite primary key and is mapped as IdClass, an instance of the
primary key class is used as the key.

If a persistent field or property other than
the primary key is used as a map key, it is expected to be unique within
the context of the relationship.

The MapKeyClass annotation is not used when
MapKey is specified and vice versa.

Table 29 lists the annotation elements that may be specified
for the MapKey annotation.

Table 29. MapKey Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the persistent field or property that is used as the map key.

The primary key is used as the map key.

Example 1:

@Entity
public class Department {

 // ...

 @OneToMany(mappedBy="department")
 @MapKey // map key is primary key
 public Map<Integer, Employee> getEmployees() { ... }

 // ...
}

@Entity
public class Employee {

 // ...

 @Id public Integer getEmpId() { ... }
 @ManyToOne
 @JoinColumn(name="dept_id")
 public Department getDepartment() { ... }

 // ...
}

Example 2:

@Entity
public class Department {
 // ...

 @OneToMany(mappedBy="department")
 @MapKey(name="name")
 public Map<String, Employee> getEmployees() { ... }

 // ...
}

@Entity
public class Employee {
 @Id
 public Integer getEmpId() { ... }

 // ...

 public String getName() { ... }

 // ...

 @ManyToOne
 @JoinColumn(name="dept_id")
 public Department getDepartment() { ... }

 // ...
}

11.1.33. MapKeyClass Annotation

The MapKeyClass annotation is used to
specify the type of the map key for associations of type java.util.Map
. The map key can be a basic type, an embeddable class, or an entity. If
the map is specified using Java generics, the MapKeyClass annotation
and associated type need not be specified; otherwise they must be
specified.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface MapKeyClass {
 Class value();
}

The MapKeyClass annotation is used in
conjunction with ElementCollection or one of the collection-valued
relationship annotations (OneToMany or ManyToMany).

The MapKey annotation is not used when
MapKeyClass is specified and vice versa.

Table 30 lists the annotation elements that may
be specified for the MapKeyClass annotation.

Table 30. MapKeyClass Annotation Elements

Type
Name
Description
Default

Class

value

(Required) The type of the map key.

Example 1:

@Entity
public class Item {
 @Id
 int id;

 // ...

 @ElementCollection(targetClass=String.class)
 @MapKeyClass(String.class)
 Map images; // map from image name to image filename

 // ...
}

Example 2:

// MapKeyClass and target type of relationship can be defaulted
@Entity
public class Item {
 @Id
 int id;

 // ...

 @ElementCollection
 Map<String, String> images;

 // ...
}

Example 3:

@Entity
public class Company {
 @Id
 int id;

 // ...

 @OneToMany(targetEntity=com.example.VicePresident.class)
 @MapKeyClass(com.example.Division.class)
 Map organization;
}

Example 4:

// MapKeyClass and target type of relationship are defaulted
@Entity
public class Company {
 @Id
 int id;

 // ...

 @OneToMany
 Map<Division, VicePresident> organization;
}

11.1.34. MapKeyColumn Annotation

The MapKeyColumn annotation is used to
specify the mapping for the key column of a map whose map key is a basic
type. If the name element is not specified, it defaults to the
concatenation of the following: the name of the referencing relationship
field or property; “_”; “KEY”.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface MapKeyColumn {
 String name() default "";
 boolean unique() default false;
 boolean nullable() default false;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 int length() default 255;
 int precision() default 0; // decimal precision
 int scale() default 0; // decimal scale
}

If no MapKeyColumn annotation is specified,
the default values in Table 31 apply.

Table 31. MapKeyColumn Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the map key column.
The table in which it is found depends upon the context. If the map key
is for an element collection, the map key column is in the collection
table for the map value. If the map key is for a ManyToMany entity
relationship or for a OneToMany entity relationship using a join table,
the map key column is in a join table. If the map key is for a OneToMany
entity relationship using a foreign key mapping strategy, the map key
column is in the table of the entity that is the value of the map.

The concatenation of the following: the name
of the referencing property or field name; " _ "; " KEY ".

boolean

unique

(Optional) Whether the column is a unique
key. This is a shortcut for the UniqueConstraint annotation at the table
level and is useful for when the unique key constraint corresponds to
only a single column. This constraint applies in addition to any
constraint entailed by primary key mapping and to constraints specified
at the table level.

false

boolean

nullable

(Optional) Whether the database column is nullable.

true

boolean

insertable

(Optional) Whether the column is included in
SQL INSERT statements generated by the persistence provider.

true

boolean

updatable

(Optional) Whether the column is included in
SQL UPDATE statements generated by the persistence provider.

true

String

columnDefinition

(Optional) The SQL fragment that is used when generating the DDL for the column.

Generated SQL to create a column of the inferred type.

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

String

table

(Optional) The name of the table that contains the column.

If the map key is for
an element collection, the name of the collection table for the map
value. If the map key is for a OneToMany or ManyToMany entity
relationship using a join table, the name of the join table for the map.
If the map key is for a OneToMany entity relationship using a foreign
key mapping strategy, the name of the primary table of the entity that
is the value of the map.

int

length

(Optional) The column length

 Applies only to columns whose type is parameterized by length,
 for example, varchar or varbinary types.

255

int

precision

(Optional) The precision of a column of SQL type decimal or
numeric, or of similar database-native type.

 Applies only to columns of exact numeric type.

 The default value 0 indicates that a provider-determined
 precision should be inferred.

0

int

scale

(Optional) The scale of a column of SQL type decimal or
numeric, or of similar database-native type.

 Applies only to columns of exact numeric type.

 The default value 0 indicates that a provider-determined
 scale should be inferred.

0

Example:

@Entity
public class Item {
 @Id
 int id;

 // ...

 @ElementCollection
 @MapKeyColumn(name="IMAGE_NAME")
 @Column(name="IMAGE_FILENAME")
 @CollectionTable(name="IMAGE_MAPPING")
 Map<String, String> images; // map from image name to filename

 // ...
}

11.1.35. MapKeyEnumerated Annotation

The MapKeyEnumerated annotation is used to
specify the enum type for a map key whose basic type is an enumerated
type.

The MapKeyEnumerated annotation can be
applied to an element collection or relationship of type java.util.Map
, in conjunction with the ElementCollection, OneToMany, or
ManyToMany annotation. If the map is specified using Java generics,
the MapKeyClass annotation and associated type need not be specified;
otherwise they must be specified.

If the enumerated type is not specified or
the MapKeyEnumerated annotation is not used, the enumerated type is
assumed to be ORDINAL.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface MapKeyEnumerated {
 EnumType value() default ORDINAL;
}

Table 32 lists the annotation elements that
may be specified for the MapKeyEnumerated annotation and their default
values. The EnumType enum is defined in Section 11.1.18.

Table 32. MapKeyEnumerated Annotation Elements

Type
Name
Description
Default

EnumType

value

(Optional) The type used in mapping an enum type.

ORDINAL

11.1.36. MapKeyJoinColumn Annotation

The MapKeyJoinColumn annotation is used to
specify a mapping to an entity that is a map key. The map key join
column is in the collection table, join table, or table of the target
entity that is used to represent the map.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(MapKeyJoinColumns.class)
public @interface MapKeyJoinColumn {
 String name() default "";
 String referencedColumnName() default "";
 boolean unique() default false;
 boolean nullable() default false;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
}

Table 33 lists the annotation elements that
may be specified for the MapKeyJoinColumn annotation and their default
values.

If no MapKeyJoinColumn annotation is
specified, a single join column is assumed and the default values
described below (and in Table 33) apply.

The name annotation element defines the
name of the foreign key column. The remaining annotation elements (other
than referencedColumnName) refer to this column.
