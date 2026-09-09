# 4. Query Language (part 1/3)

The Jakarta Persistence query language is a
string-based query language used to define queries over entities and
their persistent state. It enables the application developer to specify
the semantics of queries in a portable way, independent of the
particular database schema in use in an enterprise environment. The full
range of the language may be used in both static and dynamic queries.

This chapter provides the full definition of
the Jakarta Persistence query language.

4.1. Overview

The Jakarta Persistence query language is a
query specification language for string-based dynamic queries and static
queries expressed through metadata. It is used to define queries over
the persistent entities defined by this specification and their
persistent state and relationships.

The Jakarta Persistence query language can be
compiled to a target language, such as SQL, of a database or other
persistent store. This allows the execution of queries to be shifted to
the native language facilities provided by the database, instead of
requiring queries to be executed on the runtime representation of the
entity state. As a result, query methods can be optimizable as well as
portable.

The query language uses the abstract
persistence schema of entities, including their embedded objects and
relationships, for its data model, and it defines operators and
expressions based on this data model. It uses a SQL-like syntax to
select objects or values based on abstract schema types and
relationships. It is possible to parse and validate queries before
entities are deployed.

The term abstract persistence schema refers
to the persistent schema abstraction (persistent entities, their state,
and their relationships) over which Jakarta Persistence queries operate.
Queries over this persistent schema abstraction are translated into
queries that are executed over the database schema to which entities are
mapped.

Queries may be defined in metadata
annotations or the XML descriptor. The abstract schema types of a set of
entities can be used in a query if the entities are defined in the same
persistence unit as the query. Path expressions allow for navigation
over relationships defined in the persistence unit.

A persistence unit defines the set of all
classes that are related or grouped by the application and which must be
colocated in their mapping to a single database.

4.2. Statement Types

A Jakarta Persistence query language statement
may be either a select statement, an update statement, or a delete
statement.

This chapter refers to all such statements as
“queries”. Where it is important to distinguish among statement types,
the specific statement type is referenced.

In BNF syntax, a query language statement is
defined as:

QL_statement ::= select_statement | update_statement | delete_statement

Any Jakarta Persistence query language statement
may be constructed dynamically or may be statically defined in a
metadata annotation or XML descriptor element.

All statement types may have parameters.

4.2.1. Select Statements

A select query is a string with the following clauses:

a SELECT clause, which determines the type of
the objects or values to be selected.

a FROM clause, which provides declarations
that designate the domain to which the expressions specified in the
other clauses of the query apply.

an optional WHERE clause, which may be used
to restrict the results that are returned by the query.

an optional GROUP BY clause, which allows
query results to be aggregated in terms of groups.

an optional HAVING clause, which allows
filtering over aggregated groups.

an optional ORDER BY clause, which may be
used to order the results that are returned by the query.

In BNF syntax, a select query is defined by:

select_query ::= [select_clause]? from_clause [where_clause] [groupby_clause] [having_clause] [orderby_clause]

Every select statement has a FROM clause. The square brackets [] in the
BNF indicate that the other clauses are optional.

4.2.1.1. Set Operators in Select Statements

A select statement may be a single select query, or it may combine
multiple select queries using the binary left-associative operators
UNION, UNION ALL, INTERSECT, INTERSECT ALL, EXCEPT, and
EXCEPT ALL. The semantics of these operators are identical to SQL.
[62]

The full syntax for a select statement is defined by:

select_statement ::= union
union ::= intersection | union {UNION [ALL] | EXCEPT [ALL]} intersection
intersection ::= query_expression | intersection INTERSECT [ALL] query_expression
query_expression ::= select_query | (union)

A provider is only required to support select statements where every
constituent select query has the same number of items in the select
clause, and where corresponding items in the select clauses of the
constituent select queries either:

have exactly the same type, as defined by Section 4.9.1, or

are entity types which inherit a common entity type, as defined
by Section 2.13.

4.2.2. Update and Delete Statements

Update and delete statements provide bulk
operations over sets of entities.

In BNF syntax, these operations are defined by:

update_statement ::= update_clause [where_clause]
delete_statement ::= delete_clause [where_clause]

The update and delete clauses determine the
type of the entities to be updated or deleted. The WHERE clause may be
used to restrict the scope of the update or delete operation.

Update and delete statements are described
further in Section 4.11.

4.3. Abstract Schema Types and Query Domains

The Jakarta Persistence query language is a
typed language, and every expression has a type. The type of an
expression is derived from the structure of the expression, the abstract
schema types of the identification variable declarations, the types to
which the persistent attributes evaluate, and the types of literals.

The abstract schema type of an entity or
embeddable is derived from its class and the metadata information
provided by Java language annotations or in the XML descriptor.

Informally, the abstract schema type of an
entity or embeddable can be characterized as follows:

For every non-relationship persistent field
or get accessor method (for a persistent property) of the class, there
is a field (“state field”) whose abstract schema type corresponds to
that of the field or the result type of the accessor method.

For every persistent relationship field or
get accessor method (for a persistent relationship property) of the
class, there is a field (“association field”) whose type is the abstract
schema type of the related entity (or, if the relationship is a
one-to-many or many-to-many, a collection of such).

Abstract schema types are specific to the
query language data model. The persistence provider is not required to
implement or otherwise materialize an abstract schema type.

The domain of a query consists of the
abstract schema types of all entities and embeddables that are defined
in the same persistence unit.

The domain of a query may be restricted by
the navigability of the relationships of the entity and associated
embeddable classes on which it is based. The association fields of an
entity’s or embeddable’s abstract schema type determine navigability.
Using the association fields and their values, a query can select
related entities and use their abstract schema types in the query.

4.3.1. Naming

Entities are designated in query strings by
their entity names. The entity name is defined by the name element of
the Entity annotation (or the entity-name XML descriptor element),
and defaults to the unqualified name of the entity class. Entity names
are scoped within the persistence unit and must be unique within the
persistence unit.

4.3.2. Example

This example assumes that the application
developer provides several entity classes, representing orders,
products, and line items, and an embeddable address class representing
shipping addresses and billing addresses. The abstract schema types for
the entities are Order, Product, and LineItem respectively.
There is a one-to-many relationship between Order and LineItem. The
entity LineItem is related to Product in a many-to-one relationship.
The classes are logically in the same persistence unit, as shown in
Figure 1.

Queries to select orders can be defined by
navigating over the association fields and state fields defined by
Order and LineItem. A query to find all orders with pending line
items might be written as follows:

SELECT DISTINCT o
FROM Order AS o JOIN o.lineItems AS l
WHERE l.shipped = FALSE

Figure 1. Abstract persistence schema of several entities defined in the same persistence unit.

This query navigates over the association
field lineItems of the abstract schema type Order to find line
items, and uses the state field shipped of LineItem to select those
orders that have at least one line item that has not yet shipped. (Note
that this query does not select orders that have no line items.)

Although reserved identifiers, such as
DISTINCT, FROM, AS, JOIN, WHERE, and FALSE appear in upper
case in this example, reserved identifiers are case
insensitive.[63]

The SELECT clause of this example designates
the return type of this query to be of type Order.

Because the same persistence unit defines the
abstract persistence schema of the related entities, the developer can
also specify a query over orders that utilizes the abstract schema type
for products, and hence the state fields and association fields of both
the abstract schema types Order and Product. For example, if the
abstract schema type Product has a state field named productType, a
query over orders can be specified using this state field. Such a query
might be to find all orders for products with product type office
supplies. A query for this might be as follows.

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l JOIN l.product p
WHERE p.productType = 'office_supplies'

Because Order is related to Product by
means of the relationships between Order and LineItem and between
LineItem and Product, navigation using the association fields
lineItems and product is used to express the query. This query is
specified by using the entity name Order, which designates the
abstract schema type over which the query ranges. The basis for the
navigation is provided by the association fields lineItems and
product of the abstract schema types Order and LineItem respectively.

4.4. The FROM Clause and Navigational Declarations

The FROM clause of a query defines the domain of the query:

one or more named entity abstract schema types, as specified below
in Section 4.4.3, together with

zero or more joined associations and collections, as specified
below in Section 4.4.5.

An identification variable is an identifier declared in the FROM
clause of a query. Each identification variable is assigned an
abstract schema type. Each element of the domain may declare an
identification variable.

If the domain has exactly one named entity abstract schema type
and no joins, then the named entity does not require an explicit
identification variable, and its identification variable defaults
to the implicit identification variable, this.

Otherwise, every element of the FROM clause—​that is, every
named entity abstract schema types and every join—​must
declare an identification variable.

from_clause ::=
 FROM {this_implicit_variable | identification_variable_declarations}

this_implicit_variable ::= entity_name

identification_variable_declarations ::=
 identification_variable_declaration
 {, {identification_variable_declaration | collection_member_declaration}}*

identification_variable_declaration ::= range_variable_declaration {join | fetch_join}*

range_variable_declaration ::= entity_name [AS] identification_variable

join ::= range_join | path_join

range_join ::= join_spec range_variable_declaration [join_condition]

path_join ::=
 join_spec join_association_path_expression [AS] identification_variable [join_condition]

fetch_join ::= join_spec FETCH join_association_path_expression

join_spec ::= [INNER | LEFT [OUTER]] JOIN

join_association_path_expression ::=
 join_collection_valued_path_expression |
 join_single_valued_path_expression |
 TREAT(join_collection_valued_path_expression AS subtype) |
 TREAT(join_single_valued_path_expression AS subtype)

join_collection_valued_path_expression ::= [identification_variable.]{single_valued_embeddable_object_field.}*collection_valued_field

join_single_valued_path_expression ::= [identification_variable.]{single_valued_embeddable_object_field.}*single_valued_object_field

join_condition ::= ON conditional_expression

collection_member_declaration ::= IN (collection_valued_path_expression) [AS] identification_variable

The following subsections discuss the constructs used in the FROM clause.

4.4.1. Identifiers

An identifier is a character sequence of
unlimited length. The character sequence must begin with a Java
identifier start character, and all other characters must be Java
identifier part characters. An identifier start character is any
character for which the method Character.isJavaIdentifierStart returns
true. This includes the underscore (_) character and the dollar sign
($) character. An identifier part character is any character for
which the method Character.isJavaIdentifierPart returns true. The
question mark (?) character is reserved for use by the Jakarta
Persistence query language.

The following[64] are reserved identifiers: ABS, ALL, AND, ANY,
AS, ASC, AVG, BETWEEN, BIT_LENGTH, BOTH, BY, CASE,
CEILING, CHAR_LENGTH, CHARACTER_LENGTH, CLASS, COALESCE,
CONCAT, COUNT, CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP,
DELETE, DESC, DISTINCT, ELSE, EMPTY, END, ENTRY, ESCAPE,
EXISTS, EXP, EXTRACT, FALSE, FETCH, FIRST, FLOOR, FROM,
FUNCTION, GROUP, HAVING, IN, INDEX, INNER, IS, JOIN,
KEY, LEADING, LAST, LEFT, LENGTH, LIKE, LOCAL, LN,
LOCATE, LOWER, MAX, MEMBER, MIN, MOD, NEW, NOT, NULL,
NULLS, NULLIF, OBJECT, OF, ON, OR, ORDER, OUTER,
POSITION, POWER, REPLACE, RIGHT, ROUND, SELECT, SET,
SIGN, SIZE, SOME, SQRT, SUBSTRING, SUM, THEN, TRAILING,
TREAT, TRIM, TRUE, TYPE, UNKNOWN, UPDATE, UPPER, VALUE,
WHEN, WHERE.

Reserved identifiers are case-insensitive.
Reserved identifiers must not be used as identification variables or
result variables (see Section 4.9).

It is recommended that SQL keywords other
than those listed above not be used as identification variables in
queries because they may be used as reserved identifiers in future
releases of this specification.

4.4.2. Identification Variables

An identification variable is a valid identifier declared in the FROM
clause of a query.

Every identification variable must be declared in the FROM clause,
except for the implicit identification variable this. Identification
variables are never declared in other clauses.

An identification variable must not be a reserved identifier.

An identification variable may have the same name as an entity.

Identification variables are case-insensitive.

An identification variable evaluates to a
value of the type of the expression used in declaring the variable. For
example, consider the previous query:

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l JOIN l.product p
WHERE p.productType = 'office_supplies'

In the FROM clause declaration o.lineItems
l, the identification variable l evaluates to any LineItem value
directly reachable from Order. The association field lineItems is a
collection of instances of the abstract schema type LineItem and the
identification variable l refers to an element of this collection. The
type of l is the abstract schema type of LineItem.

An identification variable can range over an
entity, embeddable, or basic abstract schema type. An identification
variable designates an instance of an abstract schema type or an element
of a collection of abstract schema type instances.

Note that for identification variables
referring to an instance of an association or collection represented as
a java.util.Map, the identification variable is of the abstract
schema type of the map value.

An identification variable always designates
a reference to a single value. It is declared in one of three ways: in a
range variable declaration, in a join clause, or in a collection member
declaration. The identification variable declarations are evaluated from
left to right in the FROM clause, and an identification variable
declaration can use the result of a preceding identification variable
declaration of the query string.

All identification variables used in the
SELECT, WHERE, ORDER BY, GROUP BY, or HAVING clause of a SELECT or
DELETE statement must be declared in the FROM clause. The identification
variables used in the WHERE clause of an UPDATE statement must be
declared in the UPDATE clause.

Identification variables are existentially
quantified in these clauses. This means that an identification variable
represents a member of a collection or an instance of an entity’s
abstract schema type. An identification variable never designates a
collection in its entirety.

An identification variable is scoped to the
query (or subquery) in which it is defined and is also visible to any
subqueries within that query scope that do not define an identification
variable of the same name.

4.4.3. Range Variable Declarations

A range variable declaration introduces a query domain element ranging
over a given named entity abstract schema type, with an associated
identification variable.

The syntax for declaring an identification variable as a range variable
is similar to that of SQL; optionally, it may use the AS keyword. A
range variable declaration designates an entity abstract schema type by
its entity name, as defined above in Section 4.3.1.[65]

range_variable_declaration ::= entity_name [AS] identification_variable

The entity name in a range variable declaration is case-sensitive.

Range variable declarations allow the
developer to designate a “root” for objects which may not be reachable
by navigation.

In order to select values by comparing more
than one instance of an entity abstract schema type, more than one
identification variable ranging over the abstract schema type is needed
in the FROM clause.

The following query returns orders whose
quantity is greater than the order quantity for John Smith. This example
illustrates the use of two different identification variables in the
FROM clause, both of the abstract schema type Order. The SELECT clause
of this query determines that it is the orders with quantities larger
than John Smith’s that are returned.

SELECT DISTINCT o1
FROM Order o1, Order o2
WHERE o1.quantity > o2.quantity AND
 o2.customer.lastname = 'Smith' AND
 o2.customer.firstname= 'John'

If the query domain is a single entity abstract schema type, the range
variable declaration is optional. These queries are equivalent:

SELECT quantity
FROM Order
WHERE customer.lastname = 'Smith'
 AND customer.firstname= 'John'

SELECT this.quantity
FROM Order
WHERE this.customer.lastname = 'Smith'
 AND this.customer.firstname= 'John'

SELECT ord.quantity
FROM Order AS ord
WHERE ord.customer.lastname = 'Smith'
 AND ord.customer.firstname= 'John'

Otherwise, if the query domain has more than one element, each named
entity abstract schema type listed in the FROM clause must be a range
variable declaration, and the implicit identification variable is not
implicitly assigned an abstract schema type.

4.4.4. Path Expressions

A path expression is a sequence of identifiers uniquely identifying
a state field or association field of an element of the query domain.

A path expression may begin with a reference to an identification
variable, followed by the navigation operator (.). If the first
element of a path expression is not an identification variable, then
the path expression is interpreted exactly as if it began with the
implicit identification variable this.

The remaining elements of the path expression are interpreted as
references to state fields or association fields in the context of the
abstract schema type assigned to the identification variable—​or
to this, if the path expression does not begin with an identification
variable.

A reference to a state field or association field in a path expression
is case-sensitive.

The type of the path expression is the type computed as
the result of navigation; that is, the type of the state field or
association field to which the expression navigates. The type of a path
expression that navigates to an association field may be specified as a
subtype of the declared type of the association field by means of the
TREAT operator. See Section 4.4.9.

An identification variable qualified
by the KEY, VALUE, or ENTRY operator is a path expression. The KEY,
VALUE, and ENTRY operators may only be applied to identification
variables that correspond to map-valued associations or map-valued
element collections. The type of the path expression is the type
computed as the result of the operation; that is, the abstract schema
type of the field that is the value of the KEY, VALUE, or ENTRY operator
(the map key, map value, or map entry
respectively).[66]

In the following query, photos is a map from photo label to filename.

SELECT i.name, VALUE(p)
FROM Item i JOIN i.photos p
WHERE KEY(p) LIKE '%egret'

In the above query the identification
variable p designates an abstract schema type corresponding to the map
value. The results of VALUE(p) and KEY(p) are the map value and
the map key associated with p, respectively. The following query is
equivalent:

SELECT i.name, p
FROM Item i JOIN i.photos p
WHERE KEY(p) LIKE '%egret'

A path expression using the KEY or VALUE
operator can be further composed. A path expression using the ENTRY
operator is terminal. It cannot be further composed and can only appear
in the SELECT list of a query.

The syntax for qualified identification variables is as follows.

qualified_identification_variable ::=
 map_field_identification_variable |
 ENTRY(identification_variable)

map_field_identification_variable ::=
 KEY(identification_variable) |
 VALUE(identification_variable)

Depending on navigability, a path expression
that leads to an association field or to a field whose type is an
embeddable class may be further composed. Path expressions can be
composed from other path expressions if the original path expression
evaluates to a single-valued type (not a collection).

In the following example, simple data model with Employee, ContactInfo,
Address and Phone classes is used:

@Entity
public class Employee {
 @Id int id;
 @Embedded
 private ContactInfo contactInfo;
}

@Entity
public class Phone {
 @Id
 private int id;
 private String vendor;
}

@Embeddable
public class ContactInfo {
 @Embedded
 private Address address;
 @ManyToMany
 private List<Phone> phones;
}

@Embeddable
public class Address {
 private String street;
 private String city;
 private String state;
 private String zipcode;
}

The contactInfo field denotes an embeddable class consisting of an address and set of phones.

SELECT p.vendor
FROM Employee e JOIN e.contactInfo.phones p
WHERE e.contactInfo.address.zipcode = '95054'

Path expression navigability is composed
using “inner join” semantics. That is, if the value of a non-terminal
field in the path expression is null, the path is considered to have no
value, and does not participate in the determination of the result.

The following query is equivalent to the
query above:

SELECT p.vendor
FROM Employee e JOIN e.contactInfo c JOIN c.phones p
WHERE e.contactInfo.address.zipcode = '95054'

4.4.4.1. Path Expression Syntax

The syntax for single-valued path expressions
and collection-valued path expressions is as follows.

An identification variable used in a
single_valued_object_path_expression or in a
collection_valued_path_expression may be an unqualified identification
variable or an identification variable to which the KEY or VALUE
function has been applied.

general_identification_variable ::=
 identification_variable |
 map_field_identification_variable

The type of an entity-valued path expression
or an entity-valued subpath of a path expression used in a WHERE clause
may be specified as a subtype of the corresponding declared type by
means of the TREAT operator. See Section 4.4.9.

general_subpath ::= simple_subpath | treated_subpath{.single_valued_object_field}*

simple_subpath ::=
 general_identification_variable |
 general_identification_variable{.single_valued_object_field}*

treated_subpath ::= TREAT(general_subpath AS subtype)

single_valued_path_expression ::=
 qualified_identification_variable |
 TREAT(qualified_identification_variable AS subtype) |
 state_field_path_expression |
 single_valued_object_path_expression

state_field_path_expression ::= [general_subpath.]state_field

state_valued_path_expression ::= state_field_path_expression | general_identification_variable

single_valued_object_path_expression ::= general_subpath.single_valued_object_field

collection_valued_path_expression ::= general_subpath.collection_valued_field

A single_valued_object_field is designated by the name of an association
field in a one-to-one or many-to-one relationship or a field of
embeddable class type. The type of a single_valued_object_field
is the abstract schema type of the related
entity or embeddable class.

A single_valued_embeddable_object_field is designated by the name
of a field of embeddable class type.

A state_field is designated by the name of
an entity or embeddable class state field that corresponds to a basic
type.

A collection_valued_field is designated by the name of an association
field in a one-to-many or a many-to-many relationship or by the name of
an element collection field. The type of a collection_valued_field is
a collection of values of the abstract schema type of the related entity
or element type.

It is syntactically illegal to compose a path
expression from a path expression that evaluates to a collection. For
example, if o designates Order, the path expression o.lineItems.product
is illegal since navigation to lineItems results in a collection. This
case should produce an error when the query string is verified. To
handle such a navigation, an identification variable must be declared in
the FROM clause to range over the elements of the lineItems
collection. Another path expression must be used to navigate over each
such element in the WHERE clause of the query, as in the following:

SELECT DISTINCT l.product
FROM Order AS o JOIN o.lineItems l

A collection_valued_path_expression may only occur in:

the FROM clause of a query,

an empty_collection_comparison_expression,

a collection_member_expression, or

as an argument to the SIZE operator.

See Section 4.6.8, Section 4.6.9, and Section 4.7.7.2.

4.4.5. Joins

JPQL defines the following varieties of join:

inner joins, and.

left outer joins.[67]

The semantics of each variety of join is identical to SQL, and the
syntax is borrowed from ANSI SQL.

Every join has a target, either:

an entity-valued path expression, or

an entity type (that is, range variable declaration, as already
specified in Section 4.4.3).

An inner join may be implicitly specified by the use of a cartesian
product in the FROM clause and a join condition in the WHERE clause.
In the absence of a join condition, this reduces to the cartesian
product.

The main use case for this generalized style of join is when a join
condition does not involve a foreign key relationship mapped to an
association between entities.

Example:

SELECT c FROM Customer c, Employee e WHERE c.hatsize = e.shoesize

This style of inner join (sometimes called a "theta" join) is less
typical than explicitly defined joins over relationships.

The syntax for explicit join operations is given by:

join ::= range_join | path_join

range_join ::= join_spec range_variable_declaration [join_condition]

path_join ::=
 join_spec join_association_path_expression [AS] identification_variable [join_condition]

fetch_join ::= join_spec FETCH join_association_path_expression

join_spec ::= [INNER | LEFT [OUTER]] JOIN

join_association_path_expression ::=
 join_collection_valued_path_expression |
 join_single_valued_path_expression |
 TREAT(join_collection_valued_path_expression `AS` subtype) |
 TREAT(join_single_valued_path_expression AS subtype)

join_collection_valued_path_expression ::=
 [identification_variable.]{single_valued_embeddable_object_field.}*collection_valued_field

join_single_valued_path_expression ::=
 [identification_variable.]{single_valued_embeddable_object_field.}*single_valued_object_field

join_condition ::= ON conditional_expression

The inner and outer join operation types described in Section 4.4.5.1, Section 4.4.5.2,
and Section 4.4.5.3 are supported.

4.4.5.1. Inner Joins

The syntax for an inner join to an entity type is given by:

[INNER] JOIN range_variable_declaration [join_condition]

The keyword INNER is optional and does not affect the semantics
of the query.

SELECT c
FROM Customer c
 JOIN Order o ON o.customer.id = c.id
WHERE c.status = 1

Or, equivalently:

SELECT c
FROM Customer c
 INNER JOIN Order o ON o.customer.id = c.id
WHERE c.status = 1

These queries are equivalent to the following query involving
an implicit "theta" join:

SELECT c
FROM Customer c, Order o
WHERE o.customer.id = c.id AND c.status = 1

The syntax for an inner join over an association is given by:

[INNER] JOIN join_association_path_expression [AS] identification_variable [join_condition]

For example, the query below joins over the relationship between
customers and orders. This type of join typically equates to a
join over a foreign key relationship in the database.

SELECT c
FROM Customer c
 JOIN c.orders o
WHERE c.status = 1

Equivalently:

SELECT c
FROM Customer c
 INNER JOIN c.orders o
WHERE c.status = 1

This is equivalent to the following query using the earlier IN
construct, defined in [4]. It selects those customers of
status 1 for which at least one order exists:

SELECT OBJECT(c)
FROM Customer c, IN(c.orders) o
WHERE c.status = 1

The query below joins over Employee, ContactInfo and Phone.
ContactInfo is an embeddable class that consists of an address
and set of phones. Phone is an entity.

SELECT p.vendor
FROM Employee e JOIN e.contactInfo c JOIN c.phones p
WHERE c.address.zipcode = '95054'

A join condition may be specified for an inner join. This is equivalent
to specification of the same condition in the WHERE clause.

4.4.5.2. Outer Joins

The syntax for an outer join to an entity type is given by:

LEFT [OUTER] JOIN range_variable_declaration [join_condition]

The keyword OUTER is optional and does not affect the semantics of
the query.

SELECT c
FROM Customer c
 LEFT JOIN Order o ON o.customer.id = c.id
WHERE c.status = 1

Or, equivalently:

SELECT c
FROM Customer c
 LEFT OUTER JOIN Order o ON o.customer.id = c.id
WHERE c.status = 1

Outer joins enable the retrieval of a set of entities where matching
values in the join condition may be absent. For example, the queries
above return Customer instances with no matching Order.

The syntax for an outer join over an association is given by:

LEFT [OUTER] JOIN join_association_path_expression [AS] identification_variable [join_condition]

An association outer join without no explicit join_condition has an
implicit join condition inferred from the foreign key relationship
mapped by the join_association_path_expression. Typically, a JPQL
join of this form is translated to a SQL outer join with an ON condition
specifying the foreign key relationship, as in the following examples.

Jakarta Persistence query language:

SELECT s.name, COUNT(p)
FROM Suppliers s LEFT JOIN s.products p
GROUP BY s.name

SQL:

SELECT s.name, COUNT(p.id)
FROM Suppliers s LEFT JOIN Products p
 ON s.id = p.supplierId
GROUP By s.name

An explicit join_condition (that is, an ON condition in the JOIN)
results in an additional restriction in the ON condition of the
generated SQL.

Jakarta Persistence query language:

SELECT s.name, COUNT(p)
FROM Suppliers s LEFT JOIN s.products p
 ON p.status = 'inStock'
GROUP BY s.name

SQL:

SELECT s.name, COUNT(p.id)
FROM Suppliers s LEFT JOIN Products p
 ON s.id = p.supplierId AND p.status = 'inStock'
GROUP BY s.name

Note that the result of this query will be different from that of the
following query:

SELECT s.name, COUNT(p)
FROM Suppliers s LEFT JOIN s.products p
WHERE p.status = 'inStock'
GROUP BY s.name

The result of the latter query will exclude suppliers who have no
products in stock whereas the former query will include them.

An important use case for LEFT JOIN is in enabling the prefetching of
related data items as a side effect of a query. This is accomplished by
specifying the LEFT JOIN as a FETCH JOIN, as described below.

4.4.5.3. Fetch Joins

A FETCH JOIN clause in a query results in eager fetching of an association
or element collection as a side effect of execution of the query.

The syntax for a fetch join is given by:

fetch_join ::= [LEFT [OUTER] | INNER] JOIN FETCH join_association_path_expression

A FETCH JOIN must be an INNER or LEFT (OUTER) join. A FETCH JOIN does not
have an explicit join condition or identification variable.

The association referenced by the right side
of the FETCH JOIN clause must be an association or element collection
that is referenced from an entity or embeddable that is returned as a
result of the query. It is not permitted to specify an identification
variable for the objects referenced by the right side of the FETCH JOIN
clause, and hence references to the implicitly fetched entities or
elements cannot appear elsewhere in the query.

The following query returns a set of
departments. As a side effect, the associated employees for those
departments are also retrieved, even though they are not part of the
explicit query result. The initialization of the persistent state or
relationship fields or properties of the objects that are retrieved as a
result of a fetch join is determined by the metadata for that class—in
this example, the Employee entity class.

SELECT d
FROM Department d LEFT JOIN FETCH d.employees
WHERE d.deptno = 1

A fetch join has the same join semantics as
the corresponding inner or outer join, except that the related objects
specified on the right-hand side of the join operation are not returned
in the query result or otherwise referenced in the query. Hence, for
example, if department 1 has five employees, the above query returns
five references to the department 1 entity.

The FETCH JOIN construct must not be used in
the FROM clause of a subquery.

4.4.6. Collection Member Declarations

An identification variable declared by a
collection_member_declaration ranges over values of a collection
obtained by navigation using a path expression.

An identification variable of a collection
member declaration is declared using a special operator, the reserved
identifier IN. The argument to the IN operator is a collection-valued
path expression. The path expression evaluates to a collection type
specified as a result of navigation to a collection-valued association
field of an entity or embeddable class abstract schema type.

The syntax for declaring a collection member
identification variable is as follows:

collection_member_declaration ::=
 IN (collection_valued_path_expression) [AS] identification_variable

For example, the query

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l
WHERE l.product.productType = 'office_supplies'

can equivalently be expressed as follows, using the IN operator:

SELECT DISTINCT o
FROM Order o, IN(o.lineItems) l
WHERE l.product.productType = 'office_supplies'

In this example, lineItems is the name of an
association field whose value is a collection of instances of the
abstract schema type LineItem. The identification variable l
designates a member of this collection, a single LineItem abstract
schema type instance. In this example, o is an identification variable
of the abstract schema type Order.

4.4.7. FROM Clause and SQL

The Jakarta Persistence query language treats
the FROM clause similarly to SQL in that the declared identification
variables affect the results of the query even if they are not used in
the WHERE clause. Application developers should use caution in defining
identification variables because the domain of the query can depend on
whether there are any values of the declared type.

For example, the FROM clause below defines a
query over all orders that have line items and existing products. If
there are no Product instances in the database, the domain of the
query is empty and no order is selected.

SELECT o
FROM Order AS o JOIN o.lineItems l JOIN l.product p

4.4.8. Polymorphism

Jakarta Persistence queries are automatically
polymorphic. The FROM clause of a query designates not only instances of
the specific entity class(es) to which it explicitly refers but
instances of subclasses of those classes as well. The instances returned
by a query thus include instances of the subclasses that satisfy the
query criteria.

Non-polymorphic queries or queries whose
polymorphism is restricted can be specified using entity type
expressions in the WHERE clause to restrict the domain of the query. See
Section 4.7.12.

4.4.9. Downcasting

The use of the TREAT operator is supported
for downcasting within path expressions in the FROM and WHERE clauses.
Use of the TREAT operator allows access to subclass-specific state.

If during query execution the first argument
to the TREAT operator is not a subtype (proper or improper) of the
target type, the path is considered to have no value, and does not
participate in the determination of the result. That is, in the case of
a join, the referenced object does not participate in the result, and in
the case of a restriction, the associated predicate is false. Use of the
TREAT operator therefore also has the effect of filtering on the
specified type (and its subtypes) as well as performing the downcast. If
the target type is not a subtype (proper or improper) of the static type
of the first argument, the query is invalid.

Examples:

SELECT b.name, b.ISBN
FROM Order o JOIN TREAT(o.product AS Book) b

SELECT e FROM Employee e JOIN TREAT(e.projects AS LargeProject) lp
WHERE lp.budget > 1000

SELECT e FROM Employee e JOIN e.projects p
WHERE TREAT(p AS LargeProject).budget > 1000
 OR TREAT(p AS SmallProject).name LIKE 'Persist%'
 OR p.description LIKE "cost overrun"

SELECT e FROM Employee e
WHERE TREAT(e AS Exempt).vacationDays > 10
 OR TREAT(e AS Contractor).hours > 100

4.5. WHERE Clause

The WHERE clause of a query consists of a
conditional expression used to select objects or values that satisfy the
expression. The WHERE clause restricts the result of a select statement
or the scope of an update or delete operation.

A WHERE clause is defined as follows:

where_clause ::= WHERE conditional_expression

The GROUP BY construct enables the
aggregation of values according to the properties of an entity class.
The HAVING construct enables conditions to be specified that further
restrict the query result as restrictions upon the groups.

The syntax of the HAVING clause is as follows:

having_clause ::= HAVING conditional_expression

The GROUP BY and HAVING constructs are
further discussed in Section 4.8.

4.6. Conditional Expressions

The following sections describe language
constructs that can be used in a conditional expression of the WHERE
clause, the HAVING clause, or in an ON condition.

State fields that are mapped in serialized
form or as lobs cannot be portably used in conditional
[68].

4.6.1. Conditional Expression Composition

Conditional expressions are composed of other
conditional expressions, comparison operations, logical operations, path
expressions that evaluate to boolean values, boolean literals, and
boolean input parameters.

The scalar expressions described in Section 4.7 can be used in
conditional expressions.

Aggregate functions can only be used in
conditional expressions in a HAVING clause. See Section 4.8.

Standard bracketing () for ordering expression evaluation is supported.

Conditional expressions are defined as follows:

conditional_expression ::= conditional_term | conditional_expression OR conditional_term
conditional_term ::= conditional_factor | conditional_term AND conditional_factor
conditional_factor ::= [NOT] conditional_primary
conditional_primary ::= simple_cond_expression | (conditional_expression)
simple_cond_expression ::=
 comparison_expression |
 between_expression |
 in_expression |
 like_expression |
 null_comparison_expression |
 empty_collection_comparison_expression |
 collection_member_expression |
 exists_expression

4.6.2. Operators and Operator Precedence

The operators are listed below in order of decreasing precedence.

Navigation operator (.)

Arithmetic operators:

+, - unary

*, / multiplication and division

+, - addition and subtraction

String concatenation (||)

Comparison operators: =, >, >=, <
, <=, <> (not equal), [NOT] BETWEEN, [NOT] LIKE, [NOT]
IN, IS [NOT] NULL, IS [NOT] EMPTY, [NOT] MEMBER
[OF], [NOT] EXISTS

Logical operators:

NOT

AND

OR

The following sections describe operators used in specific expressions.

4.6.3. Comparison Expressions

The syntax for the use of comparison
expressions in a conditional expression is as
follows[69]:

comparison_expression ::=
 string_expression comparison_operator {string_expression | all_or_any_expression} |
 boolean_expression {= | <>} {boolean_expression | all_or_any_expression} |
 enum_expression {= | <>} {enum_expression | all_or_any_expression} |
 datetime_expression comparison_operator
 {datetime_expression | all_or_any_expression} |
 entity_expression {= | <>} {entity_expression | all_or_any_expression} |
 arithmetic_expression comparison_operator
 {arithmetic_expression | all_or_any_expression} |
 entity_id_or_version_function {= | <>} input_parameter |
 entity_type_expression {= | <>} entity_type_expression}

comparison_operator ::= = | > | >= | < | <= | <>

Examples:

item.cost * 1.08 <= 100.00
CONCAT(person.lastName, ', ', person.firstName)) = 'Jones, Sam'
TYPE(e) = ExemptEmployee

4.6.4. Between Expressions

The syntax for the use of the comparison operator [NOT] BETWEEN in a
conditional expression is as follows:

 between_expression ::=
 arithmetic_expression [NOT] BETWEEN arithmetic_expression AND arithmetic_expression |
 string_expression [NOT] BETWEEN string_expression AND string_expression |
 datetime_expression [NOT] BETWEEN datetime_expression AND datetime_expression

The BETWEEN expression

x BETWEEN y AND z

is semantically equivalent to:

y <= x AND x <= z

The rules for unknown and NULL values in
comparison operations apply. See Section 4.6.13.

Examples:

p.age BETWEEN 15 and 19 is equivalent to p.age >= 15 AND p.age <= 19

p.age NOT BETWEEN 15 and 19 is equivalent to p.age < 15 OR p.age > 19

In the following example,
transactionHistory is a list of credit card transactions defined using
an order column.

SELECT t
FROM CreditCard c JOIN c.transactionHistory t
WHERE c.holder.name = 'John Doe' AND INDEX(t) BETWEEN 0 AND 9

4.6.5. In Expressions

The syntax for the use of the comparison
operator [NOT] IN in a conditional expression is as follows:

in_expression ::=
 {state_valued_path_expression | type_discriminator} [NOT] IN
 {(in_item {, in_item}*) | (subquery) | collection_valued_input_parameter}
in_item ::= literal | single_valued_input_parameter

The state_valued_path_expression must have
a string, numeric, date, time, timestamp, or enum value.

The literal and/or input parameter values
must be like the abstract schema type of the
state_valued_path_expression in type. (See Section 4.6.14.)

The results of the subquery must be like
the abstract schema type of the state_valued_path_expression in
type. Subqueries are discussed in Section 4.6.12.

Example 1:

o.country IN ('UK', 'US', 'France')

is true for UK and false for Peru, and is equivalent to the expression

(o.country = 'UK') OR (o.country = 'US') OR (o.country = 'France')

Example 2:

o.country NOT IN ('UK', 'US', 'France')

is false for UK and true for Peru, and is equivalent to the expression

NOT ((o.country = 'UK') OR (o.country = 'US') OR (o.country = 'France'))

If an IN or NOT IN expression has a list of in_item expressions,
there must be at least one item in the list.
The value of such expressions is determined according to the
following rules:

If the state_valued_path_expression in an IN or NOT IN expression
evaluates to NULL or unknown, then the whole IN or NOT IN
expression evaluates to NULL or unknown.

Otherwise, if the state_valued_path_expression and at least one
in_item evaluate to the same value, the whole IN or NOT IN
expression evaluates to true.

Otherwise, if the value of a state_valued_path_expression
evaluates to a value distinct from the value of every in_item
expression, the whole IN or NOT IN expression evaluates to:
