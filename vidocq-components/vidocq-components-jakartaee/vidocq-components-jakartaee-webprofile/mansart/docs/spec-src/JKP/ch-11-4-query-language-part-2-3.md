# 4. Query Language (part 2/3)

false, if every in_item expression evaluates to a non-null
value, or

NULL or unknown if at least one in_item expression evaluates
to null.

The list of values may be parameterized by a collection-valued input parameter.
[70] (See Section 4.7.4.)

o.country NOT IN :countries

4.6.6. Like Expressions

The syntax for the use of the comparison
operator [NOT] LIKE in a conditional expression is as follows:

like_expression ::=
 string_expression [NOT] LIKE pattern_value [ESCAPE escape_character]

The string_expression must have a string
value. The pattern_value is a string literal or a string-valued input
parameter in which an underscore (_) stands for any single
character, a percent (%) character stands for any sequence of
characters (including the empty sequence), and all other characters
stand for themselves. The optional escape_character is a
single-character string literal or a character-valued input parameter
(i.e., char or Character) and is used to escape the special meaning
of the underscore and percent characters in pattern_value.
[71]

Examples:

address.phone LIKE '12%3' is true for '123', '12993' and false for '1234'

asentence.word LIKE 'l_se' is true for 'lose' and false for 'loose'

aword.underscored LIKE '_%' ESCAPE '\' is true for '_foo' and false for 'bar'

address.phone NOT LIKE '12%3' is false for '123' and '12993' and true for '1234'

If the value of the string_expression or
pattern_value is NULL or unknown, the value of the LIKE expression
is unknown. If the escape_character is specified and is NULL, the
value of the LIKE expression is unknown.

4.6.7. Null Comparison Expressions

The syntax for the use of the comparison
operator IS NULL in a conditional expression is as follows:

null_comparison_expression ::=
 {single_valued_path_expression | input_parameter} IS [NOT] NULL

A null comparison expression tests whether or
not the single-valued path expression or input parameter is a NULL
value.

Null comparisons over instances of embeddable
class types are not supported. Support for comparisons over embeddables
may be added in a future release of this specification.

4.6.8. Empty Collection Comparison Expressions

The syntax for the use of the comparison
operator IS EMPTY in an empty_collection_comparison_expression is as
follows:

empty_collection_comparison_expression ::=
 collection_valued_path_expression IS [NOT] EMPTY

This expression tests whether or not the
collection designated by the collection-valued path expression is empty
(i.e, has no elements).

Example:

SELECT o
FROM Order o
WHERE o.lineItems IS EMPTY

If the value of the collection-valued path
expression in an empty collection comparison expression is unknown, the
value of the empty comparison expression is unknown.

4.6.9. Collection Member Expressions

The syntax for the use of the comparison
operator MEMBER OF [72] in a
collection_member_expression is as follows:

collection_member_expression ::=
 entity_or_value_expression [NOT] MEMBER [OF] collection_valued_path_expression
entity_or_value_expression ::=
 single_valued_object_path_expression |
 state_valued_path_expression |
 simple_entity_or_value_expression
simple_entity_or_value_expression ::=
 identification_variable |
 input_parameter |
 literal

This expression tests whether the designated
value is a member of the collection specified by the collection-valued
path expression.

Expressions that evaluate to embeddable types
are not supported in collection member expressions. Support for use of
embeddables in collection member expressions may be added in a future
release of this specification.

If the collection valued path expression
designates an empty collection, the value of the MEMBER OF expression is
FALSE and the value of the NOT MEMBER OF expression is TRUE. Otherwise,
if the value of the collection_valued_path_expression
or entity_or_value_expression in the
collection member expression is NULL or unknown, the value of the
collection member expression is unknown.

Example:

SELECT p
FROM Person p
WHERE 'Joe' MEMBER OF p.nicknames

4.6.10. Exists Expressions

An EXISTS expression is a predicate that is
true only if the result of the subquery consists of one or more values
and that is false otherwise.

The syntax of an exists expression is

exists_expression ::= [NOT] EXISTS (subquery)

Example:

SELECT DISTINCT emp
FROM Employee emp
WHERE EXISTS (
 SELECT spouseEmp
 FROM Employee spouseEmp
 WHERE spouseEmp = emp.spouse)

The result of this query consists of all
employees whose spouses are also employees.

4.6.11. All or Any Expressions

An ALL conditional expression is a predicate
over a subquery that is true if the comparison operation is true for all
values in the result of the subquery or the result of the subquery is
empty. An ALL conditional expression is false if the result of the
comparison is false for at least one value of the result of the
subquery, and is unknown if neither true nor false.

An ANY conditional expression is a predicate
over a subquery that is true if the comparison operation is true for
some value in the result of the subquery. An ANY conditional expression
is false if the result of the subquery is empty or if the comparison
operation is false for every value in the result of the subquery, and is
unknown if neither true nor false. The keyword SOME is synonymous with
ANY.

The comparison operators used with ALL or ANY
conditional expressions are =, <, <=, >, >=, <>. The result of the
subquery must be like that of the other argument to the comparison
operator in type. See Section 4.6.14.

The syntax of an ALL or ANY expression is
specified as follows:

all_or_any_expression ::= {ALL | ANY | SOME} (subquery)

Example:

SELECT emp
FROM Employee emp
WHERE emp.salary > ALL (
 SELECT m.salary
 FROM Manager m
 WHERE m.department = emp.department)

The result of this query consists of all
employees whose salaries exceed the salaries of all managers in their
department.

4.6.12. Subqueries

Subqueries may be used in the WHERE or HAVING clause.[73]

The syntax for subqueries is as follows:

subquery ::= simple_select_clause subquery_from_clause [where_clause]
 [groupby_clause] [having_clause]
simple_select_clause ::= SELECT [DISTINCT] simple_select_expression
subquery_from_clause ::=
 FROM subselect_identification_variable_declaration
 {, subselect_identification_variable_declaration |
 collection_member_declaration}*
subselect_identification_variable_declaration ::=
 identification_variable_declaration |
 derived_path_expression [AS] identification_variable {join}* |
 derived_collection_member_declaration
simple_select_expression ::=
 single_valued_path_expression |
 scalar_expression |
 aggregate_expression |
 identification_variable
derived_path_expression ::=
 general_derived_path.single_valued_object_field |
 general_derived_path.collection_valued_field
general_derived_path ::=
 simple_derived_path |
 treated_derived_path{.single_valued_object_field}*
simple_derived_path ::= superquery_identification_variable{.single_valued_object_field}*
treated_derived_path ::= TREAT(general_derived_path AS subtype)
derived_collection_member_declaration ::=
 IN superquery_identification_variable.{single_valued_object_field.}*collection_valued_field

Examples:

SELECT DISTINCT emp
FROM Employee emp
WHERE EXISTS (
 SELECT spouseEmp
 FROM Employee spouseEmp
 WHERE spouseEmp = emp.spouse)

Note that some contexts in which a subquery
can be used require that the subquery be a scalar subquery (i.e.,
produce a single result). This is illustrated in the following examples
using numeric comparisons.

SELECT c
FROM Customer c
WHERE (SELECT AVG(o.price) FROM c.orders o) > 100

SELECT goodCustomer
FROM Customer goodCustomer
WHERE goodCustomer.balanceOwed < (
 SELECT AVG(c.balanceOwed)/2.0 FROM Customer c)

4.6.13. Null Values

When the target of a reference does not exist
in the database, its value is regarded as NULL. SQL NULL semantics
[2] defines the evaluation of
conditional expressions containing NULL values.

The following is a brief description of these semantics:

Comparison or arithmetic operations with a
NULL value always yield an unknown value.

Two NULL values are not considered to be
equal, the comparison yields an unknown value.

Comparison or arithmetic operations with an
unknown value always yield an unknown value.

The IS NULL and IS NOT NULL operators convert
a NULL state field or single-valued object field value into the
respective TRUE or FALSE value.

Boolean operators use three valued logic,
defined by Table 1, Table 2, and Table 3.

Table 1. Definition of the AND Operator

AND
T
F
U

T

T

F

U

F

F

F

F

U

U

F

U

Table 2. Definition of the OR Operator

OR
T
F
U

T

T

T

T

F

T

F

U

U

T

U

U

Table 3. Definition of the NOT Operator

NOT

T

F

F

T

U

U

The Jakarta Persistence query language
defines the empty string, '', as a string
with length zero, which is not equal to a NULL value. However, NULL values
and empty strings may not always be distinguished when queries are
mapped to some databases. Application developers should therefore not
rely on the semantics of query comparisons involving the empty string
and NULL value.

4.6.14. Equality and Comparison Semantics

Only the values of like types are permitted
to be compared. A type is like another type if they correspond to the
same Java language type, or if one is a primitive Java language type and
the other is the wrapped Java class type equivalent (e.g., int and
Integer are like types in this sense). There is one exception to this
rule: it is valid to compare numeric values for which the rules of
numeric promotion apply. Conditional expressions attempting to compare
non-like type values are disallowed except for this numeric case.

Note that the arithmetic operators, the string concatenation operator,
and comparison operators are permitted to be applied to state fields and
input parameters of the wrapped Java class equivalents to the primitive
numeric Java types.

Two entities of the same abstract schema type
are equal if and only if they have the same primary key value.

Only equality/inequality comparisons over
enums are required to be supported.

Comparisons over instances of embeddable
class or map entry types are not supported.

The following examples illustrate the syntax
and semantics of the Jakarta Persistence query language. These examples are
based on the example presented in Section 4.3.2.

Find all orders:

SELECT o
FROM Order o

Find all orders that need to be shipped to California:

SELECT o
FROM Order o
WHERE o.shippingAddress.state = 'CA'

Find all states for which there are orders:

SELECT DISTINCT o.shippingAddress.state
FROM Order o

Find all orders that have line items:

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l

Note that the result of this query does not
include orders with no associated line items. This query can also be
written as:

SELECT o
FROM Order o
WHERE o.lineItems IS NOT EMPTY

Find all orders that have no line items:

SELECT o
FROM Order o
WHERE o.lineItems IS EMPTY

Find all pending orders:

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l
WHERE l.shipped = FALSE

Find all orders in which the shipping address
differs from the billing address. This example assumes that the
application developer uses two distinct entity types to designate
shipping and billing addresses.

SELECT o
FROM Order o
WHERE
 NOT (o.shippingAddress.state = o.billingAddress.state AND
 o.shippingAddress.city = o.billingAddress.city AND
 o.shippingAddress.street = o.billingAddress.street)

If the application developer uses a single
entity type in two different relationships for both the shipping
address and the billing address, the above expression can be simplified
based on the equality rules defined in Section 4.6.14. The
query can then be written as:

SELECT o
FROM Order o
WHERE o.shippingAddress <> o.billingAddress

The query checks whether the same entity
abstract schema type instance (identified by its primary key) is related
to an order through two distinct relationships.

4.6.14.1. Queries Using Input Parameters

The following query finds the orders for a
product whose name is designated by an input parameter:

SELECT DISTINCT o
FROM Order o JOIN o.lineItems l
WHERE l.product.name = ?1

For this query, the input parameter must be
of the type of the state field name, i.e., a string.

4.7. Scalar Expressions

Numeric, string, datetime, case, and entity
type expressions result in scalar values.

Scalar expressions may be used in the SELECT
clause of a query as well as in the WHERE [74] and
HAVING clauses.

scalar_expression ::=
 arithmetic_expression |
 string_expression |
 enum_expression |
 datetime_expression |
 boolean_expression |
 case_expression |
 entity_type_expression |
 entity_id_or_version_function

4.7.1. Literals

A string literal is enclosed in single
quotes—for example: 'literal'. A string literal that includes a single
quote is represented by two single quotes—for example: 'literal''s'.
String literals in queries, like Java String literals, use unicode
character encoding. The use of Java escape notation is not supported in
query string literals.

A numeric literal may be either:

a decimal Java integer (int or long) literal

a Java floating point (float or double) literal, or

a literal BigInteger or BigDecimal.

A suffix L, D, or F may be used to indicate the specific numeric
type, in accordance with the Java Language Specification. The suffix is
not case-sensitive. The literal numeric value preceding the suffix must
conform to the rules for Java numeric literals established by the Java
Language Specification.

A suffix BI or BD may be used to indicate a literal BigInteger or
BigDecimal, respectively. The literal numeric value preceding the suffix
must be an exact or approximate SQL numeric literal. For a BigInteger
literal, the numeric value must be an exact integer literal.

Just as in Java, when a numeric literal has no suffix:

an integer literal is interpreted as a Java int, and

a floating point literal is interpreted as a Java double.

Support for hexadecimal and octal numeric literals is not required by
this specification.

Enum literals support the use of Java enum
literal syntax. The fully qualified enum class name must be specified.

The JDBC escape syntax may be used for the
specification of date, time, and timestamp literals. For example:

SELECT o
FROM Customer c JOIN c.orders o
WHERE c.name = 'Smith'
 AND o.submissionDate < {d '2008-12-31'}

The portability of this syntax for date,
time, and timestamp literals is dependent upon the JDBC driver in use.
Persistence providers are not required to translate from this syntax
into the native syntax of the database or driver.

The boolean literals are TRUE and FALSE.

Entity type literals are specified by entity names—for example: Customer.

Although reserved literals appear in upper case, they are case-insensitive.

4.7.2. Identification Variables

All identification variables used in the
WHERE or HAVING clause of a SELECT or DELETE statement must be declared
in the FROM clause, as described in Section 4.4.2. The identification variables used in the
WHERE clause of an UPDATE statement must be declared in the UPDATE
clause.

Identification variables are existentially
quantified in the WHERE and HAVING clause.
This means that an identification variable represents a member of a
collection or an instance of an entity’s abstract schema type. An
identification variable never designates a collection in its entirety.

4.7.3. Path Expressions

It is illegal to use a
collection_valued_path_expression within a WHERE or HAVING clause as
part of a conditional expression except in an
empty_collection_comparison_expression, in a
collection_member_expression, or as an argument to the SIZE operator.

4.7.4. Input Parameters

An input parameter allows a value in the Java program to be safely
interpolated into the text of the parameterized query.

In a given query, either positional or named parameters may be used.
Positional and named parameters must not be mixed in a single query.

The persistence provider is required to support input parameters which
occur in the WHERE clause or HAVING clause of a query, or as the
new value for an update item in the SET clause of an update statement.

Note that if an input parameter value is null, comparison operations or
arithmetic operations involving the input parameter will result in an
unknown value. See Section 4.6.13.

An input parameter might be single-valued or collection-valued.
An input parameter which occurs directly to the right of the IN keyword
in an IN predicate, as defined in Section 4.6.5, is collection-valued. Every
other input parameter is single-valued

The API for the binding concrete arguments to query parameters is described
in Section 3.11.

4.7.4.1. Positional Parameters

The following rules apply to positional input parameters.

A positional parameter is designated by an integer, and prefixed with a
? symbol (question mark) in the text of the query string.
For example: ?1.

Input parameters are numbered starting from 1.

A given positional parameter may occur more than once in the query string.

The ordering of the use of parameters within the text of the query string
need not match the numbering of the positional parameters.

4.7.4.2. Named Parameters

A named parameter is denoted by an identifier, and prefixed by the : symbol
(colon) in the text of the query string. The identifier name must follow the
usual rules for identifiers specified in Section 4.4.1. Named parameters are
case-sensitive.

Example:

SELECT c
FROM Customer c
WHERE c.status = :stat

A given named parameter may occur more than once in the query string.

4.7.5. Arithmetic Expressions

The arithmetic operators are:

+, - unary

*, / multiplication and division

+, - addition and subtraction

Arithmetic operations use numeric promotion.

Arithmetic functions are described in Section 4.7.7.2.

4.7.6. String concatenation operator

The binary concatenation operator is ||.
Its operands must be string expressions.

4.7.7. Built-in String, Arithmetic, and Datetime Functional Expressions

The Jakarta Persistence query language includes
the built-in functions described in Section 4.7.7.1, Section 4.7.7.2,
Section 4.7.7.3, which may be used
in the SELECT, WHERE or HAVING clause of a query. The invocation of
predefined database functions and user-defined database functions is
described in Section 4.7.9.

If the value of any argument to a functional
expression is null or unknown, the value of the functional expression is
unknown.

4.7.7.1. String Functions

functions_returning_strings ::=
 CONCAT(string_expression, string_expression {, string_expression}*) |
 SUBSTRING(string_expression,
 arithmetic_expression [, arithmetic_expression]) |
 TRIM([[trim_specification] [trim_character] FROM] string_expression) |
 LOWER(string_expression) |
 UPPER(string_expression) |
 REPLACE(string_expression, string_expression, string_expression) |
 LEFT(string_expression, arithmetic_expression) |
 RIGHT(string_expression, arithmetic_expression)
trim_specification ::= LEADING | TRAILING | BOTH

functions_returning_numerics ::=
 LENGTH(string_expression) |
 LOCATE(string_expression, string_expression[, arithmetic_expression])

The CONCAT function returns a string that is
a concatenation of its arguments.

The second and third arguments of the
SUBSTRING function denote the starting position and length of the
substring to be returned. These arguments are integers. The third
argument is optional. If it is not specified, the substring from the
start position to the end of the string is returned. The first position
of a string is denoted by 1. The SUBSTRING function returns a string.

The TRIM function trims the specified
character from a string. If the character to be trimmed is not
specified, it will be assumed to be space (or blank). The optional
trim_character is a single-character string literal or a
character-valued input parameter (i.e., char or Character)
[75]. If a trim specification is not provided, it
defaults to BOTH. The TRIM function returns the trimmed string.

The LOWER and UPPER functions convert a
string to lower and upper case, respectively, with regard to the locale
of the database. They return a string.

The LEFT and RIGHT functions return the leftmost or rightmost substring,
respectively, of the first argument whose length is given by the second
argument.

The REPLACE function replaces all occurrences within the first argument
string of the second argument string with the third argument string.

The LOCATE function returns the position at which one string occurs within
a second string, optionally ignoring any occurrences that begin before a
specified character position in the second string. It returns the first
character position within the second string (after the specified character
position, if any) at which the first string occurs, as an integer, where
the first character of the second string is denoted by 1. That is, the first
argument is the string to be searched for; the second argument is the string
to be searched in; the optional third argument is an integer representing
the character position at which the search starts (by default, 1, the first
character of the second string). If the first string does not occur within
the second string, 0 is returned.[76]

The LENGTH function returns the length of the
string in characters as an integer.

4.7.7.2. Arithmetic Functions

functions_returning_numerics ::=
 ABS(arithmetic_expression) |
 CEILING(arithmetic_expression) |
 EXP(arithmetic_expression) |
 FLOOR(arithmetic_expression) |
 LN(arithmetic_expression) |
 MOD(arithmetic_expression, arithmetic_expression) |
 POWER(arithmetic_expression, arithmetic_expression) |
 ROUND(arithmetic_expression, arithmetic_expression) |
 SIGN(arithmetic_expression) |
 SQRT(arithmetic_expression) |
 SIZE(collection_valued_path_expression) |
 INDEX(identification_variable) |
 extract_datetime_field

The ABS, CEILING, and FLOOR functions accept a numeric argument and
return a number (integer, float, or double) of the same type as the
argument.

The SIGN function accepts a numeric argument and returns an integer.

The SQRT, EXP, and LN functions accept a numeric argument and return
a double.

The MOD function accepts two integer arguments and returns an integer.

The ROUND function accepts a numeric argument and an integer argument
and returns a number of the same type as the first argument.

The POWER function accepts two numeric arguments and returns a double.

Numeric arguments to these functions may
correspond to the numeric Java object types as well as the primitive
numeric types.

The SIZE function returns an integer value,
the number of elements of the collection. If the collection is empty,
the SIZE function evaluates to zero.

The INDEX function returns an integer value
corresponding to the position of its argument in an ordered list. The
INDEX function can only be applied to identification variables denoting
types for which an order column has been specified.

In the following example, studentWaitlist
is a list of students for which an order column has been specified:

SELECT w.name
FROM Course c JOIN c.studentWaitlist w
WHERE c.name = 'Calculus'
AND INDEX(w) = 0

4.7.7.3. Datetime Functions

functions_returning_datetime :=
 CURRENT_DATE |
 CURRENT_TIME |
 CURRENT_TIMESTAMP |
 LOCAL DATE |
 LOCAL TIME |
 LOCAL DATETIME |
 extract_datetime_part

The functions LOCAL DATE, LOCAL TIME, and LOCAL DATETIME return the value
of the current date, time, or timestamp on the database server, respectively.
Their types are java.time.LocalDate, java.time.LocalTime, and
java.time.LocalDateTime respectively.

The functions CURRENT_DATE, CURRENT_TIME, and CURRENT_TIMESTAMP
return the value of the current date, time, or timestamp on the database
server, respectively. Their types are java.sql.Date, java.sql.Time,
and java.sql.Timestamp respectively.

The EXTRACT function takes a datetime argument and one of the following
field type identifiers: YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE,
SECOND, DATE, TIME.

EXTRACT returns the value of the corresponding field or part of the
datetime.

extract_datetime_field :=
 EXTRACT(datetime_field FROM datetime_expression)

datetime_field := identification_variable

For the following field type identifiers, EXTRACT returns an integer
value:

YEAR means the calendar year.

QUARTER means the calendar quarter, numbered from 1 to 4.

MONTH means the calendar month of the year, numbered from 1.

WEEK means the ISO-8601 week number.

DAY means the calendar day of the month, numbered from 1.

HOUR means the hour of the day in 24-hour time, numbered from 0 to 23.

MINUTE means the minute of the hour, numbered from 0 to 59.

For the SECOND field type identifier, EXTRACT returns a floating point
value:

SECOND means the second of the minute, numbered from 0 to 59, including
a fractional part representing fractions of a second.

It is illegal to pass a datetime argument which does not have the given
field type to EXTRACT.

extract_datetime_part :=
 EXTRACT(datetime_part FROM datetime_expression)

datetime_part := identification_variable

For the following field type identifiers, EXTRACT returns a part of the
datetime value:

DATE means the date part of a datetime.

TIME means the time part of a datetime.

It is illegal to pass a datetime argument which does not have the given
part to EXTRACT.

FROM Course c WHERE c.year = EXTRACT(YEAR FROM LOCAL DATE)

4.7.8. Typecasts

The CAST function converts an expression of one type to an expression
of a different type.

string_cast_function::=
 CAST(scalar_expression AS STRING)
arithmetic_cast_function::=
 CAST(string_expression AS {INTEGER | LONG | FLOAT | DOUBLE})

The persistence provider is required to accept typecasts of the following
forms:

any scalar expression to STRING

any string expression to INTEGER, LONG, FLOAT, or DOUBLE

Typecast expressions are evaluated by the database, with semantics that
vary somewhat between different databases.

When a typecast occurs as a select expression, the result type of the
select expression is:

java.lang.String for a cast to STRING

java.lang.Integer, java.lang.Long, java.lang.Float, or java.lang.Double
for a cast to INTEGER, LONG, FLOAT, or DOUBLE, respectively

4.7.9. Invocation of Predefined and User-defined Database Functions

The invocation of functions other than the
built-in functions of the Jakarta Persistence query language is supported
by means of the function_invocation syntax. This includes the
invocation of predefined database functions and user-defined database
functions.

function_invocation ::= FUNCTION(function_name {, function_arg}*)

function_arg ::=
 literal |
 state_valued_path_expression |
 input_parameter |
 scalar_expression

The function_name argument is a string that
denotes the database function that is to be invoked. The arguments must
be suitable for the database function that is to be invoked. The result
of the function must be suitable for the invocation context.

The function may be a database-defined
function or a user-defined function. The function may be a scalar
function or an aggregate function.

Applications that use the
function_invocation syntax will not be portable across databases.

Example:

SELECT c
FROM Customer c
WHERE FUNCTION('hasGoodCredit', c.balance, c.creditLimit)

4.7.10. Case Expressions

The following forms of case
expressions are supported: general case expressions, simple case
expressions, coalesce expressions, and nullif
expressions.[77]

case_expression ::=
 general_case_expression |
 simple_case_expression |
 coalesce_expression |
 nullif_expression

general_case_expression ::=
 CASE when_clause {when_clause}* ELSE scalar_expression END
when_clause ::= WHEN conditional_expression THEN scalar_expression

simple_case_expression ::=
 CASE case_operand simple_when_clause {simple_when_clause}*
 ELSE scalar_expression
 END
case_operand ::= state_valued_path_expression | type_discriminator
simple_when_clause ::= WHEN scalar_expression THEN scalar_expression

coalesce_expression ::= COALESCE(scalar_expression {, scalar_expression}+)

nullif_expression ::= NULLIF(scalar_expression, scalar_expression)

Examples:

UPDATE Employee e
SET e.salary =
 CASE WHEN e.rating = 1 THEN e.salary * 1.1
 WHEN e.rating = 2 THEN e.salary * 1.05
 ELSE e.salary * 1.01
 END

UPDATE Employee e
SET e.salary =
 CASE e.rating WHEN 1 THEN e.salary * 1.1
 WHEN 2 THEN e.salary * 1.05
 ELSE e.salary * 1.01
 END

SELECT e.name,
 CASE TYPE(e) WHEN Exempt THEN 'Exempt'
 WHEN Contractor THEN 'Contractor'
 WHEN Intern THEN 'Intern'
 ELSE 'NonExempt'
 END
FROM Employee e
WHERE e.dept.name = 'Engineering'

SELECT e.name,
 f.name,
 CONCAT(CASE WHEN f.annualMiles > 50000 THEN 'Platinum '
 WHEN f.annualMiles > 25000 THEN 'Gold '
 ELSE ''
 END,
 'Frequent Flyer')
FROM Employee e JOIN e.frequentFlierPlan f

4.7.11. Identifier and Version Functions

The ID and VERSION functions evaluate to the primary key or version,
respectively, of their argument, which must be an identification variable
assigned an entity abstract schema type or a path expression resolving to
a one-to-one or many-to-one relationship field. For example, if Person
has a primary key field named ssn, then ID(person) is a synonym for
person.ssn.

entity_id_or_version_function ::= id_function | version_function
id_function ::=
 ID(general_identification_variable |
 single_valued_object_path_expression)
version_function ::=
 VERSION(general_identification_variable |
 single_valued_object_path_expression)

The result type of an ID or VERSION function expression is the primary
key type or version type of the argument entity, respectively.

The result may be compared to an input parameter:

DELETE from Employee
WHERE id(this) = :id
 AND version(this) = :version

A persistence provider is not required to support the use of the ID
function for entities with composite primary keys.

4.7.12. Entity Type Expressions and Literal Entity Types

An entity type expression can be used to restrict query polymorphism.
The syntax of an entity type expression is as follows:

entity_type_expression ::=
 type_discriminator |
 entity_type_literal |
 input_parameter
type_discriminator ::=
 TYPE(general_identification_variable |
 single_valued_object_path_expression |
 input_parameter)

The TYPE operator returns the exact type of its argument, which must be
an identification variable assigned an entity abstract schema type, a
path expression resolving to a one-to-one or many-to-one relationship
field, or an input parameter.

An entity_type_literal specifies a literal entity type by its entity
name defined above in Section 4.3.1.

For an input parameter, the entity type must be specified by calling
Query.setParameter() with the java.lang.Class object representing
the entity class.

Examples:

SELECT e
FROM Employee e
WHERE TYPE(e) IN (Exempt, Contractor)

SELECT e
FROM Employee e
WHERE TYPE(e) IN (:empType1, :empType2)

SELECT e
FROM Employee e
WHERE TYPE(e) IN :empTypes

SELECT TYPE(e)
FROM Employee e
WHERE TYPE(e) <> Exempt

4.7.13. Numeric Expressions and Type Promotion

Every numeric expression in a query is assigned a Java numeric type
according to the following rules:

An expression that corresponds to a persistent state field is of the
same type as that persistent state field.

An expression that corresponds to one of arithmetic functions described
in Section 4.7.7.2 is of the type defined by Section 4.7.7.2.

An expression that corresponds to one of an aggregate functions described
in Section 4.9.5 is of the type defined by Section 4.9.5.

For a CASE expression, COALESCE expression, NULLIF expression, or
arithmetic operator expression (+, -, *, /), the numeric type is
determined by its operand types, and by the following rules[78].

If there is an operand of type Double or double, the expression
is of type Double;

otherwise, if there is an operand of type Float or float, the
expression is of type Float;

otherwise, if there is an operand of type BigDecimal, the expression
is of type BigDecimal;

otherwise, if there is an operand of type BigInteger, the expression
is of type BigInteger, unless the operator is / (division), in which
case the expression type is not defined here;

otherwise, if there is an operand of type Long or long, the
expression is of type Long, unless the operator is / (division),
in which case the expression type is not defined here;

otherwise, if there is an operand of integral type, the expression
is of type Integer, unless the operator is / (division), in which
case the expression type is not defined here.

Users should note that the semantics of the
SQL division operation are not standard across databases. In particular,
when both operands are of integral types, the result of the division
operation will be an integral type in some databases, and an
non-integral type in others. Portable applications should not assume a
particular result type.

For numeric expressions occurring in the SELECT clause, these rules
determine the Java object type returned in the query result list.

4.8. GROUP BY, HAVING

The GROUP BY construct enables the
aggregation of result values according to a set of properties. The
HAVING construct enables conditions to be specified that further
restrict the query result. Such conditions are restrictions upon the
groups.

The syntax of the GROUP BY and HAVING clauses is as follows:

groupby_clause ::= GROUP BY groupby_item {, groupby_item}*
groupby_item ::= single_valued_path_expression | identification_variable

having_clause ::= HAVING conditional_expression

If a query contains both a WHERE clause and a
GROUP BY clause, the effect is that of first applying the where clause,
and then forming the groups and filtering them according to the HAVING
clause. The HAVING clause causes those groups to be retained that
satisfy the condition of the HAVING clause.

The requirements for the SELECT clause when
GROUP BY is used follow those of SQL: namely, any item that appears in
the SELECT clause (other than as an aggregate function or as an argument
to an aggregate function) must also appear in the GROUP BY clause. In
forming the groups, null values are treated as the same for grouping
purposes.

Grouping by an entity is permitted. In this
case, the entity must contain no serialized state fields or lob-valued
state fields that are eagerly fetched. Grouping by an entity that
contains serialized state fields or lob-valued state fields is not
portable, since the implementation is permitted to eagerly fetch fields
or properties that have been specified as LAZY.

Grouping by embeddables is not supported.

The HAVING clause is used to filter over the
groups, and can contain aggregate functions over attributes included in
the groups and/or functions or other query language operators over the
attributes that are used for grouping. It is not required that an
aggregate function used in the HAVING clause also be used in the SELECT
clause.

If there is no GROUP BY clause and the HAVING
clause is used, the result is treated as a single group, and the select
list can only consist of aggregate functions. The use of HAVING in the
absence of GROUP BY is not required to be supported by an implementation
of this specification. Portable applications should not rely on HAVING
without the use of GROUP BY.

Examples:

SELECT c.status, AVG(c.filledOrderCount), COUNT(c)
FROM Customer c
GROUP BY c.status
HAVING c.status IN (1, 2)

SELECT c.country, COUNT(c)
FROM Customer c
GROUP BY c.country
HAVING COUNT(c) > 30

SELECT c, COUNT(o)
FROM Customer c JOIN c.orders o
GROUP BY c
HAVING COUNT(o) >= 5

4.9. SELECT Clause

The SELECT clause specifies the query result, as a list of items to
be returned by the query.

The SELECT clause can contain one or more of the following elements:

an identification variable that ranges over an abstract schema type,

a single-valued path expression,

a scalar expression,

an aggregate expression,

a constructor expression.

The SELECT clause has the following syntax:

select_clause ::= SELECT [DISTINCT] select_item {, select_item}*
select_item ::= select_expression [[AS] result_variable]
select_expression ::=
 single_valued_path_expression |
 scalar_expression |
 aggregate_expression |
 identification_variable |
 OBJECT(identification_variable) |
 constructor_expression
constructor_expression ::=
 NEW constructor_name (constructor_item {, constructor_item}*)
constructor_item ::=
 single_valued_path_expression |
 scalar_expression |
 aggregate_expression |
 identification_variable
aggregate_expression ::=
 {AVG | MAX | MIN | SUM} ([DISTINCT] state_valued_path_expression) |
 COUNT ([DISTINCT] identification_variable | state_valued_path_expression |
 single_valued_object_path_expression) |
 function_invocation

For example:

SELECT c.id, c.status
FROM Customer c JOIN c.orders o
WHERE o.count > 100

In the following example, videoInventory is
a Map from the entity Movie to the number of copies in stock:

SELECT v.location.street, KEY(i).title, VALUE(i)
FROM VideoStore v JOIN v.videoInventory i
WHERE v.location.zipcode = '94301' AND VALUE(i) > 0

Note that the SELECT clause must be specified
to return only single-valued expressions. The query below is therefore
not valid:

SELECT o.lineItems FROM Order AS o

The DISTINCT
keyword is used to specify that duplicate values must be eliminated from
the query result.

If DISTINCT is not specified, duplicate
values are not eliminated.

The result of DISTINCT over embeddable
objects or map entry results is undefined.

Standalone identification variables in the
SELECT clause may optionally be qualified by the
OBJECT operator.[79] The
SELECT clause must not use the OBJECT operator to qualify path
expressions.

A result_variable assigns a name to a select_item in the query result.
The result variable must be a valid identifier, as defined in Section 4.4.1,
must not be a reserved identifier, and must not collide with any
identification variable declared in the FROM clause. A result variable may
be used to refer to an element of the select clause from an item in the
ORDER BY clause, as specified in Section 4.10. Like identification variables,
result variables are case-insensitive.

Example:

SELECT c, COUNT(l) AS itemCount
FROM Customer c JOIN c.orders o JOIN o.lineItems l
WHERE c.address.state = 'CA'
GROUP BY c
ORDER BY itemCount

The SELECT clause is optional. A query with a missing SELECT clause
is interpreted as if it had the following single-item SELECT clause:
select this, where this is the implicit identification variable.

Thus, the following queries are equivalent:

FROM Order
WHERE customer.lastname = 'Smith'
 AND customer.firstname= 'John'

SELECT this
FROM Order
WHERE this.customer.lastname = 'Smith'
 AND this.customer.firstname= 'John'

SELECT ord
FROM Order AS ord
WHERE ord.customer.lastname = 'Smith'
 AND ord.customer.firstname= 'John'

If the implicit identification variable has not been assigned an
abstract schema type, the SELECT clause is required.

4.9.1. Result Type of the SELECT Clause

The type of the query result specified by the
SELECT clause of a query is an entity
abstract schema type, a state field type,
the result of a scalar expression, the result of an aggregate function,
the result of a construction operation, or some sequence of these.

The result type of the SELECT clause is
defined by the result types of the select expressions contained in
it. When multiple select expressions are used in the SELECT clause, the
elements in this result correspond in order to the order of their
specification in the SELECT clause and in type to the result types of
each of the select expressions.

The type of the result of a select_expression
is as follows:

The result type of an identification_variable
is the type of the entity object or embeddable object to which the
identification variable corresponds. The type of an
identification_variable that refers to an entity abstract schema type is
the type of the entity to which that identification variable corresponds
or a subtype as determined by the object/relational mapping.

The result type of a
single_valued_path_expression that is a state_field_path_expression is
the same type as the corresponding state field of the entity or
embeddable class. If the state field of the entity is a primitive type,
the result type is the corresponding object type.

The result type of a
single_valued_path_expression that is a
single_valued_object_path_expression is the type of the entity object or
embeddable object to which the path expression corresponds. A
single_valued_object_path_expression that results in an entity object
will result in an entity of the type of the relationship field or the
subtype of the relationship field of the entity object as determined by
the object/relational mapping.

The result type of a
single_valued_path_expression that is an identification_variable to
which the KEY or VALUE function has been applied is determined by the
type of the map key or value respectively, as defined by the above
rules.

The result type of a
single_valued_path_expression that is an identification_variable to
which the ENTRY function has been applied is java.util.Map.Entry,
where the key and value types of the map entry are determined by the
above rules as applied to the map key and map value respectively.

The result type of a scalar_expression is
the type of the scalar value to which the expression evaluates. The
result type of a numeric scalar_expression is defined in Section 4.7.13.

The result type of an
entity_type_expression scalar expression is the Java class to which
the resulting abstract schema type corresponds.

The result type of aggregate_expression is
defined in Section 4.9.5.

The result type of a constructor_expression
is the type of the class for which the constructor is defined. The types
of the arguments to the constructor are defined by the above rules.

4.9.2. Constructor Expressions in the SELECT Clause

A constructor may be used in the SELECT list
to return an instance of a Java class. The specified class is not
required to be an entity or to be mapped to the database. The
constructor name must be fully qualified.

If an entity class name is specified as the
constructor name in the SELECT NEW clause, the resulting entity
instances will be in either the new or the detached state, depending on
whether a primary key is retrieved for the constructed object.

If a single_valued_path_expression or
identification_variable that is an argument to the constructor
references an entity, the resulting entity instance referenced by that
single_valued_path_expression or identification_variable will be in
the managed state.

For example,

SELECT NEW com.acme.example.CustomerDetails(c.id, c.status, o.count)
FROM Customer c JOIN c.orders o
WHERE o.count > 100

4.9.3. Null Values in the Query Result

If the result of a query corresponds to an
association field or state field whose value is null, that null value is
returned in the result of the query method. The IS NOT NULL construct
can be used to eliminate such null values from the result set of the
query.

Note, however, that state field types defined
in terms of Java numeric primitive types cannot produce NULL values in
the query result. A query that returns such a state field type as a
result type must not return a null value.

4.9.4. Embeddables in the Query Result

If the result of a query corresponds to an
identification variable or state field whose value is an embeddable, the
embeddable instance returned by the query will not be in the managed
state (i.e., it will not be part of the state of any managed entity).

In the following example, the Address
instances returned by the query will reference Phone instances. While
the Phone instances will be managed, the Address instances
referenced by the addr result variable will not be. Modifications to
these embeddable instances will have no effect on persistent state.

@Entity
public class Employee {
 @Id
 int id;

 Address address;

 // ...
}

@Embeddable
public class Address {
 String street;

 // ...

 @OneToOne
 Phone phone; // fetch=EAGER
}

@Entity
public class Phone {
 @Id
 int id;

 // ...

 @OneToOne(mappedBy="address.phone")
 Employee emp; // fetch=EAGER
}

SELECT e.address AS addr
FROM Employee e

4.9.5. Aggregate Functions in the SELECT Clause

The result of a query may be the result of an
aggregate function applied to a path expression.

The following aggregate functions can be used
in the SELECT clause of a query: AVG, COUNT, MAX, MIN, SUM, aggregate
functions defined in the database.
