# 11. Metadata for Object/Relational Mapping (part 1/4)

The object/relational mapping metadata is
part of the application domain model contract. It expresses requirements
and expectations on the part of the application as to the mapping of the
entities and relationships of the application domain to a database.
Queries (and, in particular, SQL queries) written against the database
schema that corresponds to the application domain model are dependent
upon the mappings expressed by means of the object/relational mapping
metadata. The implementation of this specification must assume this
application dependency upon the object/relational mapping metadata and
insure that the semantics and requirements expressed by that mapping are
observed.

The use of object/relational mapping metadata
to control schema generation is specified in Section 11.2.

11.1. Annotations for Object/Relational Mapping

These annotations and types are in the
package jakarta.persistence.

XML metadata may be used as an alternative to
these annotations, or to override or augment annotations, as described
in Chapter 12.

11.1.1. Access Annotation

The Access annotation is used to specify an
access type to be applied to an entity class, mapped superclass, or
embeddable class, or to a specific attribute of such a class.

@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
public @interface Access {
 AccessType value();
}

Table 4 lists the annotation elements that may be specified
for the Access annotation.

Table 4. Access Annotation Elements

Type
Name
Description
Default

AccessType

value

(Required) The access type to be applied to the class or attribute.

11.1.2. AssociationOverride Annotation

The AssociationOverride annotation is used
to override a mapping for an entity relationship.

The AssociationOverride annotation may be
applied to an entity that extends a mapped superclass to override a
relationship mapping defined by the mapped superclass. If the
AssociationOverride annotation is not specified, the association is
mapped the same as in the original mapping. When used to override a
mapping defined by a mapped superclass, the AssociationOverride
annotation is applied to the entity class.

The AssociationOverride annotation may be
used to override a relationship mapping from an embeddable within an
entity to another entity when the embeddable is on the owning side of
the relationship. When used to override a relationship mapping defined
by an embeddable class (including an embeddable class embedded within
another embeddable class), the AssociationOverride annotation is
applied to the field or property containing the embeddable.

When the AssociationOverride
annotation is used to override a relationship mapping from an embeddable
class, the name element specifies the referencing relationship field
or property within the embeddable class. To override mappings at
multiple levels of embedding, a dot (".") notation syntax must be used
in the name element to indicate an attribute within an embedded
attribute. The value of each identifier used with the dot notation is
the name of the respective embedded field or property. When the
AssociationOverride annotation is applied to override the mappings of
an embeddable class used as a map value, " value. " must be used to
prefix the name of the attribute within the embeddable class that is
being overridden in order to specify it as part of the map
value.[106]

If the relationship mapping is a
foreign key mapping, the joinColumns element of the
AssociationOverride annotation is used. If the relationship mapping
uses a join table, the joinTable element of the AssociationOverride
element must be specified to override the mapping of the join table
and/or its join columns.[107]

The joinColumns element refers to the table
for the class that contains the annotation.

The foreignKey element is used to specify
or control the generation of a foreign key constraint for the columns
corresponding to the joinColumns element when table generation is in
effect. If both this element and the foreignKey element of any of the
joinColumns elements are specified, the behavior is undefined.

@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(AssociationOverrides.class)
public @interface AssociationOverride {
 String name();

 JoinColumn[] joinColumns() default {};

 ForeignKey foreignKey() default

 @ForeignKey(PROVIDER_DEFAULT);
 JoinTable joinTable() default @JoinTable;
}

Table 5 lists the annotation elements
that may be specified for the AssociationOverride annotation.

Table 5. AssociationOverride Annotation Elements

Type
Name
Description
Default

String

name

(Required) The name of the relationship
property whose mapping is being overridden if property-based access is
being used, or the name of the relationship field if field-based access
is used.

JoinColumn[]

joinColumns

The
join column(s) being mapped to the persistent attribute(s). The
joinColumns element must be specified if a foreign key mapping is used
in the overriding of the mapping of the relationship. The joinColumns
element must not be specified if a join table is used in the overriding
of the mapping of the relationship

ForeignKey

foreignKey

(Optional) The foreign key constraint
specification for the join columns. This is used only if table
generation is in effect.

Provider’s default

JoinTable

joinTable

The
join table that maps the relationship. The joinTable element must be
specified if a join table is used in the overriding of the mapping of
the relationship. The joinTable element must not be specified if a
foreign key mapping is used in the overriding of the mapping of the
relationship.

.

Example 1:

@MappedSuperclass
public class Employee {
 @Id
 protected Integer id;

 @Version
 protected Integer version;

 @ManyToOne
 protected Address address;

 public Integer getId() { ... }

 public void setId(Integer id) { ... }

 public Address getAddress() { ... }

 public void setAddress(Address address) { ... }
}

@Entity
@AssociationOverride(name="address", joinColumns=@JoinColumn(name="ADDR_ID"))
public class PartTimeEmployee extends Employee {
 // address field mapping overridden to ADDR_ID foreign key
 @Column(name="WAGE")
 protected Float hourlyWage;

 public Float getHourlyWage() { ... }

 public void setHourlyWage(Float wage) { ... }
}

Example 2: Overriding of the mapping for
the phoneNumbers relationship defined in the ContactInfo embeddable
class.

@Entity
public class Employee {
 @Id
 int id;

 @AssociationOverride(
 name="phoneNumbers",
 joinTable=@JoinTable(
 name="EMPPHONES",
 joinColumns=@JoinColumn(name="EMP"),
 inverseJoinColumns=@JoinColumn(name="PHONE")
 )
 )
 @Embedded
 ContactInfo contactInfo;

 // ...
}

@Embeddable
public class ContactInfo {
 @ManyToOne Address address; // Unidirectional
 @ManyToMany(targetEntity=PhoneNumber.class)
 List phoneNumbers;
}

@Entity
public class PhoneNumber {
 @Id
 int number;

 @ManyToMany(mappedBy="contactInfo.phoneNumbers")
 Collection<Employee> employees;
}

11.1.3. AssociationOverrides Annotation

The mappings of multiple relationship
properties or fields may be overridden. The AssociationOverrides
annotation can be used for this purpose.

@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
public @interface AssociationOverrides {
 AssociationOverride[] value();
}

Table 6 lists the annotation elements
that may be specified for the AssociationOverrides annotation.

Table 6. AssociationOverrides Annotation Elements

Type
Name
Description
Default

AssociationOverride[]

value

(Required)
The association override mappings that are to be applied to the
relationship field or property.

Example:

@MappedSuperclass
public class Employee {
 @Id
 protected Integer id;

 @Version
 protected Integer version;

 @ManyToOne
 protected Address address;

 @OneToOne
 protected Locker locker;

 public Integer getId() { ... }

 public void setId(Integer id) { ... }

 public Address getAddress() { ... }

 public void setAddress(Address address) { ... }

 public Locker getLocker() { ... }

 public void setLocker(Locker locker) { ... }
}

@Entity
@AssociationOverrides({
 @AssociationOverride(name="address", joinColumns=@JoinColumn("ADDR_ID")),
 @AssociationOverride(name="locker", joinColumns=@JoinColumn("LCKR_ID"))})
public PartTimeEmployee { ... }

Alternatively:

@Entity
@AssociationOverride(name="address", joinColumns=@JoinColumn("ADDR_ID"))
@AssociationOverride(name="locker", joinColumns=@JoinColumn("LCKR_ID"))
public PartTimeEmployee { ... }

11.1.4. AttributeOverride Annotation

The AttributeOverride annotation is used to
override the mapping of a Basic (whether explicit or default) property
or field or Id property or field.

The AttributeOverride annotation may be
applied to an entity that extends a mapped superclass or to an embedded
field or property to override a Basic mapping or Id mapping defined
by the mapped superclass or embeddable class (or embeddable class of one
of its attributes).

The AttributeOverride annotation may be
applied to an element collection containing instances of an embeddable
class or to a map collection whose key and/or value is an embeddable
class. When the AttributeOverride annotation is applied to a map, "
key. " or " value. " must be used to prefix the name of the
attribute that is being overridden in order to specify it as part of the
map key or map value.

To override mappings at multiple levels of
embedding, a dot (".") notation form must be used in the name element
to indicate an attribute within an embedded attribute. The value of each
identifier used with the dot notation is the name of the respective
embedded field or property.

If the AttributeOverride annotation is not
specified, the column is mapped the same as in the original mapping.

Table 7 lists the annotation elements
that may be specified for the AttributeOverride annotation.

The column element refers to the table for
the class that contains the annotation.

@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(AttributeOverrides.class)
public @interface AttributeOverride {
 String name();
 Column column();
}

Table 7. AttributeOverride Annotation Elements

Type
Name
Description
Default

String

name

(Required) The name of the property whose
mapping is being overridden if property-based access is being used, or
the name of the field if field-based access is used.

Column

column

(Required) The column that is being mapped
to the persistent attribute. The mapping type will remain the same as is
defined in the embeddable class or mapped superclass.

Example 1:

@MappedSuperclass
public class Employee {
 @Id
 protected Integer id;

 @Version
 protected Integer version;

 protected String address;

 public Integer getId() { ... }

 public void setId(Integer id) { ... }

 public String getAddress() { ... }

 public void setAddress(String address) { ... }
}

@Entity
@AttributeOverride(name="address", column=@Column(name="ADDR"))
public class PartTimeEmployee extends Employee {
 // address field mapping overridden to ADDR
 protected Float wage();

 public Float getHourlyWage() { ... }

 public void setHourlyWage(Float wage) { ... }
}

Example 2:

@Embeddable public class Address {
 protected String street;

 protected String city;

 protected String state;

 @Embedded
 protected Zipcode zipcode;
}

@Embeddable
public class Zipcode {
 protected String zip;
 protected String plusFour;
}

@Entity
public class Customer {
 @Id
 protected Integer id;

 protected String name;

 @AttributeOverride(name="state", column=@Column(name="ADDR_STATE"))
 @AttributeOverride(name="zipcode.zip", column= @Column(name="ADDR_ZIP"))
 @Embedded
 protected Address address;

 // ...
}

Example 3:

@Entity
public class PropertyRecord {
 @EmbeddedId
 PropertyOwner owner;

 @AttributeOverrides(name="key.street", column=@Column(name="STREET_NAME"))
 @AttributeOverride(name="value.size", column=@Column(name="SQUARE_FEET"))
 @AttributeOverride(name="value.tax", column=@Column(name="ASSESSMENT"))
 @ElementCollection
 Map<Address, PropertyInfo> parcels;
}

@Embeddable
public class PropertyInfo {
 Integer parcelNumber;
 Integer size;
 BigDecimal tax;
}

11.1.5. AttributeOverrides Annotation

The mappings of multiple properties or fields
may be overridden. The AttributeOverrides annotation can be used for
this purpose.

@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
public @interface AttributeOverrides {
 AttributeOverride[] value();
}

Table 8 lists the annotation elements
that may be specified for the AttributeOverrides annotation.

Table 8. AttributeOverrides Annotation Elements

Type
Name
Description
Default

AttributeOverride[]

value

(Required)
The AttributeOverride mappings that are to be applied to the field or
property.

Example:

@Embedded
@AttributeOverrides({
 @AttributeOverride(name="startDate", column=@Column(name="EMP_START")),
 @AttributeOverride(name="endDate", column=@Column(name="EMP_END"))
})
public EmploymentPeriod getEmploymentPeriod() { ... }

11.1.6. Basic Annotation

The Basic annotation is the simplest type of mapping to a database column.
The Basic annotation may be applied to any persistent property or instance
variable whose type is one of the basic types listed in Section 2.6.

For the types listed above, the persistence provider must support mappings
to the column types listed in tables B-2 and B-4 of Appendix B of the
JDBC 4.3 specification. See [3]. In addition, the provider must
support mapping:

java.time.Instant to the JDBC TIMESTAMP or TIMESTAMP_WITH_TIMEZONE type,

java.time.Year to the JDBC INTEGER and SMALLINT types,

java.math.BigInteger and java.math.BigDecimal to the JDBC NUMERIC and DECIMAL types,

java.util.UUID to the JDBC CHAR and VARCHAR types, and

char[] to the JDBC CHAR, NCHAR, VARCHAR, NVARCHAR, LONGVARCHAR,
and LONGNVARCHAR types.

As described in Section 2.10, the use of the Basic
annotation is optional for persistent fields and properties of the
types listed above. If the Basic annotation is not specified for such a field or
property, the default values of the Basic annotation will apply.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Basic {
 FetchType fetch() default EAGER;
 boolean optional() default true;
}

Table 9 lists the annotation elements that may be specified
for the Basic annotation and their default values.

The FetchType enum defines strategies for
fetching data from the database:

public enum FetchType { LAZY, EAGER };

The EAGER strategy is a requirement on the
persistence provider runtime that data must be eagerly fetched. The
LAZY strategy is a hint to the persistence provider runtime that
data should be fetched lazily when it is first accessed. The
implementation is permitted to eagerly fetch data for which the LAZY
strategy hint has been specified. In particular, lazy fetching might
only be available for Basic mappings for which property-based access
is used.

The optional element is a hint as to
whether the value of the field or property may be null. It is
disregarded for primitive types.

Table 9. Basic Annotation Elements

Type
Name
Description
Default

FetchType

fetch

(Optional) Whether the value of the field or
property should be lazily loaded or must be eagerly fetched. The EAGER
strategy is a requirement on the persistence provider runtime that the
value must be eagerly fetched. The LAZY strategy is a hint to the
persistence provider runtime.

EAGER

boolean

optional

(Optional) Whether the value of the field or
property may be null. This is a hint and is disregarded for primitive
types; it may be used in schema generation.

true

Example 1:

@Basic
protected String name;

Example 2:

@Basic(fetch=LAZY)
protected String getName() { return name; }

If the persistence provider stores a value of type java.util.UUID in
a column of type VARCHAR or equivalent, the value must be stored in
its canonical representation, unless the application explicitly indicates
that some other representation is preferred.

11.1.7. Cacheable Annotation

The Cacheable annotation
specifies whether an entity should be cached if caching is enabled when
the value of the persistence.xml shared-cache-mode element is
ENABLE_SELECTIVE or DISABLE_SELECTIVE. The value of the Cacheable
annotation is inherited by subclasses; it can be overridden by
specifying Cacheable on a subclass.

@Target({TYPE})
@Retention(RUNTIME)
public @interface Cacheable {
 boolean value() default true;
}

Cacheable(false) means that the entity and
its state must not be cached by the provider.

If the shared-cache-mode element is not
specified in the persistence.xml file and the
jakarta.persistence.sharedCache.mode property is not specified when the
entity manager factory for the persistence unit is created, the
semantics of the Cacheable annotation are undefined.

Table 10. Cacheable Annotation Elements

Type
Name
Description
Default

boolean

value

(Optional) Whether or not the entity should
be cached.

true

11.1.8. CollectionTable Annotation

The CollectionTable annotation is used in
the mapping of collections of basic or embeddable types. The
CollectionTable annotation specifies the table that is used for the
mapping of the collection and is specified on the collection-valued
field or property.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface CollectionTable {
 String name() default "";
 String catalog() default "";
 String schema() default "";
 JoinColumn[] joinColumns() default {};
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
 UniqueConstraint[] uniqueConstraints() default {};
 Index[] indexes() default {};
 String options() default "";
}

By default, the columns of the collection
table that correspond to the embeddable class or basic type are derived
from the attributes of the embeddable class or from the basic type
according to the default values of the Column annotation, as described
in Section 11.1.9. In the
case of a basic type, the column name is derived from the name of the
collection-valued field or property. In the case of an embeddable class,
the column names are derived from the field or property names of the
embeddable class.

To override the default properties of the
column used for a basic type, the Column annotation is used on the
collection-valued attribute in addition to the ElementCollection
annotation. The value of the table element of the Column annotation
defaults to the name of the collection table.

To override these defaults for an embeddable
class, the AttributeOverride and/or AttributeOverrides annotations
must be used in addition to the ElementCollection annotation. The
value of the table element of the Column annotation used in the
AttributeOverride annotation defaults to the name of the collection
table. If the embeddable class contains references to other entities,
the default values for the columns corresponding to those references may
be overridden by means of the AssociationOverride and/or
AssociationOverrides annotations.

The foreignKey element is used to specify
or control the generation of a foreign key constraint for the columns
corresponding to the joinColumns element when table generation is in
effect. If both this element and the foreignKey element of any of the
joinColumns elements are specified, the behavior is undefined. If no
foreignKey annotation element is specified in either location, the
persistence provider’s default foreign key strategy will apply.

If the CollectionTable annotation is
missing, the default values of the CollectionTable annotation elements
apply.

This annotation may not be applied to a persistent field or property not
annotated @ElementCollection.

Table 11 lists the annotation elements that
may be specified for the CollectionTable annotation and their default
values.

Table 11. CollectionTable Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the collection table.

The concatenation of the name of the
containing entity and the name of the collection attribute, separated by
an underscore.

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
collection table which reference the primary table of the entity.

(Default only applies if a single join
column is used.) The same defaults as for JoinColumn (i.e., the
concatenation of the following: the name of the entity; “_”; the name of
the referenced primary key column.) However, if there is more than one
join column, a JoinColumn annotation must be specified for each join
column using the JoinColumns annotation. Both the name and the
referencedColumnName elements must be specified in each such JoinColumn
annotation.

ForeignKey

foreignKey

(Optional) The foreign key constraint
specification for the join columns. This is used only if table
generation is in effect.

Provider’s default

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

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

Example:

@Embeddable
public class Address {
 protected String street;
 protected String city;
 protected String state;

 // ...
}

@Entity public class Person {
 @Id
 protected String ssn;

 protected String name;

 protected Address home;

 // ...

 @ElementCollection // use default table (PERSON_NICKNAMES)
 @Column(name="name", length=50)
 protected Set<String> nickNames = new HashSet();

 // ...
}

@Entity
public class WealthyPerson extends Person {
 @ElementCollection
 @CollectionTable(name="HOMES") // use default join column name
 @AttributeOverrides({
 @AttributeOverride(name="street", column=@Column(name="HOME_STREET")),
 @AttributeOverride(name="city", column=@Column(name="HOME_CITY")),
 @AttributeOverride(name="state", column=@Column(name="HOME_STATE"))
 })
 protected Set<Address> vacationHomes = new HashSet();

 // ...
}

11.1.9. Column Annotation

The Column annotation is used to specify a
mapped column for a persistent property or field.

Table 12 lists the annotation elements that may be specified
for the Column annotation and their default values.

If no Column annotation is specified, the
default values in Table 12 apply.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Column {
 String name() default "";
 boolean unique() default false;
 boolean nullable() default true;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 int length() default 255;
 int precision() default 0; // decimal precision
 int scale() default 0; // decimal scale
 int secondPrecision() default -1; //fractional second precision
 CheckConstraint[] check() default {}
 String comment() default "";
}

Table 12. Column Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of the column.

The property or field name.

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

(Optional) The SQL fragment that is used
when generating the DDL for the column.

 The specified DDL must be written in the native SQL dialect of
 the target database, and is not portable across databases.

Generated SQL to create a column of the
inferred type.

String

options

(Optional) A SQL fragment appended to the generated DDL.

 The specified DDL must be written in the native SQL dialect of
 the target database, and is not portable across databases.

Nothing appended.

String

table

(Optional) The name of the table that
contains the column. If absent the column is assumed to be in the
primary table for the mapped object.

Column
is in primary table.

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

int

secondPrecision

(Optional) The number of decimal digits to use for storing
 fractional seconds in a SQL time or timestamp column.

 Applies only to columns of time or timestamp type.

 The default value -1 indicates that fractional seconds
 should not be stored in a time column, or that the maximum
 number of digits supported by the database and JDBC driver
 should be stored in a timestamp column.

-1

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

Portable applications which make use of schema generation should
explicitly specify the precision and scale of columns of type
numeric or decimal.

Example 1:

@Column(name="DESC", nullable=false, length=512)
public String getDescription() {
 return description;
}

Example 2:

@Column(name="DESC", columnDefinition="CLOB NOT NULL", table="EMP_DETAIL")
@Lob
public String getDescription() {
 return description;
}

Example 3:

@Column(name="ORDER_COST", updatable=false, precision=12, scale=2)
public BigDecimal getCost() {
 return cost;
}

11.1.10. Convert Annotation

The Convert annotation specifies how the values of a field or property
are converted to a basic type, enabling a converter which was defined
autoApply=false, overriding the use of a converter which was defined
autoApply=true (see Section 10.6), or overriding the use of a converter
specified by a field or property of an embedded type or inherited mapped
superclass.

When persistent properties are used, the Convert annotation is applied
to the getter method.

It is not necessary to use the Basic annotation or corresponding XML
element to specify the converted basic type. Nor is it usually necessary
to explicitly specify the converter class, except to disambiguate cases
where multiple converters would otherwise apply.

The Convert annotation may be applied to an entity that extends a
mapped superclass to specify or override the conversion mapping for
an inherited basic attribute.

@Target({METHOD, FIELD, TYPE})
@Retention(RUNTIME)
@Repeatable(Converts.class)
public @interface Convert {
 Class converter() default void.class;
 String attributeName() default "";
 boolean disableConversion() default false;
}

Table 13 lists the annotation elements that may be specified for the
Convert annotation.

Table 13. Convert Annotation Elements

Type
Name
Description
Default

Class

converter

(Optional) The converter to be applied.

No converter

String

attributeName

(Optional) The name of the attribute to
convert. Must be specified unless the Convert annotation is applied to
an attribute of basic type or to an element collection of basic type.
Must not be specified otherwise.

The basic
attribute or basic element collection attribute to which the annotation
is applied

boolean

disableConversion

(Optional) Whether conversion of the attribute is to be disabled.

false

The converter element specifies the converter that is applied. Even if
an automatically-applied converter would otherwise be applicable to the
annotated field or property, the converter specified by the converter
element must be applied instead.

The disableConversion element specifies that any automatically-applied
converter that would otherwise be applicable to the given field or property
must not be applied.

If neither the converter element nor the disableConversion element is
specified, and there is exactly one converter for the type of the annotated
field or property, that converter is applied, even if it is not an
automatically-applied converter.

If multiple converters are applicable to the annotated field or property,
and the converter element is not specified, the behavior is undefined.

The Convert annotation should not be used to specify conversion of
id attributes, (including the attributes of embedded ids and derived
identities), of version attributes, of relationship attributes, or of
attributes explicitly annotated (or designated via XML) as Enumerated
or Temporal. Applications that depend on such conversions are not
portable.

The Convert annotation may be applied to:

a basic attribute, or

a collection attribute (that is, an ElementCollection) of any
type other than Map, in which case the converter is applied to
the elements of the collection.

In these cases, the attributeName element must not be specified.

Alternatively, the Convert annotation may be applied to:

an embedded attribute,

a collection attribute (that is, an ElementCollection) whose
element type is an embeddable type, in which case the converter
is applied to the specified attribute of the embeddable instances
contained in the collection,

a map collection attribute (that is, an ElementCollection of
type Map), in which case the converter is applied to the keys or
values of the map, or to the specified attribute of the embeddable
instances contained in the map, or

an entity class which extends a mapped superclass, to enable or
override conversion of an inherited basic or embedded attribute.

In these cases, the attributeName element must be specified.

To override conversion mappings at multiple levels of embedding, a dot
(“.”) notation form must be used in the attributeName element to
indicate an attribute within an embedded attribute. The value of each
identifier used with the dot notation is the name of the respective
embedded field or property.

The dot notation may also be used with map entries:

When the Convert annotation is applied to a map to specify conversion
of a map key or value of basic type, "key" or "value", respectively,
must be used as the value of the attributeName element to specify that
it is the map key or value that is converted.

When the Convert annotation is applied to a map whose key or value type
is an embeddable type, the attributeName element must be specified, and
"key." or "value." (respectively) must be used to prefix the name of
the attribute of the key or value type that is converted.

Example 1: Convert a basic attribute

@Converter
public class BooleanToIntegerConverter implements AttributeConverter<Boolean, Integer> { ... }

@Entity
public class Employee {
 @Id
 long id;

 @Convert(converter=BooleanToIntegerConverter.class)
 boolean fullTime;

 // ...
}

Example 2: Auto-apply conversion of a basic attribute

@Converter(autoApply=true)
public class EmployeeDateConverter implements
 AttributeConverter<com.acme.EmployeeDate, java.sql.Date> { ... }

@Entity
public class Employee {
 @Id
 long id;

 // ...

 // EmployeeDateConverter is applied automatically
 EmployeeDate startDate;
}

Example 3: Disable conversion in the presence of an auto-apply converter

@Convert(disableConversion=true)
EmployeeDate lastReview;

Example 4: Apply a converter to an element collection of basic type

@ElementCollection
// applies to each element in the collection
@Convert(converter=NameConverter.class)
List<String> names;

Example 5: Apply a converter to an element collection that is a map of basic values. The converter is applied to the map value.

@ElementCollection
@Convert(converter=EmployeeNameConverter.class)
Map<String, String> responsibilities;

Example 6: Apply a converter to a map key of basic type

@OneToMany
@Convert(converter=ResponsibilityCodeConverter.class, attributeName="key")
Map<String, Employee> responsibilities;

Example 7: Apply a converter to an embeddable attribute

@Embedded
@Convert(converter=CountryConverter.class, attributeName="country")
Address address;

Example 8: Apply a converter to a nested embeddable attribute

@Embedded
@Convert(converter=CityConverter.class, attributeName="region.city")
Address address;

Example 9: Apply a converter to a nested
attribute of an embeddable that is a map key of an element collection

@Entity
public class PropertyRecord {
 // ...

 @Convert(converter=CityConverter.class, attributeName="key.region.city")
 @ElementCollection
 Map<Address, PropertyInfo> parcels;
}

Example 10: Apply a converter to an embeddable that is a map key for a relationship

@OneToMany
@Convert(converter=ResponsibilityCodeConverter.class, attributeName="key.jobType")
Map<Responsibility, Employee> responsibilities;

Example 11: Override conversion mappings for attributes inherited from a mapped superclass

@Entity
@Convert(converter=DateConverter.class, attributeName="startDate")
@Convert(converter=DateConverter.class, attributeName="endDate")
public class FullTimeEmployee extends GenericEmployee { ... }

11.1.11. Converts Annotation

The Converts annotation can be used to
group Convert annotations. Multiple converters must not be applied to
the same basic attribute.

@Target({METHOD, FIELD, TYPE})
@Retention(RUNTIME)
public @interface Converts {
 Convert[] value();
}

Table 14 lists the annotation elements that may be
specified for the Converts annotation.

Table 14. Converts Annotation Elements

Type
Name
Description
Default

Convert[]

value

(Required) The Convert mappings that are to
be applied to the entity or the field or property.

Example: Multiple converters applied to an embedded attribute

@Embedded
@Converts({
 @Convert(converter=CountryConverter.class, attributeName="country"),
 @Convert(converter=CityConverter.class, attributeName="region.city")
})
Address address;

11.1.12. DiscriminatorColumn Annotation

For the SINGLE_TABLE mapping strategy, and
typically also for the JOINED strategy, the persistence provider will
use a type discriminator column. The DiscriminatorColumn annotation is
used to define the discriminator column for the SINGLE_TABLE and JOINED
inheritance mapping strategies.

The strategy and the discriminator
column are only specified in the root of an entity class hierarchy or
subhierarchy in which a different inheritance strategy is
applied.[108]

The DiscriminatorColumn annotation can be
specified on an entity class (including on an abstract entity class).

If the DiscriminatorColumn annotation is
missing, and a discriminator column is required, the name of the
discriminator column defaults to "DTYPE" and the discriminator type to
STRING.

Table 15 lists the annotation elements
that may be specified for the DiscriminatorColumn annotation and their
default values.

The supported discriminator types are defined
by the DiscriminatorType enum:

public enum DiscriminatorType { STRING, CHAR, INTEGER };

The type of the discriminator column, if
specified in the optional columnDefinition element, must be consistent
with the discriminator type.

@Target({TYPE})
@Retention(RUNTIME)
public @interface DiscriminatorColumn {
 String name() default "DTYPE";
 DiscriminatorType discriminatorType() default STRING;
 String columnDefinition() default "";
 String options() default "";
 int length() default 31;
}

Table 15. DiscriminatorColumn Annotation Elements

Type
Name
Description
Default

String

name

(Optional) The name of column to be used for
the discriminator.

"DTYPE"

DiscriminatorType

discriminatorType

(Optional) The type of object/column to use
as a class discriminator.

DiscriminatorType.STRING

String

columnDefinition

(Optional) The SQL fragment that is used
when generating the DDL for the discriminator column.

Provider-generated SQL to create a column of
the specified discriminator type.

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

int

length

(Optional) The column length for
String-based discriminator types. Ignored for other discriminator types.

31

Example:

@Entity
@Table(name="CUST")
@DiscriminatorColumn(name="DISC", discriminatorType=STRING, length=20)
public class Customer { ... }

@Entity
public class ValuedCustomer extends Customer { ... }

11.1.13. DiscriminatorValue Annotation

The DiscriminatorValue annotation is used
to specify the value of the discriminator column for entities of the
given type. The DiscriminatorValue annotation can only be specified on
a concrete entity class. If the DiscriminatorValue annotation is not
specified and a discriminator column is used, a provider-specific
function will be used to generate a value representing the entity type.

The inheritance strategy and the
discriminator column are only specified in the root of an entity class
hierarchy or subhierarchy in which a different inheritance strategy is
applied. The discriminator value, if not defaulted, should be specified
for each entity class in the hierarchy.

Table 16 lists the annotation elements
that may be specified for the DiscriminatorValue annotation and their
default values.

The discriminator value must be consistent in
type with the discriminator type of the specified or defaulted
discriminator column. If the discriminator type is an integer, the value
specified must be able to be converted to an integer value (e.g., “1”).

@Target({TYPE})
@Retention(RUNTIME)
public @interface DiscriminatorValue {
 String value();
}

Table 16. DiscriminatorValue Annotation Elements

Type
Name
Description
Default

String

value

(Optional) The value that indicates that the
row is an entity of the annotated entity type.

If the DiscriminatorValue annotation is not
specified, a provider-specific function to generate a value representing
the entity type is used for the value of the discriminator column. If
the DiscriminatorType is STRING, the discriminator value default is the
entity name.

Example:

@Entity
@Table(name="CUST")
@Inheritance(strategy=SINGLE_TABLE)
@DiscriminatorColumn(name="DISC", discriminatorType=STRING,length=20)
@DiscriminatorValue("CUSTOMER")
public class Customer { ... }

@Entity
@DiscriminatorValue("VCUSTOMER")
public class ValuedCustomer extends Customer { ... }

11.1.14. ElementCollection Annotation

The ElementCollection annotation
defines a collection of instances of a basic type or embeddable class.
The ElementCollection annotation (or equivalent XML element) must be
specified if the collection is to be mapped by means of a collection
table.[109]

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface ElementCollection {
 Class targetClass() default void.class;
 FetchType fetch() default LAZY;
}

Table 17 lists the annotation elements
that may be specified for the ElementCollection annotation and their
default values.

Table 17. ElementCollection Annotation Elements

Type
Name
Description
Default

Class

targetClass

(Optional) The basic or embeddable class
that is the element type of the collection. Optional only if the
collection field or property is defined using Java generics. Must be
specified otherwise.

The parameterized type
of the collection when defined using generics.

FetchType

fetch

(Optional) Whether the collection should be
lazily loaded or must be eagerly fetched. The EAGER strategy is a
requirement on the persistence provider runtime that the collection
elements must be eagerly fetched. The LAZY strategy is a hint to the
persistence provider runtime.

LAZY

Example:

@Entity public class Person {
 @Id
 protected String ssn;

 protected String name;

 @ElementCollection
 protected Set<String> nickNames = new HashSet();

 // ...
}

11.1.15. Embeddable Annotation

The Embeddable annotation is used to
specify a class whose instances are stored as an intrinsic part of an
owning entity and share the identity of the entity.

@Documented
@Target({TYPE})
@Retention(RUNTIME)
public @interface Embeddable {

}

Example 1:

@Embeddable
public class EmploymentPeriod {
 @Temporal(DATE)
 java.util.Date startDate;

 @Temporal(DATE)
 java.util.Date endDate;

 // ...
}

Example 2:

@Embeddable
public class PhoneNumber {
 protected String areaCode;
 protected String localNumber;

 @ManyToOne
 PhoneServiceProvider provider;

 // ...
}

@Entity
public class PhoneServiceProvider {
 @Id
 protected String name;

 // ...
}

Example 3:

@Embeddable
public class Address {
 protected String street;
 protected String city;
 protected String state;

 @Embedded
 protected Zipcode zipcode;
}

@Embeddable
public class Zipcode {
 protected String zip;
 protected String plusFour;
}

11.1.16. Embedded Annotation

The Embedded annotation is used to
specify a persistent field or property of an entity or embeddable class
whose value is an instance of an embeddable
class.[110] Each of the persistent properties or
fields of the embedded object is mapped to the database table for the
entity or embeddable class. The embeddable class must be annotated as
Embeddable.[111]

The AttributeOverride,
AttributeOverrides, AssociationOverride, and
AssociationOverrides annotations may be used to override mappings
declared or defaulted by the embeddable class.

Implementations are not required to support
embedded objects that are mapped across more than one table (e.g., split
across primary and secondary tables or multiple secondary tables).

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Embedded {}

Example:

@Embedded
@AttributeOverrides({
 @AttributeOverride(name="startDate", column=@Column(name="EMP_START")),
 @AttributeOverride(name="endDate", column=@Column(name="EMP_END"))
})
public EmploymentPeriod getEmploymentPeriod() { ... }

11.1.17. EmbeddedId Annotation

The EmbeddedId annotation is applied to a
persistent field or property of an entity class or mapped superclass to
denote a composite primary key that is an embeddable class. The
embeddable class must be annotated as Embeddable.[112] Relationship mappings defined within an
embedded id class are not supported.

There must be only one EmbeddedId
annotation and no Id annotation when the EmbeddedId annotation is
used.

The AttributeOverride annotation may be
used to override the column mappings declared within the embeddable
class.

The MapsId annotation may be used in
conjunction with the EmbeddedId annotation to specify a derived
primary key. See Section 2.4.2 and Section 11.1.38.

If the entity has a derived primary key, the
AttributeOverride annotation may only be used to override those
attributes of the embedded id that do not correspond to the relationship
to the parent entity.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface EmbeddedId {}

Example 1:

@Entity public class Employee {
 @EmbeddedId
 protected EmployeePK empPK;

 String name;

 @ManyToOne
 Set<Department> dept;

 // ...
}

Example 2:

@Embeddable
public class DependentId {
 String name;
 EmployeeId empPK; // corresponds to PK type of Employee
}

@Entity
public class Dependent {
 // default column name for "name" attribute is overridden
 @AttributeOverride(name="name", @Column(name="dep_name"))
 @EmbeddedId
 DependentId id;

 // ...

 @MapsId("empPK")
 @ManyToOne
 Employee emp;
}

11.1.18. Enumerated Annotation

An Enumerated annotation specifies that a persistent property or field
should be persisted as an enumerated type. The Enumerated annotation is
optional if the type of a persistent field or property is a Java enum type.

The Enumerated annotation may be used in conjunction with the Basic
annotation. The Enumerated annotation may be used in conjunction with
the ElementCollection annotation[113] when the element type of the
collection is an enum type.

An enum can be mapped as either a string or an integer[114]. The EnumType enum defines
the available options for mapping enumerated types.

public enum EnumType {
 ORDINAL,
 STRING
}

The value member of the Enumerated annotation specifies the EnumType:

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Enumerated {
 EnumType value() default ORDINAL;
}

Table 18 lists the annotation elements that may be specified for the
Enumerated annotation and their default values.

Table 18. Enumerated Annotation Elements
