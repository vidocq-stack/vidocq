# 11. Metadata for Object/Relational Mapping (part 4/4)

@Entity
public class Address {
 // ...

 @TableGenerator(
 name="addressGen",
 table="ID_GEN",
 pkColumnName="GEN_KEY",
 valueColumnName="GEN_VALUE",
 pkColumnValue="ADDR_ID")
 @Id
 @GeneratedValue(strategy=TABLE, generator="addressGen")
 int id;

 // ...
}

11.1.53. TableGenerators Annotation

The TableGenerators annotation can be used to
specify multiple table generators.

@Target({TYPE, METHOD, FIELD, PACKAGE})
@Retention(RUNTIME)
public @interface TableGenerators {
 TableGenerator[] value();
}

Table 49. TableGenerators Annotation Elements

Type
Name
Description
Default

TableGenerator[]

value

(Required) The table generator mappings

11.1.54. Temporal Annotation

The Temporal annotation must be specified
for persistent fields or properties of type java.util.Date and
java.util.Calendar unless a converter is being applied. It may only be
specified for fields or properties of these types.

The Temporal annotation may be used in
conjunction with the Basic annotation, the Id annotation, or the
`ElementCollection`[131] annotation (when the element
collection value is of such a temporal type).

The TemporalType enum defines the mapping for these temporal types.

public enum TemporalType {
 DATE, //java.sql.Date
 TIME, //java.sql.Time
 TIMESTAMP //java.sql.Timestamp
}

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Temporal {
 TemporalType value();
}

The Temporal annotation and TemporalType enum are deprecated, and their use in newly-written code is strongly discouraged.

Table 50 lists the annotation elements that may be specified
for the Temporal annotation and their default values.

Table 50. Temporal Annotation Elements

Type
Name
Description
Default

TemporalType

value

(Required) The type used in mapping java.util.Date or java.util.Calendar.

Example:

@Embeddable
public class EmploymentPeriod {
 @Temporal(DATE)
 java.util.Date startDate;

 @Temporal(DATE)
 java.util.Date endDate;

 // ...
}

11.1.55. Transient Annotation

The Transient annotation is used to
annotate a property or field of an entity class, mapped superclass, or
embeddable class. It specifies that the property or field is not
persistent.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Transient {}

Example:

@Entity
public class Employee {
 @Id
 int id;

 @Transient
 User currentUser;

 // ...
}

11.1.56. UniqueConstraint Annotation

The UniqueConstraint annotation is used to
specify that a unique constraint is to be included in the generated DDL
for a primary or secondary table.

Table 51 lists the annotation elements that
may be specified for the UniqueConstraint annotation.

@Target({})
@Retention(RUNTIME)
public @interface UniqueConstraint {
 String name() default "";
 String[] columnNames();
 String options() default "";
}

Table 51. UniqueConstraint Annotation Elements

Type
Name
Description
Default

String

name

(Optional) Constraint name.

A provider-chosen name.

String[]

columnNames

(Required) An array of the column names that make up the constraint.

String

options

(Optional) A SQL fragment appended to the generated DDL.

Nothing appended.

Example:

@Entity
@Table(
 name="EMPLOYEE",
 uniqueConstraints=@UniqueConstraint(columnNames={"EMP_ID", "EMP_NAME"})
)
public class Employee { ... }

11.1.57. Version Annotation

The Version annotation declares the version field or property of an
entity class, as defined in Section 2.5. The version is used to ensure
integrity when performing the merge operation, and for optimistic
concurrency control, as specified in Section 3.5.2.

The Version field or property should be mapped to the primary table
of the entity; an application which maps the Version property to a
table other than the primary table is not portable.

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface Version {}

Example:

@Version
@Column(name="OPTLOCK")
protected int getVersionNum() { return versionNum; }

11.2. Object/Relational Metadata Used in Schema Generation

The following annotations and XML elements
define or control the generation of database objects. If schema
generation is in effect, the persistence provider must observe the
mapping information specified by these annotations and their
corresponding XML elements. Unless otherwise specified, all elements of
these annotations are observed in the schema generation process.

CollectionTable

Column

DiscriminatorColumn

EmbeddedId

Enumerated, MapKeyEnumerated

ForeignKey

GeneratedValue

Id

Index

Inheritance

JoinColumn

JoinTable

Lob

MapKeyColumn

MapKeyJoinColumn

OrderColumn

PrimaryKeyJoinColumn

SecondaryTable

SequenceGenerator

Table

TableGenerator

Temporal, MapKeyTemporal

UniqueConstraint

Version

In some cases, these annotations and elements
may be specified explicitly, while in other cases they may be implied by
the default values of other annotations or elements. For example, by
default a table is generated corresponding to an entity and bears the
same name as that assigned to the entity (which in turn may have been
defaulted from the name of the entity class).

The naming of database objects is determined
by the defaulting rules and the explicit names used in annotations
and/or XML. The names of database objects must be treated in conformance
with the requirements of Section 2.15.

The metadata annotations and corresponding
XML elements that result in generated objects are as follows.

11.2.1. Table-level elements

The following annotations (and corresponding
XML elements) specify the creation of tables. The rules for their
naming, columns, and other properties are defined in the referenced
sections of this specification:

11.2.1.1. Table

By default, a table is created for every
top-level entity and, by default, includes columns corresponding to the
basic and embedded attributes of the entity and the foreign keys to the
tables of related entities. These columns include columns that result
from the use of mapped superclasses, if any. The SecondaryTable
annotation, in conjunction with the use of the table element of the
Column and JoinColumn annotations, is used to override this mapping
to partition the state of an entity across multiple tables.

The mapping of the columns of a table is
controlled by the Column and JoinColumn annotations. When entity
state is inherited from a mapped superclass, the AttributeOverride and
AssociationOverride annotations may be used to further control the
column-level mapping of inherited state. The ordering of the columns is
not defined by this specification. When it is desirable to control the
ordering of columns, DDL scripts should be provided.

See Section 11.1.50 for additional rules that apply to the
generation of tables. For the treatment of column-level mappings, see
further below.

11.2.1.2. Inheritance

The Inheritance annotation defines the
inheritance strategy for an entity hierarchy. The inheritance strategy
determines whether the table for a top-level entity includes columns for
entities that inherit from the entity and whether it includes a
discriminator column, or whether separate tables are created for each
entity type that inherits from the top-level entity. See Section 2.14 and
Section 11.1.25 for rules
pertaining to the treatment of entity inheritance.

11.2.1.3. SecondaryTable

A secondary table is created to partition the
mapping of entity state across multiple tables. See Section 11.1.47 for the rules
that apply to the generation of secondary tables.

11.2.1.4. CollectionTable

A collection table is created for the mapping
of an element collection. See Section 11.1.8 for the rules that apply to the generation
of collection tables. The Column, AttributeOverride, and
AssociationOverride annotations may be used to override
CollectionTable mappings, as described in Section 11.1.9,
Section 11.1.4, and Section 11.1.2 respectively.

11.2.1.5. JoinTable

By default, join tables are created for the
mapping of many-to-many relationships and unidirectional one-to-many
relationships. See Section 2.12.4, Section 2.12.5.1, and
Section 2.12.5.2 for the defaults that apply in such cases. Join tables may also be used
to map bidirectional many-to-one/one-to-many associations,
unidirectional many-to-one relationships, and one-to-one relationships
(both bidirectional and unidirectional). See Section 11.1.28 for the rules that
apply to the generation of join tables. The AssociationOverride
annotation may be used to override join table mappings.

11.2.1.6. TableGenerator

Table generator tables are used to store
generated primary key values. See Section 11.1.52 for the rules
pertaining to table generators.

11.2.2. Column-level elements

The following annotations and corresponding
XML elements control the mapping of columns in generated tables.

The exact mapping of Java language types to
database-specific types is not defined by this specification, as
databases vary in the specific types that they support. In general,
however, an implementation of this specification should conform to the
“Standard Mapping from Java Types to JDBC Types” as defined by the JDBC
specification [3]. Unless otherwise explicitly
specified, however, VARCHAR and VARBINARY mappings should be used in
preference to CHAR and BINARY mappings. Applications that are sensitive
to the exact database mappings that are generated should use the
columnDefinition element of the Column annotation or include DDL
files that specify how the database schema is to be generated.

11.2.2.1. Column

The following elements of the Column
annotation are used in schema generation:

name

unique

nullable

columnDefinition

table

length (string-valued columns only)

precision (exact numeric (decimal/numeric) columns only)

scale (exact numeric (decimal/numeric) columns only)

See Section 11.1.9 for the rules that apply to these elements and column
creation. The AttributeOverride annotation may be used to override
column mappings.

11.2.2.2. MapKeyColumn

The MapKeyColumn annotation specifies the
mapping for a key column of a map when the key is of basic type. The
following elements of the MapKeyColumn annotation are used in schema
generation:

name

unique

nullable

columnDefinition

table

length (string-valued columns only)

precision (exact numeric (decimal/numeric) columns only)

scale (exact numeric (decimal/numeric) columns only)

See Section 11.1.34 for the rules that apply to these elements and
map key column creation. The AttributeOverride annotation may be used
to override map key column mappings.

11.2.2.3. Enumerated, MapKeyEnumerated

The Enumerated and MapKeyEnumerated
annotations control whether string- or integer-valued columns are
generated for basic attributes of enumerated types and therefore impact
the default column mappings for these types. See Section 11.1.18 and
Section 11.1.35. The
Column and MapKeyColumn annotations may be used to further control
the column mappings for attributes of enumerated types.

11.2.2.4. Temporal, MapKeyTemporal

The Temporal and MapKeyTemporal
annotations control whether date-, time-, or timestamp-value columns are
generated for basic attributes of temporal types, and therefore impact
the default column mappings for these types. See Section 11.1.54 and
Section 11.1.38. The Column
and MapKeyColumn annotations may be used to further control the column
mappings for attributes of temporal types.

11.2.2.5. Lob

The Lob annotation specifies that a
persistent attribute is to be persisted to a database large object type.
See Section 11.1.29. In general,
however, the treatment of the Lob annotation is provider-dependent.
Applications that are sensitive to the exact mapping that is used should
use the columnDefinition element of the Column annotation or include
DDL files that specify how the database schema is to be generated.

11.2.2.6. OrderColumn

The OrderColumn annotation specifies the
generation of a column that is used to maintain the persistent ordering
of a list that is represented in an element collection, one-to-many, or
many-to-many relationship.

The following elements of the OrderColumn
annotation are used in schema generation:

name

nullable

columnDefinition

See Section 11.1.44 for the rules that pertain to the generation of order columns.

11.2.2.7. DiscriminatorColumn

A discriminator column is generated for the
SINGLE_TABLE mapping strategy and may optionally be generated by the
provider for use with the JOINED inheritance strategy. The
DiscriminatorColumn annotation may be used to control the mapping of
the discriminator column. See Section 11.1.12 for the rules that pertain to
discriminator columns.

11.2.2.8. Version

The Version annotation specifies the
generation of a column to serve as an entity’s optimistic lock. See
Section 11.1.57 for rules
that pertain to the version column. The Column annotation may be used
to further control the column mapping for a version attribute.

11.2.3. Primary Key mappings

Primary keys may be represented by basic or
embedded attributes and/or may correspond to foreign key attributes. The
Id and EmbeddedId annotations define attributes whose corresponding
columns are the constituents of database primary keys.

11.2.3.1. Id

The Id annotation (which may be used used
in conjunction with the IdClass annotation) is used to specify
attributes whose database columns correspond to a primary key. Use of
the Id annotation results in the creation of a primary key consisting
of the corresponding column or columns. Rules for the Id annotation
are described in Section 11.1.22 and Section 2.4.

The Column annotation may be used to
further control the column mapping for an Id attribute that is applied
to a basic type. If the Id column was defined in a mapped superclass,
the AttributeOverride annotation may be used to control the column
mapping.

The JoinColumn annotation may be used to
further control the column mappings for an Id attribute that is
applied to a relationship that corresponds to a foreign key. If the Id
attribute was defined in a mapped superclass, the AssociationOverride
annotation may be used to control the column mapping.

11.2.3.2. EmbeddedId

The EmbeddedId annotation specifies an
embedded attribute whose corresponding columns correspond to a database
primary key. Use of the EmbeddedId annotation results in the creation
of a primary key consisting of the corresponding columns. Rules for the
EmbeddedId annotation are described in Section 11.1.17 and Section 2.4.

The Column annotation may be used to
control the column mapping for an embeddable class. If the EmbeddedId
attribute is defined in a mapped superclass, the AttributeOverride
annotation may be used to control the column mappings.

If an EmbeddedId attribute corresponds to a
relationship attribute, the MapsId annotation must be used, and the
column mapping is determined by the join column for the relationship.
See Section 2.4.2.

11.2.3.3. GeneratedValue

The GeneratedValue annotation indicates a
primary key whose value is to be generated by the provider. If a
strategy is indicated, the provider must use it if it is supported by
the target database. Note that specification of the AUTO strategy may
result in the provider creating a database object for Id generation
(e.g., a database sequence). Rules for the GeneratedValue annotation
are described in Section 11.1.21. The GeneratedValue annotation may only be portably used
for simple (i.e., non-composite) primary keys.

11.2.4. Foreign Key Column Mappings

11.2.4.1. JoinColumn

The JoinColumn annotation is typically used
in specifying a foreign key mapping. In general, the foreign key
definitions created will be provider-dependent and database-dependent.
Applications that are sensitive to the exact mapping that is used should
use the foreignKey element of the JoinColumn annotation or include
DDL files that specify how the database schemas are to be generated.

The following elements of the JoinColumn
annotation are used in schema generation:

name

referencedColumnName

unique

nullable

columnDefinition

table

foreignKey

See Section 11.1.26 for rules that apply to these elements and join
column creation, and sections Section 2.12 and Section 11.1.8 for the rules that apply for the default
mappings of foreign keys for relationships and element collections. The
AssociationOverride annotation may be used to override relationship
mappings. The PrimaryKeyJoinColumn annotation is used to join
secondary tables and may be used in the mapping of one-to-one
relationships. See Section 11.2.4.3 below.

11.2.4.2. MapKeyJoinColumn

The MapKeyJoinColumn annotation is to
specify foreign key mappings to entities that are map keys in map-valued
element collections or relationships. In general, the foreign key
definitions created should be expected to be provider-dependent and
database-dependent. Applications that are sensitive to the exact mapping
that is used should use the foreignKey element of the
MapKeyJoinColumn annotation or include DDL files that specify how the
database schemas are to be generated.

The following elements of the
MapKeyJoinColumn annotation are used in schema generation:

name

referencedColumnName

unique

nullable

columnDefinition

table

foreignKey

See Section 11.1.36 for rules that apply to these elements and
map key join column creation. The AssociationOverride annotation may
be used to override such mappings.

11.2.4.3. PrimaryKeyJoinColumn

The PrimaryKeyJoinColumn annotation
specifies that a primary key column is to be used as a foreign key. This
annotation is used in the specification of the JOINED mapping strategy
and for joining a secondary table to a primary table in a OneToOne
relationship mapping. In general, the foreign key definitions created
should be expected to be provider-dependent and database-dependent.
Applications that are sensitive to the exact mapping that is used should
use the foreignKey element of the PrimaryKeyJoinColumn annotation or
include DDL files that specify how the database schemas are to be
generated. See Section 11.1.45 for rules pertaining to the
PrimaryKeyJoinColumn annotation.

11.2.4.4. ForeignKey

The ForeignKey annotation may be used
within the JoinColumn, JoinColumns, MapKeyJoinColumn,
MapKeyJoinColumns, PrimaryKeyJoinColumn, PrimaryKeyJoinColumns,
CollectionTable, JoinTable, SecondaryTable, and
AssociationOverride annotations to specify or override a foreign key
constraint. See Section 11.1.20.

11.2.5. Other Elements

11.2.5.1. SequenceGenerator

The SequenceGenerator annotation creates a
database sequence to be used for Id generation. The use of generators is
limited to those databases that support them. See Section 11.1.49.

11.2.5.2. Index

The Index annotation generates an index
consisting of the specified columns. The ordering of the names in the
columnList element specified in the Index annotation must be
observed by the provider when creating the index. See Section 11.1.24.

11.2.5.3. UniqueConstraint

The UniqueConstraint annotation generates a
unique constraint for the given table. Databases typically implement
unique constraints by creating unique indexes. The ordering of the
columnNames specified in the UniqueConstraint annotation must be
observed by the provider when creating the constraint. See Section 11.1.56. The
unique element of the Column, JoinColumn, MapKeyColumn, and
MapKeyJoinColumn annotations is equivalent to the use of the
UniqueConstraint annotation when only one column is to be included in
the constraint.

11.3. Examples of the Application of Annotations for Object/Relational Mapping

This example shows some simple mappings:

@Entity
public class Customer {
 @Id
 @GeneratedValue(strategy = AUTO)
 Long id;

 @Version
 protected int version;

 @ManyToOne
 Address address;

 @Basic
 String description;

 @OneToMany(targetEntity = com.acme.Order.class,
 mappedBy = "customer")
 Collection orders = new Vector();

 @ManyToMany(mappedBy = "customers")
 Set<DeliveryService> serviceOptions = new HashSet();

 public Long getId() {
 return id;
 }

 public Address getAddress() {
 return address;
 }

 public void setAddress(Address addr) {
 this.address = addr;
 }

 public String getDescription() {
 return description;
 }

 public void setDescription(String desc) {
 this.description = desc;
 }

 public Collection getOrders() {
 return orders;
 }

 public Set<DeliveryService> getServiceOptions() {
 return serviceOptions;
 }
}

@Entity
public class Address {
 private Long id;
 private int version;
 private String street;

 @Id
 @GeneratedValue(strategy = AUTO)
 public Long getId() {
 return id;
 }

 protected void setId(Long id) {
 this.id = id;
 }

 @Version
 public int getVersion() {
 return version;
 }

 protected void setVersion(int version) {
 this.version = version;
 }

 public String getStreet() {
 return street;
 }

 public void setStreet(String street) {
 this.street = street;
 }
}

@Entity
public class Order {
 private Long id;
 private int version;
 private String itemName;
 private int quantity;
 private Customer cust;

 @Id
 @GeneratedValue(strategy = AUTO)
 public Long getId() {
 return id;
 }

 public void setId(Long id) {
 this.id = id;
 }

 @Version
 protected int getVersion() {
 return version;
 }

 protected void setVersion(int version) {
 this.version = version;
 }

 public String getItemName() {
 return itemName;
 }

 public void setItemName(String itemName) {
 this.itemName = itemName;
 }

 public int getQuantity() {
 return quantity;
 }

 public void setQuantity(int quantity) {
 this.quantity = quantity;
 }

 @ManyToOne
 public Customer getCustomer() {
 return cust;
 }

 public void setCustomer(Customer cust) {
 this.cust = cust;
 }
}

@Entity
@Table(name = "DLVY_SVC")
public class DeliveryService {
 private String serviceName;
 private int priceCategory;
 private Collection customers;

 @Id
 public String getServiceName() {
 return serviceName;
 }

 public void setServiceName(String serviceName) {
 this.serviceName = serviceName;
 }

 public int getPriceCategory() {
 return priceCategory;
 }

 public void setPriceCategory(int priceCategory) {
 this.priceCategory = priceCategory;
 }

 @ManyToMany(targetEntity = com.acme.Customer.class)
 @JoinTable(name = "CUST_DLVRY")
 public Collection getCustomers() {
 return customers;
 }

 public setCustomers(Collection customers) {
 this.customers = customers;
 }
}

Next, we have a more complex example:

/***** Employee class *****/
@Entity
@Table(name = "EMPL")
@SecondaryTable(name = "EMP_SALARY",
 pkJoinColumns = @PrimaryKeyJoinColumn(name = "EMP_ID", referencedColumnName = "ID"))
public class Employee implements Serializable {
 private Long id;
 private int version;
 private String name;
 private Address address;
 private Collection phoneNumbers;
 private Collection<Project> projects;
 private Long salary;
 private EmploymentPeriod period;

 @Id
 @GeneratedValue(strategy = TABLE)
 public Integer getId() {
 return id;
 }

 protected void setId(Integer id) {
 this.id = id;
 }

 @Version
 @Column(name = "EMP_VERSION", nullable = false)
 public int getVersion() {
 return version;
 }

 protected void setVersion(int version) {
 this.version = version;
 }

 @Column(name = "EMP_NAME", length = 80)
 public String getName() {
 return name;
 }

 public void setName(String name) {
 this.name = name;
 }

 @ManyToOne(cascade = PERSIST, optional = false)
 @JoinColumn(name = "ADDR_ID", referencedColumnName = "ID", nullable = false)
 public Address getAddress() {
 return address;
 }

 public void setAddress(Address address) {
 this.address = address;
 }

 @OneToMany(targetEntity = com.acme.PhoneNumber.class,
 cascade = ALL,
 mappedBy = "employee")
 public Collection getPhoneNumbers() {
 return phoneNumbers;
 }

 public void setPhoneNumbers(Collection phoneNumbers) {
 this.phoneNumbers = phoneNumbers;
 }

 @ManyToMany(cascade = PERSIST, mappedBy = "employees")
 @JoinTable(
 name = "EMP_PROJ",
 joinColumns = @JoinColumn(name = "EMP_ID", referencedColumnName = "ID"),
 inverseJoinColumns = @JoinColumn(name = "PROJ_ID", referencedColumnName = "ID"))
 public Collection<Project> getProjects() {
 return projects;
 }

 public void setProjects(Collection<Project> projects) {
 this.projects = projects;
 }

 @Column(name = "EMP_SAL", table = "EMP_SALARY")
 public Long getSalary() {
 return salary;
 }

 public void setSalary(Long salary) {
 this.salary = salary;
 }

 @Embedded
 @AttributeOverrides({
 @AttributeOverride(name = "startDate",
 column = @Column(name = "EMP_START")),
 @AttributeOverride(name = "endDate",
 column = @Column(name = "EMP_END"))
 })
 public EmploymentPeriod getEmploymentPeriod() {
 return period;
 }

 public void setEmploymentPeriod(EmploymentPeriod period) {
 this.period = period;
 }
}

/***** Address class *****/
@Entity
public class Address implements Serializable {
 private Integer id;
 private int version;
 private String street;
 private String city;

 @Id
 @GeneratedValue(strategy = IDENTITY)
 public Integer getId() {
 return id;
 }

 protected void setId(Integer id) {
 this.id = id;
 }

 @Version
 @Column(name = "VERS", nullable = false)
 public int getVersion() {
 return version;
 }

 protected void setVersion(int version) {
 this.version = version;
 }

 @Column(name = "RUE")
 public String getStreet() {
 return street;
 }

 public void setStreet(String street) {
 this.street = street;
 }

 @Column(name = "VILLE")
 public String getCity() {
 return city;
 }

 public void setCity(String city) {
 this.city = city;
 }
}

/***** PhoneNumber class *****/
@Entity
@Table(name = "PHONE")
public class PhoneNumber implements Serializable {
 private String number;
 private int phoneType;
 private Employee employee;

 @Id
 public String getNumber() {
 return number;
 }

 public void setNumber(String number) {
 this.number = number;
 }

 @Column(name = "PTYPE")
 public int getPhonetype() {
 return phonetype;
 }

 public void setPhoneType(int phoneType) {
 this.phoneType = phoneType;
 }

 @ManyToOne(optional = false)
 @JoinColumn(name = "EMP_ID", nullable = false)
 public Employee getEmployee() {
 return employee;
 }

 public void setEmployee(Employee employee) {
 this.employee = employee;
 }
}

/***** Project class *****/
@Entity
@Inheritance(strategy = JOINED)
@DiscriminatorValue("Proj")
@DiscriminatorColumn(name = "DISC")
public class Project implements Serializable {
 private Integer projId;
 private int version;
 private String name;
 private Set<Employee> employees;

 @Id
 @GeneratedValue(strategy = TABLE)
 public Integer getId() {
 return projId;
 }

 protected void setId(Integer id) {
 this.projId = id;
 }

 @Version
 public int getVersion() {
 return version;
 }

 protected void setVersion(int version) {
 this.version = version;
 }

 @Column(name = "PROJ_NAME")
 public String getName() {
 return name;
 }

 public void setName(String name) {
 this.name = name;
 }

 @ManyToMany(mappedBy = "projects")
 public Set<Employee> getEmployees() {
 return employees;
 }

 public void setEmployees(Set<Employee> employees) {
 this.employees = employees;
 }
}

/***** GovernmentProject subclass *****/
@Entity
@Table(name = "GOVT_PROJECT")
@DiscriminatorValue("GovtProj")
@PrimaryKeyJoinColumn(name = "GOV_PROJ_ID", referencedColumnName = "ID")
public class GovernmentProject extends Project {
 private String fileInfo;

 @Column(name = "INFO")
 public String getFileInfo() {
 return fileInfo;
 }

 public void setFileInfo(String fileInfo) {
 this.fileInfo = fileInfo;
 }
}

/***** CovertProject subclass *****/
@Entity
@Table(name = "C_PROJECT")
@DiscriminatorValue("CovProj")
@PrimaryKeyJoinColumn(name = "COV_PROJ_ID", referencedColumnName = "ID")
public class CovertProject extends Project {
 private String classified;

 public CovertProject() {
 super();
 }

 public CovertProject(String classified) {
 this();
 this.classified = classified;
 }

 @Column(updatable = false)
 public String getClassified() {
 return classified;
 }

 protected void setClassified(String classified) {
 this.classified = classified;
 }
}

/***** EmploymentPeriod class *****/
@Embeddable
public class EmploymentPeriod implements Serializable {
 private Date start;
 private Date end;

 @Column(nullable = false)
 public Date getStartDate() {
 return start;
 }

 public void setStartDate(Date start) {
 this.start = start;
 }

 public Date getEndDate() {
 return end;
 }

 public void setEndDate(Date end) {
 this.end = end;
 }
}
