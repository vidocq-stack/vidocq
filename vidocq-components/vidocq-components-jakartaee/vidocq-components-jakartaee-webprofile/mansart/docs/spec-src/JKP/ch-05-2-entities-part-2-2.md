# 2. Entities (part 2/2)

Relationships may be bidirectional or
unidirectional. A bidirectional relationship has both an owning side and
an inverse (non-owning) side. A unidirectional relationship has only an
owning side. The owning side of a relationship determines the updates to
the relationship in the database, as described in Section 3.3.4.

The following rules apply to bidirectional
relationships:

The inverse side of a bidirectional
relationship must refer to its owning side by use of the mappedBy
element of the OneToOne, OneToMany, or ManyToMany annotation.
The mappedBy element designates the property or field in the entity
that is the owner of the relationship.

The many side of one-to-many / many-to-one
bidirectional relationships must be the owning side, hence the
mappedBy element cannot be specified on the ManyToOne annotation.

For one-to-one bidirectional relationships,
the owning side corresponds to the side that contains the corresponding
foreign key.

For many-to-many bidirectional relationships
either side may be the owning side.

The relationship modeling annotation
constrains the use of the cascade=REMOVE specification. The
cascade=REMOVE specification should only be applied to associations
that are specified as OneToOne or OneToMany. Applications that
apply cascade=REMOVE to other associations are not portable.

Associations that are specified as OneToOne
or OneToMany support use of the orphanRemoval option. The following
behaviors apply when orphanRemoval is in effect:

If an entity that is the target of the
relationship is removed from the relationship (by setting the
relationship to null or removing the entity from the relationship
collection), the remove operation will be applied to the entity being
orphaned. The remove operation is applied at the time of the flush
operation. The orphanRemoval functionality is intended for entities
that are privately “owned” by their parent entity. Portable applications
must otherwise not depend upon a specific order of removal, and must not
reassign an entity that has been orphaned to another relationship or
otherwise attempt to persist it. If the entity being orphaned is a
detached, new, or removed entity, the semantics of orphanRemoval do
not apply.

If the remove operation is applied to a
managed source entity, the remove operation will be cascaded to the
relationship target in accordance with the rules of Section 3.3.3,
(and hence it is not necessary to specify cascade=REMOVE for the
relationship)[26].

Section 2.12, defines relationship mapping defaults
for entity relationships. Additional mapping annotations (e.g., column
and table mapping annotations) may be specified to override or further
refine the default mappings and mapping strategies described in Section 2.12.

In addition, this specification also requires
support for the following alternative mapping strategies:

The mapping of unidirectional one-to-many
relationships by means of foreign key mappings. The JoinColumn
annotation or corresponding XML element must be used to specify such
non-default mappings. See Section 11.1.26.

The mapping of unidirectional and
bidirectional one-to-one relationships, bidirectional
many-to-one/one-to-many relationships, and unidirectional many-to-one
relationships by means of join table mappings. The JoinTable
annotation or corresponding XML element must be used to specify such
non-default mappings. See Section 11.1.28.

Such mapping annotations must be specified on
the owning side of the relationship. Any overriding of mapping defaults
must be consistent with the relationship modeling annotation that is
specified. For example, if a many-to-one relationship mapping is
specified, it is not permitted to specify a unique key constraint on the
foreign key for the relationship.

The persistence provider handles the
object/relational mapping of the relationships, including their loading
and storing to the database as specified in the metadata of the entity
class, and the referential integrity of the relationships as specified
in the database (e.g., by foreign key constraints).

Note that it is the application that bears
responsibility for maintaining the consistency of runtime
relationships—for example, for insuring that the “one” and the “many”
sides of a bidirectional relationship are consistent with one another
when the application updates the relationship at runtime.

If there are no associated entities for a
multi-valued relationship of an entity fetched from the database, the
persistence provider is responsible for returning an empty collection as
the value of the relationship.

2.12. Relationship Mapping Defaults

This section defines the mapping defaults
that apply to the use of the OneToOne, OneToMany, ManyToOne,
and ManyToMany relationship modeling annotations. The same mapping
defaults apply when the XML descriptor is used to denote the
relationship cardinalities.

2.12.1. Bidirectional OneToOne Relationships

Assuming that:

Entity A references a single instance of Entity B.

Entity B references a single instance of Entity A.

Entity A is specified as the owner of the relationship.

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

Table A contains a foreign key to table B.
The foreign key column name is formed as the concatenation of the
following: the name of the relationship property or field of entity A; "
_ "; the name of the primary key column in table B. The foreign key
column has the same type as the primary key of table B and there is a
unique key constraint on it.

Example:

@Entity
public class Employee {
 private Cubicle assignedCubicle;

 @OneToOne
 public Cubicle getAssignedCubicle() {
 return assignedCubicle;
 }

 public void setAssignedCubicle(Cubicle cubicle) {
 this.assignedCubicle = cubicle;
 }

 // ...
}

@Entity
public class Cubicle {
 private Employee residentEmployee;

 @OneToOne(mappedBy="assignedCubicle")
 public Employee getResidentEmployee() {
 return residentEmployee;
 }

 public void setResidentEmployee(Employee employee) {
 this.residentEmployee = employee;
 }

 // ...
}

In this example:

Entity Employee references a single instance of Entity Cubicle.

Entity Cubicle references a single instance of Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity Cubicle is mapped to a table named CUBICLE.

Table EMPLOYEE contains a foreign key to table CUBICLE.
The foreign key column is named ASSIGNEDCUBICLE_<PK of CUBICLE>,
where <PK of CUBICLE> denotes the name of the primary key column
of table CUBICLE. The foreign key column has the same type as the
primary key of CUBICLE, and there is a unique key constraint on it.

2.12.2. Bidirectional ManyToOne / OneToMany Relationships

Assuming that:

Entity A references a single instance of Entity B.

Entity B references a collection of Entity A[27].

Entity A must be the owner of the relationship.

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

Table A contains a foreign key to table B.
The foreign key column name is formed as the concatenation of the
following: the name of the relationship property or field of entity A; "
_ "; the name of the primary key column in table B. The foreign key
column has the same type as the primary key of table B.

Example:

@Entity
public class Employee {
 private Department department;

 @ManyToOne
 public Department getDepartment() {
 return department;
 }

 public void setDepartment(Department department) {
 this.department = department;
 }

 // ...
}

@Entity
public class Department {
 private Collection<Employee> employees = new HashSet();

 @OneToMany(mappedBy="department")
 public Collection<Employee> getEmployees() {
 return employees;
 }

 public void setEmployees(Collection<Employee> employees) {
 this.employees = employees;
 }

 // ...
}

In this example:

Entity Employee references a single instance of Entity Department.

Entity Department references a collection of Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity Department is mapped to a table named DEPARTMENT.

Table EMPLOYEE contains a foreign key to table DEPARTMENT.
The foreign key column is named DEPARTMENT_<PK of DEPARTMENT>,
where <PK of DEPARTMENT> denotes the name of the primary key
column of table DEPARTMENT. The foreign key column has the same
type as the primary key of DEPARTMENT.

2.12.3. Unidirectional Single-Valued Relationships

Assuming that:

Entity A references a single instance of Entity B.

Entity B does not reference Entity A.

A unidirectional relationship has only an owning side, which in this case must be Entity A.

The unidirectional single-valued relationship
modeling case can be specified as either a unidirectional OneToOne or
as a unidirectional ManyToOne relationship.

2.12.3.1. Unidirectional OneToOne Relationships

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

Table A contains a foreign key to table B.
The foreign key column name is formed as the concatenation of the
following: the name of the relationship property or field of entity A; "
_ "; the name of the primary key column in table B. The foreign key
column has the same type as the primary key of table B and there is a
unique key constraint on it.

Example:

@Entity
public class Employee {
 private TravelProfile profile;

 @OneToOne
 public TravelProfile getProfile() {
 return profile;
 }

 public void setProfile(TravelProfile profile) {
 this.profile = profile;
 }

 // ...
}

@Entity
public class TravelProfile {
 // ...
}

In this example:

Entity Employee references a single instance of Entity TravelProfile.

Entity TravelProfile does not reference Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity TravelProfile is mapped to a table named TRAVELPROFILE.

Table EMPLOYEE contains a foreign key to table TRAVELPROFILE.
The foreign key column is named PROFILE_<PK of TRAVELPROFILE>,
where <PK of TRAVELPROFILE> denotes the name of the primary key
column of table TRAVELPROFILE. The foreign key column has the
same type as the primary key of TRAVELPROFILE, and there is a
unique key constraint on it.

2.12.3.2. Unidirectional ManyToOne Relationships

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

Table A contains a foreign key to table B. The foreign key column name is formed as the concatenation of the following: the name of the relationship property or field of entity A; "_"; the name of the primary key column in table B. The foreign key column has the same type as the primary key of table B.

Example:

@Entity
public class Employee {
 private Address address;

 @ManyToOne
 public Address getAddress() {
 return address;
 }

 public void setAddress(Address address) {
 this.address = address;
 }

 // ...
}

@Entity
public class Address {
 // ...
}

In this example:

Entity Employee references a single instance of Entity Address.

Entity Address does not reference Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity Address is mapped to a table named ADDRESS.

Table EMPLOYEE contains a foreign key to table ADDRESS.
The foreign key column is named ADDRESS_<PK of ADDRESS>,
where <PK of ADDRESS> denotes the name of the primary key
column of table ADDRESS. The foreign key column has the same
type as the primary key of ADDRESS.

2.12.4. Bidirectional ManyToMany Relationships

Assuming that:

Entity A references a collection of Entity B.

Entity B references a collection of Entity A.

Entity A is the owner of the relationship.

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

There is a join table that is named A_B
(owner name first). This join table has two foreign key columns. One
foreign key column refers to table A and has the same type as the
primary key of table A. The name of this foreign key column is formed
as the concatenation of the following: the name of the relationship
property or field of entity B; " _ "; the name of the primary key
column in table A. The other foreign key column refers to table B
and has the same type as the primary key of table B. The name of this
foreign key column is formed as the concatenation of the following: the
name of the relationship property or field of entity A; " _ "; the
name of the primary key column in table B.

Example:

@Entity
public class Project {
 private Collection<Employee> employees;

 @ManyToMany
 public Collection<Employee> getEmployees() {
 return employees;
 }

 public void setEmployees(Collection<Employee> employees) {
 this.employees = employees;
 }

 // ...
}

@Entity
public class Employee {
 private Collection<Project> projects;

 @ManyToMany(mappedBy="employees")
 public Collection<Project> getProjects() {
 return projects;
 }

 public void setProjects(Collection<Project> projects) {
 this.projects = projects;
 }

 // ...
}

In this example:

Entity Project references a collection of Entity Employee.

Entity Employee references a collection of Entity Project.

Entity Project is the owner of the relationship.

The following mapping defaults apply:

Entity Project is mapped to a table named PROJECT.

Entity Employee is mapped to a table named EMPLOYEE.

There is a join table that is named
PROJECT_EMPLOYEE (owner name first). This join table has two foreign
key columns. One foreign key column refers to table PROJECT and has
the same type as the primary key of PROJECT. The name of this foreign
key column is PROJECTS_<PK of PROJECT>, where <PK of PROJECT> denotes
the name of the primary key column of table PROJECT. The other
foreign key column refers to table EMPLOYEE and has the same type as
the primary key of EMPLOYEE. The name of this foreign key column is
EMPLOYEES_<PK of EMPLOYEE>, where <PK of EMPLOYEE> denotes the name
of the primary key column of table EMPLOYEE.

2.12.5. Unidirectional Multi-Valued Relationships

Assuming that:

Entity A references a collection of Entity B.

Entity B does not reference Entity A.

A unidirectional relationship has only an owning side, which in this case must be Entity A.

The unidirectional multi-valued relationship
modeling case can be specified as either a unidirectional OneToMany or
as a unidirectional ManyToMany relationship.

2.12.5.1. Unidirectional OneToMany Relationships

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

There is a join table that is named A_B
(owner name first). This join table has two foreign key columns. One
foreign key column refers to table A and has the same type as the
primary key of table A. The name of this foreign key column is formed
as the concatenation of the following: the name of entity A; " _ ";
the name of the primary key column in table A. The other foreign key
column refers to table B and has the same type as the primary key of
table B and there is a unique key constraint on it. The name of this
foreign key column is formed as the concatenation of the following: the
name of the relationship property or field of entity A; " _ "; the
name of the primary key column in table B.

Example:

@Entity
public class Employee {
 private Collection<AnnualReview> annualReviews;

 @OneToMany
 public Collection<AnnualReview> getAnnualReviews() {
 return annualReviews;
 }

 public void setAnnualReviews(Collection<AnnualReview> annualReviews) {
 this.annualReviews = annualReviews;
 }

 // ...
}

@Entity
public class AnnualReview {
 // ...
}

In this example:

Entity Employee references a collection of Entity AnnualReview.

Entity AnnualReview does not reference Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity AnnualReview is mapped to a table named ANNUALREVIEW.

There is a join table that is named
EMPLOYEE_ANNUALREVIEW (owner name first). This join table has two
foreign key columns. One foreign key column refers to table EMPLOYEE
and has the same type as the primary key of EMPLOYEE. This foreign
key column is named EMPLOYEE_<PK of EMPLOYEE>, where <PK of EMPLOYEE>
denotes the name of the primary key column of table EMPLOYEE. The
other foreign key column refers to table ANNUALREVIEW and has the same
type as the primary key of ANNUALREVIEW. This foreign key column is
named ANNUALREVIEWS_<PK of ANNUALREVIEW>, where <PK of ANNUALREVIEW>
denotes the name of the primary key column of table ANNUALREVIEW.
There is a unique key constraint on the foreign key that refers to table
ANNUALREVIEW.

2.12.5.2. Unidirectional ManyToMany Relationships

The following mapping defaults apply:

Entity A is mapped to a table named A.

Entity B is mapped to a table named B.

There is a join table that is named A_B
(owner name first). This join table has two foreign key columns. One
foreign key column refers to table A and has the same type as the
primary key of table A. The name of this foreign key column is formed as
the concatenation of the following: the name of entity A; " _ ";
the name of the primary key column in table A. The other foreign key
column refers to table B and has the same type as the primary key of
table B. The name of this foreign key column is formed as the
concatenation of the following: the name of the relationship property or
field of entity A; " _ "; the name of the primary key column in
table B.

Example:

@Entity
public class Employee {
 private Collection<Patent> patents;

 @ManyToMany
 public Collection<Patent> getPatents() {
 return patents;
 }

 public void setPatents(Collection<Patent> patents) {
 this.patents = patents;
 }

 // ...
}

@Entity
public class Patent {
 //...
}

In this example:

Entity Employee references a collection of Entity Patent.

Entity Patent does not reference Entity Employee.

Entity Employee is the owner of the relationship.

The following mapping defaults apply:

Entity Employee is mapped to a table named EMPLOYEE.

Entity Patent is mapped to a table named PATENT.

There is a join table that is named
EMPLOYEE_PATENT (owner name first). This join table has two foreign
key columns. One foreign key column refers to table EMPLOYEE and has
the same type as the primary key of EMPLOYEE. This foreign key column
is named EMPLOYEE_<PK of EMPLOYEE>, where <PK of EMPLOYEE> denotes
the name of the primary key column of table EMPLOYEE. The other
foreign key column refers to table PATENT and has the same type as the
primary key of PATENT. This foreign key column is named
PATENTS_<PK of PATENT>, where <PK of PATENT> denotes the name of the
primary key column of table PATENT.

2.13. Inheritance

An entity may inherit from another entity
class. Entities support inheritance, polymorphic associations, and
polymorphic queries.

Both abstract and concrete classes can be
entities. Both abstract and concrete classes can be annotated with the
Entity annotation, mapped as entities, and queried for as entities.

Entities can extend non-entity classes and
non-entity classes can extend entity classes.

These concepts are described further in the
following sections.

2.13.1. Abstract Entity Classes

An abstract class can be specified as an
entity. An abstract entity differs from a concrete entity only in that
it cannot be directly instantiated. An abstract entity is mapped as an
entity and can be the target of queries (which will operate over and/or
retrieve instances of its concrete subclasses).

An abstract entity class is annotated with
the Entity annotation or denoted in the XML descriptor as an entity.

The following example shows the use of an
abstract entity class in the entity inheritance hierarchy.

Example: Abstract class as an Entity

@Entity
@Table(name="EMP")
@Inheritance(strategy=JOINED)
public abstract class Employee {
 @Id
 protected Integer empId;

 @Version
 protected Integer version;

 @ManyToOne
 protected Address address;

 // ...
}

@Entity
@Table(name="FT_EMP")
@DiscriminatorValue("FT")
@PrimaryKeyJoinColumn(name="FT_EMPID")
public class FullTimeEmployee extends Employee {
 // Inherit empId, but mapped in this class to FT_EMP.FT_EMPID
 // Inherit version mapped to EMP.VERSION
 // Inherit address mapped to EMP.ADDRESS fk

 // Defaults to FT_EMP.SALARY
 protected Integer salary;

 // ...
}

@Entity
@Table(name="PT_EMP")
@DiscriminatorValue("PT")
// PK column is PT_EMP.EMPID due to `PrimaryKeyJoinColumn` default
public class PartTimeEmployee extends Employee {
 protected Float hourlyWage;

 // ...
}

2.13.2. Mapped Superclasses

An entity may inherit from a superclass that
provides persistent entity state and mapping information, but which is
not itself an entity. Typically, the purpose of such a mapped superclass
is to define state and mapping information that is common to multiple
entity classes.

A mapped superclass, unlike an entity, is not
queryable and must not be passed as an argument to EntityManager or
Query operations. Persistent relationships defined by a mapped
superclass must be unidirectional.

Both abstract and concrete classes may be
specified as mapped superclasses. The MappedSuperclass annotation (or
mapped-superclass XML descriptor element) is used to designate a
mapped superclass.

A class designated as a mapped superclass has
no separate table defined for it. Its mapping information is applied to
the entities that inherit from it.

The persistent attributes of a mapped superclass may be mapped in the same
way as the attributes of an entity class. Such mappings apply only to the
entity subclasses of the mapped superclass, since no table exists for the
mapped superclass itself. When applied to a subclass, the inherited mappings
are interpreted in the context of the tables mapped by subclass. Mapping
information inherited from a mapped superclass can be overridden in such
subclasses using the AttributeOverride and AssociationOverride
annotations or corresponding XML elements.

All other entity mapping defaults apply
equally to a class designated as a mapped superclass.

The following example illustrates the
definition of a concrete class as a mapped superclass.

Example: Concrete class as a mapped superclass

@MappedSuperclass
public class Employee {
 @Id
 protected Integer empId;

 @Version
 protected Integer version;

 @ManyToOne
 @JoinColumn(name="ADDR")
 protected Address address;

 public Integer getEmpId() { ... }

 public void setEmpId(Integer id) { ... }

 public Address getAddress() { ... }

 public void setAddress(Address addr) { ... }
}

// Default table is FTEMPLOYEE table
@Entity
public class FTEmployee extends Employee {
 // Inherited empId field mapped to FTEMPLOYEE.EMPID
 // Inherited version field mapped to FTEMPLOYEE.VERSION
 // Inherited address field mapped to FTEMPLOYEE.ADDR fk

 // Defaults to FTEMPLOYEE.SALARY
 protected Integer salary;

 public FTEmployee() {}

 public Integer getSalary() { ... }

 public void setSalary(Integer salary) { ... }
}

@Entity
@Table(name="PT_EMP")
@AssociationOverride(name="address", joincolumns=@JoinColumn(name="ADDR_ID"))
public class PartTimeEmployee extends Employee {
 // Inherited empId field mapped to PT_EMP.EMPID
 // Inherited version field mapped to PT_EMP.VERSION
 // address field mapping overridden to PT_EMP.ADDR_ID fk
 @Column(name="WAGE")
 protected Float hourlyWage;

 public PartTimeEmployee() {}

 public Float getHourlyWage() { ... }

 public void setHourlyWage(Float wage) { ... }
}

2.13.3. Non-Entity Classes in the Entity Inheritance Hierarchy

An entity can have a non-entity
superclass, which may be either a concrete or abstract
class.[28]

The non-entity superclass serves for
inheritance of behavior only. The state of a non-entity superclass is
not persistent. Any state inherited from non-entity superclasses is
non-persistent in an inheriting entity class. This non-persistent state
is not managed by the entity manager[29]. Any
annotations on such superclasses are ignored.

Non-entity classes cannot be passed as
arguments to methods of the EntityManager or Query
interfaces[30] and cannot bear mapping information.

The following example illustrates the use of
a non-entity class as a superclass of an entity.

Example: Non-entity superclass

public class Cart {
 protected Integer operationCount; // transient state

 public Cart() {
 operationCount = 0;
 }

 public Integer getOperationCount() {
 return operationCount;
 }

 public void incrementOperationCount() {
 operationCount++;
 }
}

@Entity
public class ShoppingCart extends Cart {
 Collection<Item> items = new Vector<Item>();

 public ShoppingCart() {
 super();
 }

 // ...

 @OneToMany
 public Collection<Item> getItems() {
 return items;
 }

 public void addItem(Item item) {
 items.add(item);
 incrementOperationCount();
 }
}

2.14. Inheritance Mapping Strategies

The mapping of class hierarchies is specified through metadata.

There are three basic strategies that are
used when mapping a class or class hierarchy to a relational database:

a single table per class hierarchy

a joined subclass strategy, in which fields
that are specific to a subclass are mapped to a separate table than the
fields that are common to the parent class, and a join is performed to
instantiate the subclass.

a table per concrete entity class

An implementation is required to support the
single table per class hierarchy inheritance mapping strategy and the
joined subclass strategy.

Support for the table per concrete class
inheritance mapping strategy is optional in this release. Applications
that use this mapping strategy will not be portable.

Support for the combination of inheritance
strategies within a single entity inheritance hierarchy is not required
by this specification.

2.14.1. Single Table per Class Hierarchy Strategy

In this strategy, all the classes in a
hierarchy are mapped to a single table. The table has a column that
serves as a “discriminator column”, that is, a column whose value
identifies the specific subclass to which the instance that is
represented by the row belongs.

This mapping strategy provides good support
for polymorphic relationships between entities and for queries that
range over the class hierarchy.

It has the drawback, however, that it
requires that the columns that correspond to state specific to the
subclasses be nullable.

2.14.2. Joined Subclass Strategy

In the joined subclass strategy, the root of
the class hierarchy is represented by a single table. Each subclass is
represented by a separate table that contains those fields that are
specific to the subclass (not inherited from its superclass), as well as
the column(s) that represent its primary key. The primary key column(s)
of the subclass table serves as a foreign key to the primary key of the
superclass table.

This strategy provides support for
polymorphic relationships between entities.

It has the drawback that it requires that one
or more join operations be performed to instantiate instances of a
subclass. In deep class hierarchies, this may lead to unacceptable
performance. Queries that range over the class hierarchy likewise
require joins.

2.14.3. Table per Concrete Class Strategy

In this mapping strategy, each class is
mapped to a separate table. All properties of the class, including
inherited properties, are mapped to columns of the table for the class.

This strategy has the following drawbacks:

It provides poor support for polymorphic relationships.

It typically requires that SQL UNION queries
(or a separate SQL query per subclass) be issued for queries that are
intended to range over the class hierarchy.

2.15. Naming of Database Objects

Many annotations and annotation elements
contain names of database objects or assume default names for database
objects.

This specification requires the following
with regard to the interpretation of the names referencing database
objects. These names include the names of tables, columns, and other
database elements. Such names also include names that result from
defaulting (e.g., a table name that is defaulted from an entity name or
a column name that is defaulted from a field or property name).

By default, the names of database objects
must be treated as undelimited identifiers and passed to the database as
such.

For example, assuming the use of an English
locale, the following must be passed to the database as undelimited
identifers so that they will be treated as equivalent for all databases
that comply with the SQL Standard’s requirements for the treatment of
“regular identifiers” (undelimited identifiers) and “delimited
identifiers” [2]:

@Table(name="Customer")
@Table(name="customer")
@Table(name="cUsTomer")

Similarly, the following must be treated as equivalent:

@JoinColumn(name="CUSTOMER")
@ManyToOne Customer customer;

@JoinColumn(name="customer")
@ManyToOne Customer customer;

@ManyToOne Customer customer;

To specify delimited identifiers, one of the
following approaches must be used:

It is possible to specify that all database
identifiers in use for a persistence unit be treated as delimited
identifiers by specifying the <delimited-identifiers/> element within
the persistence-unit-defaults element of the object/relational xml
mapping file. If the <delimited-identifiers/> element is specified, it
cannot be overridden.

It is possible to specify on a per-name basis
that a name for a database object is to be interpreted as a delimited
identifier as follows:

Using annotations, a name is specified as a
delimited identifier by enclosing the name within double quotes, whereby
the inner quotes are escaped, e.g., @Table(name="\"customer\"").

When using XML, a name is specified as
a delimited identifier by use of double quotes, e.g., <table name="&quot;customer&quot;"/> [31]

The following annotations contain elements
whose values correspond to names of database identifiers and for which
the above rules apply, including when their use is nested within that of
other annotations:

EntityResult(discriminatorColumn element)

FieldResult(column element)

ColumnResult(name element)

CollectionTable(name, catalog, schema elements)

Column(name, columnDefinition, table elements)

DiscriminatorColumn(name, columnDefinition elements)

ForeignKey(name, foreignKeyDefinition elements)

Index(name, columnList elements)

JoinColumn(name, referencedColumnName, columnDefinition, table elements)

JoinTable(name, catalog, schema elements)

MapKeyColumn(name, columnDefinition, table elements)

MapKeyJoinColumn(name, referencedColumnName, columnDefinition, table elements)

NamedStoredProcedureQuery(procedureName element)

OrderColumn(name, columnDefinition elements)

PrimaryKeyJoinColumn(name, referencedColumnName, columnDefinition elements)

SecondaryTable(name, catalog, schema elements)

SequenceGenerator(sequenceName, catalog, schema elements)

StoredProcedureParameter(name element)

Table(name, catalog, schema elements)

TableGenerator(table, catalog, schema, pkColumnName, valueColumnName elements)

UniqueConstraint(name, columnNames elements)

The following XML elements and types contain
elements or attributes whose values correspond to names of database
identifiers and for which the above rules apply:

entity-mappings(schema, catalog elements)

persistence-unit-defaults(schema, catalog elements)

collection-table(name, catalog, schema attributes)

column(name, table, column-definition attributes)

column-result(name attribute)

discriminator-column(name, column-definition attributes)

entity-result(discriminator-column attribute)

field-result(column attribute)

foreign-key(name, foreign-key-definition attributes)

index(name attribute, column-list element)

join-column(name, referenced-column-name, column-definition, table attributes)

join-table(name, catalog, schema attributes)

map-key-column(name, column-definition, table attributes)

map-key-join-column(name, referenced-column-name, column-definition, table attributes)

named-stored-procedure-query(procedure-name attribute)

order-column(name, column-definition attributes)

primary-key-join-column(name, referenced-column-name, column-definition attributes)

secondary-table(name, catalog, schema attributes)

sequence-generator(sequence-name, catalog, schema attributes)

stored-procedure-parameter(name attribute)

table(name, catalog, schema attributes)

table-generator(table, catalog, schema, pk-column-name, value-column-name attributes)

unique-constraint(name attribute, column-name element)
