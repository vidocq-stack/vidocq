# 4. Query Language (part 3/3)

For all aggregate functions except COUNT, the
path expression that is the argument to the aggregate function must
terminate in a state field. The path expression argument to COUNT may
terminate in either a state field or a association field, or the
argument to COUNT may be an identification variable.

Arguments to the functions SUM and AVG must
be numeric. Arguments to the functions MAX and MIN must correspond to
orderable state field types (i.e., numeric types, string types,
character types, or date types).

The Java type that is contained in the result
of a query using an aggregate function is as follows:

COUNT returns Long.

MAX, MIN return the type of the state field to which they are applied.

AVG returns Double.

SUM returns Long when applied to state fields
of integral types (other than BigInteger); Double when applied to state
fields of floating point types; BigInteger when applied to state fields
of type BigInteger; and BigDecimal when applied to state fields of type
BigDecimal.

Null values are eliminated before the
aggregate function is applied, regardless of whether the keyword
DISTINCT is specified.

If SUM, AVG, MAX, or MIN is used, and there
are no values to which the aggregate function can be applied, the result
of the aggregate function is NULL.

If COUNT is used, and there are no values to
which COUNT can be applied, the result of the aggregate function is 0.

The argument to an aggregate function
may be preceded by the keyword DISTINCT to specify that duplicate values
are to be eliminated before the aggregate function is
applied.[80]

The use of DISTINCT with COUNT is not
supported for arguments of embeddable types or map entry types.

The invocation of aggregate database
functions, including user defined functions, is supported by means of
the FUNCTION operator. See Section 4.7.9.

The following query returns the average order quantity:

SELECT AVG(o.quantity) FROM Order o

The following query returns the total cost of
the items that John Smith has ordered.

SELECT SUM(l.price)
FROM Order o JOIN o.lineItems l JOIN o.customer c
WHERE c.lastname = 'Smith' AND c.firstname = 'John'

The following query returns the total number of orders.

SELECT COUNT(o) FROM Order o

The following query counts the number of
items in John Smith’s order for which prices have been specified.

SELECT COUNT(l.price)
FROM Order o JOIN o.lineItems l JOIN o.customer c
WHERE c.lastname = 'Smith' AND c.firstname = 'John'

Note that this is equivalent to:

SELECT COUNT(l)
FROM Order o JOIN o.lineItems l JOIN o.customer c
WHERE c.lastname = 'Smith' AND c.firstname = 'John' AND l.price IS NOT NULL

4.10. ORDER BY Clause

The ORDER BY clause specifies how the results of a query should be sorted.
The syntax of the ORDER BY clause is:

orderby_clause ::= ORDER BY orderby_item {, orderby_item}*
orderby_item ::= orderby_expression [ASC | DESC] [NULLS {FIRST | LAST}]
orderby_expression ::=
 state_field_path_expression |
 general_identification_variable |
 result_variable |
 scalar_expression

The ORDER BY clause specifies a list of items. Each orderby_expression
must be one of the following:

A state_field_path_expression evaluating to an orderable state field
of an entity or embeddable class abstract schema type designated in the
SELECT clause by either:

a general_identification_variable, or

a single_valued_object_path_expression.

A state_field_path_expression evaluating to the same state field of
the same entity or embeddable abstract schema type as a
state_field_path_expression in the SELECT clause.

A general_identification_variable evaluating to the same map field of
the same entity or embeddable abstract schema type as a
general_identification_variable in the SELECT clause.

A reference to a result_variable declared by an orderable item in the
SELECT clause. The orderable item must be an aggregate_expression, a
scalar_expression, or a state_field_path_expression.

Any scalar_expression involving only state_field_path_expressions
which would be allowed according to items 1 or 2 above.

Depending on the database, arbitrary scalar expressions may not be allowed
in the ORDER BY clause. Therefore, applications which require portability
between databases should not depend on the use of a scalar expression in
ORDER BY if it is only permitted by item 5.

For example, the four queries below are legal.

SELECT o
FROM Customer c JOIN c.orders o JOIN c.address a
WHERE a.state = 'CA'
ORDER BY o.quantity DESC, o.totalcost

SELECT o.quantity, a.zipcode
FROM Customer c JOIN c.orders o JOIN c.address a
WHERE a.state = 'CA'
ORDER BY o.quantity, a.zipcode

SELECT o.quantity, o.cost*1.08 AS taxedCost, a.zipcode
FROM Customer c JOIN c.orders o JOIN c.address a
WHERE a.state = 'CA' AND a.county = 'Santa Clara'
ORDER BY o.quantity, taxedCost, a.zipcode

SELECT AVG(o.quantity) as q, a.zipcode
FROM Customer c JOIN c.orders o JOIN c.address a
WHERE a.state = 'CA'
GROUP BY a.zipcode
ORDER BY q DESC

The following query is legal, but might not be supported on every database.

SELECT c, o
FROM Customer c JOIN c.orders o JOIN c.address a
WHERE a.state = 'CA'
ORDER BY UPPER(c.lastname), UPPER(c.firstname)

The following two queries are not legal
because the orderby_item is not reflected in the SELECT clause of the
query.

SELECT p.product_name
FROM Order o JOIN o.lineItems l JOIN l.product p JOIN o.customer c
WHERE c.lastname = 'Smith' AND c.firstname = 'John'
ORDER BY p.price

SELECT p.product_name
FROM Order o, IN(o.lineItems) l JOIN o.customer c
WHERE c.lastname = 'Smith' AND c.firstname = 'John'
ORDER BY o.quantity

The keyword ASC specifies that ascending ordering is used for the associated
orderby_item; the keyword DESC specifies that descending ordering is used.
If neither keyword is explicitly specified, ascending ordering is the default.

The interpretation of ascending or descending order is determined by the
database, but, in general:

ascending order for numeric values means smaller values first, while
descending order means larger values first, and

strings are sorted lexicographically, using a database-dependent collation.

The keyword NULLS specifies the ordering of null values, either FIRST or LAST.

FIRST means that results are sorted so that all null values occur before
all non-null values.

LAST means that results are sorted so that all null values occur after
all non-null values.

If NULLS is not specified, the database determines whether null values occur
first or last.

Items occurring earlier in the ORDER BY clause take precedence. That is,
an item occurring later in the ORDER BY clause is only used to resolve
"ties" between results which cannot be unambiguously ordered using only
earlier items.

The order of query results must be preserved in the result list or stream
returned by a query execution method when an ORDER BY clause is specified.

4.11. Bulk Update and Delete Operations

Bulk update and delete operations apply to
entities of a single entity class (together with its subclasses, if
any). Only one entity abstract schema type may be specified in the FROM
or UPDATE clause.

The syntax of these operations is as follows:

update_statement ::= update_clause [where_clause]
update_clause ::= UPDATE entity_name [[AS] identification_variable]
 SET update_item {, update_item}*
update_item ::= [identification_variable.]{single_valued_embeddable_object_field.}*
 {state_field | single_valued_object_field} = new_value
new_value ::=
 scalar_expression |
 simple_entity_expression |
 NULL

delete_statement ::= delete_clause [where_clause]
delete_clause ::= DELETE FROM entity_name [[AS] identification_variable]

The syntax of the WHERE clause is described
in Section 4.5.

A delete operation only applies to entities
of the specified class and its subclasses. It does not cascade to
related entities.

The new_value specified for an update
operation must be compatible in type with the field to which it is
assigned.

Bulk update maps directly to a database
update operation, bypassing optimistic locking checks. Portable
applications must manually update the value of the version column, if
desired, and/or manually validate the value of the version column.

The persistence context is not synchronized
with the result of the bulk update or delete.

Caution should be used when executing bulk
update or delete operations because they may result in inconsistencies
between the database and the entities in the active persistence context.
In general, bulk update and delete operations should only be performed
within a transaction in a new persistence context or before fetching or
accessing entities whose state might be affected by such operations._

Examples:

DELETE
FROM Customer c
WHERE c.status = 'inactive'

DELETE
FROM Customer c
WHERE c.status = 'inactive'
 AND c.orders IS EMPTY

UPDATE Customer c
SET c.status = 'outstanding'
WHERE c.balance < 10000

UPDATE Employee e
SET e.address.building = 22
WHERE e.address.building = 14
 AND e.address.city = 'Santa Clara'
 AND e.project = 'Jakarta EE'

4.12. BNF

BNF notation summary:

{ …​ } grouping

[ …​ ] optional constructs

* zero or more

+ one or more

| alternates

The following is the BNF for the Jakarta Persistence query language.

QL_statement ::= select_statement | update_statement | delete_statement
select_statement ::= union
union ::= intersection | union {UNION [ALL] | EXCEPT [ALL]} intersection
intersection ::= query_expression | intersection INTERSECT [ALL] query_expression
query_expression ::= select_query | (union)
select_query ::= [select_clause] from_clause [where_clause] [groupby_clause]
 [having_clause] [orderby_clause]
update_statement ::= update_clause [where_clause]
delete_statement ::= delete_clause [where_clause]
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
join_condition ::= ON conditional_expression
join_association_path_expression ::=
 join_collection_valued_path_expression |
 join_single_valued_path_expression |
 TREAT(join_collection_valued_path_expression AS subtype) |
 TREAT(join_single_valued_path_expression AS subtype)
join_collection_valued_path_expression ::=
 [identification_variable.]{single_valued_embeddable_object_field.}* collection_valued_field
join_single_valued_path_expression ::=
 [identification_variable.]{single_valued_embeddable_object_field.}* single_valued_object_field
collection_member_declaration ::=
 IN (collection_valued_path_expression) [AS] identification_variable
qualified_identification_variable ::=
 map_field_identification_variable |
 ENTRY(identification_variable)
map_field_identification_variable ::=
 KEY(identification_variable) |
 VALUE(identification_variable)
single_valued_path_expression ::=
 qualified_identification_variable |
 TREAT(qualified_identification_variable AS subtype) |
 state_field_path_expression |
 single_valued_object_path_expression
general_identification_variable ::=
 identification_variable |
 map_field_identification_variable
general_subpath ::= simple_subpath | treated_subpath{.single_valued_object_field}*
simple_subpath ::=
 general_identification_variable |
 general_identification_variable{.single_valued_object_field}*
treated_subpath ::= TREAT(general_subpath AS subtype)
state_field_path_expression ::= [general_subpath.]state_field
state_valued_path_expression ::=
 state_field_path_expression | general_identification_variable
single_valued_object_path_expression ::=
 general_subpath.single_valued_object_field
collection_valued_path_expression ::= general_subpath.{collection_valued_field}
update_clause ::= UPDATE entity_name [[AS] identification_variable]
 SET update_item {, update_item}*
update_item ::= [identification_variable.]{single_valued_embeddable_object_field.}*
 {state_field | single_valued_object_field} = new_value
new_value ::=
 scalar_expression |
 simple_entity_expression |
 NULL
delete_clause ::= DELETE FROM entity_name [[AS] identification_variable]
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
where_clause ::= WHERE conditional_expression
groupby_clause ::= GROUP BY groupby_item {, groupby_item}*
groupby_item ::= single_valued_path_expression | identification_variable
having_clause ::= HAVING conditional_expression
orderby_clause ::= ORDER BY orderby_item {, orderby_item}*
orderby_item ::= orderby_expression [ASC | DESC] [NULLS {FIRST | LAST}]
orderby_expression ::=
 state_field_path_expression |
 general_identification_variable |
 result_variable |
 scalar_expression
subquery ::= simple_select_clause subquery_from_clause [where_clause]
 [groupby_clause] [having_clause]
subquery_from_clause ::=
 FROM subselect_identification_variable_declaration
 {, subselect_identification_variable_declaration | collection_member_declaration}*
subselect_identification_variable_declaration ::=
 identification_variable_declaration |
 derived_path_expression [AS] identification_variable {join}* |
 derived_collection_member_declaration
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
simple_select_clause ::= SELECT [DISTINCT] simple_select_expression
simple_select_expression::=
 single_valued_path_expression |
 scalar_expression |
 aggregate_expression |
 identification_variable
scalar_expression ::=
 arithmetic_expression |
 string_expression |
 enum_expression |
 datetime_expression |
 boolean_expression |
 case_expression |
 entity_type_expression |
 entity_id_or_version_function
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
between_expression ::=
 arithmetic_expression [NOT] BETWEEN
 arithmetic_expression AND arithmetic_expression |
 string_expression [NOT] BETWEEN string_expression AND string_expression |
 datetime_expression [NOT] BETWEEN datetime_expression AND datetime_expression
in_expression ::=
 {state_valued_path_expression | type_discriminator} [NOT] IN
 {(in_item{, in_item}*) | (subquery) | collection_valued_input_parameter}
in_item ::= literal | single_valued_input_parameter
like_expression ::=
 string_expression [NOT] LIKE pattern_value [ESCAPE escape_character]
null_comparison_expression ::=
 {single_valued_path_expression | input_parameter} IS [NOT] NULL
empty_collection_comparison_expression ::=
 collection_valued_path_expression IS [NOT] EMPTY
collection_member_expression ::= entity_or_value_expression
 [NOT] MEMBER [OF] collection_valued_path_expression
entity_or_value_expression ::=
 single_valued_object_path_expression |
 state_field_path_expression |
 simple_entity_or_value_expression
simple_entity_or_value_expression ::=
 identification_variable |
 input_parameter |
 literal
exists_expression ::= [NOT] EXISTS (subquery)
all_or_any_expression ::= {ALL | ANY | SOME} (subquery)
comparison_expression ::=
 string_expression comparison_operator {string_expression | all_or_any_expression} |
 boolean_expression {= | <>} {boolean_expression | all_or_any_expression} |
 enum_expression {= | <>} {enum_expression | all_or_any_expression} |
 datetime_expression comparison_operator
 {datetime_expression | all_or_any_expression} |
 entity_expression {= | <>} {entity_expression | all_or_any_expression} |
 arithmetic_expression comparison_operator {arithmetic_expression | all_or_any_expression} |
 entity_id_or_version_function {= | <>} input_parameter |
 entity_type_expression {= | <>} entity_type_expression}
comparison_operator ::= = | > | >= | < | <= | <>
arithmetic_expression ::=
 arithmetic_term | arithmetic_expression {+ | -} arithmetic_term
arithmetic_term ::= arithmetic_factor | arithmetic_term {* | /} arithmetic_factor
arithmetic_factor ::= [{+ | -}] arithmetic_primary
arithmetic_primary ::=
 state_valued_path_expression |
 numeric_literal |
 (arithmetic_expression) |
 input_parameter |
 functions_returning_numerics |
 aggregate_expression |
 case_expression |
 function_invocation |
 arithmetic_cast_function |
 (subquery)
string_expression ::=
 state_valued_path_expression |
 string_literal |
 input_parameter |
 functions_returning_strings |
 aggregate_expression |
 case_expression |
 function_invocation |
 string_cast_function |
 string_expression || string_expression |
 (subquery)
datetime_expression ::=
 state_valued_path_expression |
 input_parameter |
 functions_returning_datetime |
 aggregate_expression |
 case_expression |
 function_invocation |
 date_time_timestamp_literal |
 (subquery)
boolean_expression ::=
 state_valued_path_expression |
 boolean_literal |
 input_parameter |
 case_expression |
 function_invocation |
 (subquery)
enum_expression ::=
 state_valued_path_expression |
 enum_literal |
 input_parameter |
 case_expression |
 (subquery)
entity_expression ::= single_valued_object_path_expression | simple_entity_expression
simple_entity_expression ::= identification_variable | input_parameter
entity_type_expression ::=
 type_discriminator |
 entity_type_literal |
 input_parameter
type_discriminator ::=
 TYPE(general_identification_variable |
 single_valued_object_path_expression |
 input_parameter)
arithmetic_cast_function::=
 CAST(string_expression AS {INTEGER | LONG | FLOAT | DOUBLE})
functions_returning_numerics ::=
 LENGTH(string_expression) |
 LOCATE(string_expression, string_expression[, arithmetic_expression]) |
 ABS(arithmetic_expression) |
 CEILING(arithmetic_expression) |
 EXP(arithmetic_expression) |
 FLOOR(arithmetic_expression) |
 LN(arithmetic_expression) |
 SIGN(arithmetic_expression) |
 SQRT(arithmetic_expression) |
 MOD(arithmetic_expression, arithmetic_expression) |
 POWER(arithmetic_expression, arithmetic_expression) |
 ROUND(arithmetic_expression, arithmetic_expression) |
 SIZE(collection_valued_path_expression) |
 INDEX(identification_variable) |
 extract_datetime_field
functions_returning_datetime ::=
 CURRENT_DATE |
 CURRENT_TIME |
 CURRENT_TIMESTAMP |
 LOCAL DATE |
 LOCAL TIME |
 LOCAL DATETIME |
 extract_datetime_part
string_cast_function::=
 CAST(scalar_expression AS STRING)
functions_returning_strings ::=
 CONCAT(string_expression, string_expression{, string_expression}*) |
 SUBSTRING(string_expression, arithmetic_expression[, arithmetic_expression]) |
 TRIM([[trim_specification] [trim_character] FROM] string_expression) |
 LOWER(string_expression) |
 UPPER(string_expression)
trim_specification ::= LEADING | TRAILING | BOTH
function_invocation ::= FUNCTION(function_name{, function_arg}*)
extract_datetime_field :=
 EXTRACT(datetime_field FROM datetime_expression)
datetime_field := identification_variable
extract_datetime_part :=
 EXTRACT(datetime_part FROM datetime_expression)
datetime_part := identification_variable
function_arg ::=
 literal |
 state_valued_path_expression |
 input_parameter |
 scalar_expression
entity_id_or_version_function ::= id_function | version_function
id_function ::=
 ID(general_identification_variable |
 single_valued_object_path_expression)
version_function ::=
 VERSION(general_identification_variable |
 single_valued_object_path_expression)
case_expression ::=
 general_case_expression |
 simple_case_expression |
 coalesce_expression |
 nullif_expression
general_case_expression::= CASE when_clause {when_clause}* ELSE scalar_expression END
when_clause ::= WHEN conditional_expression THEN scalar_expression
simple_case_expression ::=
 CASE case_operand simple_when_clause {simple_when_clause}*
 ELSE scalar_expression
 END
case_operand ::= state_valued_path_expression | type_discriminator
simple_when_clause ::= WHEN scalar_expression THEN scalar_expression
coalesce_expression ::= COALESCE(scalar_expression{, scalar_expression}+)
nullif_expression::= NULLIF(scalar_expression, scalar_expression)
