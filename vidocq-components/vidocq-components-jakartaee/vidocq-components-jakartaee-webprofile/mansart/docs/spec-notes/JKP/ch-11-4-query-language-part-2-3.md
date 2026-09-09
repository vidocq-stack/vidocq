# ch-11-4-query-language-part-2-3 — Requirements Note

## 4.6.6. Like Expressions

[4.6.6] The string_expression in a LIKE expression must have a string value.
[4.6.6] The pattern_value is a string literal or a string-valued input parameter in which an underscore (_) stands for any single character, a percent (%) character stands for any sequence of characters (including the empty sequence), and all other characters stand for themselves.
[4.6.6] The optional escape_character is a single-character string literal or a character-valued input parameter (i.e., char or Character) and is used to escape the underscore and percent characters in pattern_value.
[4.6.6] If the value of the string_expression or pattern_value is NULL or unknown, the value of the LIKE expression is unknown.
[4.6.6] If the escape_character is specified and is NULL, the value of the LIKE expression is unknown.

## 4.6.7. Null Comparison Expressions

[4.6.7] A null comparison expression tests whether or not the single-valued path expression or input parameter is a NULL value.
[4.6.7] Null comparisons over instances of embeddable class types are not supported.

## 4.6.8. Empty Collection Comparison Expressions

[4.6.8] An empty_collection_comparison_expression tests whether or not the collection designated by the collection-valued path expression is empty (i.e., has no elements).
[4.6.8] If the value of the collection-valued path expression in an empty collection comparison expression is unknown, the value of the empty comparison expression is unknown.

## 4.6.9. Collection Member Expressions

[4.6.9] A collection_member_expression tests whether the designated value is a member of the collection specified by the collection-valued path expression.
[4.6.9] Expressions that evaluate to embeddable types are not supported in collection member expressions.
[4.6.9] If the collection-valued path expression designates an empty collection, the value of the MEMBER OF expression is FALSE and the value of the NOT MEMBER OF expression is TRUE.
[4.6.9] If the value of the collection_valued_path_expression or entity_or_value_expression in the collection member expression is NULL or unknown, the value of the collection member expression is unknown.

## 4.6.10. Exists Expressions

[4.6.10] An EXISTS expression is a predicate that is true only if the result of the subquery consists of one or more values and that is false otherwise.

## 4.6.11. All or Any Expressions

[4.6.11] An ALL conditional expression is a predicate over a subquery that is true if the comparison operation is true for all values in the result of the subquery or the result of the subquery is empty; it is false if the result of the comparison is false for at least one value, and is unknown if neither true nor false.
[4.6.11] An ANY conditional expression is a predicate over a subquery that is true if the comparison operation is true for some value in the result of the subquery; it is false if the result of the subquery is empty or if the comparison operation is false for every value, and is unknown if neither true nor false.
[4.6.11] The keyword SOME is synonymous with ANY.
[4.6.11] The comparison operators used with ALL or ANY conditional expressions are =, <, <=, >, >=, <>.
[4.6.11] The result of the subquery must be like that of the other argument to the comparison operator in type.

## 4.6.12. Subqueries

[4.6.12] Subqueries may be used in the WHERE or HAVING clause.
[4.6.12] Some contexts in which a subquery can be used require that the subquery be a scalar subquery (i.e., produce a single result).

## 4.6.13. Null Values

[4.6.13] Comparison or arithmetic operations with a NULL value always yield an unknown value.
[4.6.13] Two NULL values are not considered to be equal; the comparison yields an unknown value.
[4.6.13] Comparison or arithmetic operations with an unknown value always yield an unknown value.
[4.6.13] The IS NULL and IS NOT NULL operators convert a NULL state field or single-valued object field value into the respective TRUE or FALSE value.
[4.6.13] Boolean operators use three-valued logic (AND, OR, NOT).
[4.6.13] The Jakarta Persistence query language defines the empty string, '', as a string with length zero, which is not equal to a NULL value.

## 4.6.14. Equality and Comparison Semantics

[4.6.14] Only the values of like types are permitted to be compared; there is one exception: it is valid to compare numeric values for which the rules of numeric promotion apply.
[4.6.14] Conditional expressions attempting to compare non-like type values are disallowed except for the numeric case.
[4.6.14] Two entities of the same abstract schema type are equal if and only if they have the same primary key value.
[4.6.14] Only equality/inequality comparisons over enums are required to be supported.
[4.6.14] Comparisons over instances of embeddable class or map entry types are not supported.

## 4.7. Scalar Expressions

[4.7] Scalar expressions may be used in the SELECT clause of a query as well as in the WHERE and HAVING clauses.

## 4.7.1. Literals

[4.7.1] A string literal is enclosed in single quotes; a single quote within is represented by two single quotes; string literals use unicode character encoding; Java escape notation is not supported.
[4.7.1] A numeric literal may be a decimal Java integer (int or long) literal, a Java floating point (float or double) literal, or a literal BigInteger or BigDecimal; a suffix L, D, or F may indicate the specific type per the Java Language Specification.
[4.7.1] A suffix BI or BD may indicate a literal BigInteger or BigDecimal, respectively.
[4.7.1] When a numeric literal has no suffix, an integer literal is interpreted as a Java int and a floating point literal as a Java double.
[4.7.1] Support for hexadecimal and octal numeric literals is not required by this specification.
[4.7.1] Enum literals support Java enum literal syntax; the fully qualified enum class name must be specified.
[4.7.1] The JDBC escape syntax may be used for the specification of date, time, and timestamp literals; persistence providers are not required to translate this into native database syntax.
[4.7.1] The boolean literals are TRUE and FALSE.
[4.7.1] Entity type literals are specified by entity names; reserved literals are case-insensitive.

## 4.7.2. Identification Variables

[4.7.2] All identification variables used in the WHERE or HAVING clause of a SELECT or DELETE statement must be declared in the FROM clause; those in an UPDATE statement's WHERE clause must be declared in the UPDATE clause.
[4.7.2] Identification variables are existentially quantified in the WHERE and HAVING clause; an identification variable never designates a collection in its entirety.

## 4.7.3. Path Expressions

[4.7.3] It is illegal to use a collection_valued_path_expression within a WHERE or HAVING clause as part of a conditional expression except in an empty_collection_comparison_expression, in a collection_member_expression, or as an argument to the SIZE operator.

## 4.7.4. Input Parameters

[4.7.4] An input parameter allows a value in the Java program to be safely interpolated into the text of the parameterized query.
[4.7.4] In a given query, either positional or named parameters may be used; positional and named parameters must not be mixed in a single query.
[4.7.4] The persistence provider is required to support input parameters which occur in the WHERE clause or HAVING clause of a query, or as the new value for an update item in the SET clause of an update statement.
[4.7.4] If an input parameter value is null, comparison operations or arithmetic operations involving the input parameter will result in an unknown value.
[4.7.4] An input parameter which occurs directly to the right of the IN keyword in an IN predicate is collection-valued; every other input parameter is single-valued.

## 4.7.4.1. Positional Parameters

[4.7.4.1] A positional parameter is designated by an integer, prefixed with a ? symbol (question mark) in the text of the query string; input parameters are numbered starting from 1; a given positional parameter may occur more than once in the query string.
[4.7.4.1] The ordering of the use of parameters within the text of the query string need not match the numbering of the positional parameters.

## 4.7.4.2. Named Parameters

[4.7.4.2] A named parameter is denoted by an identifier, prefixed by the : symbol (colon) in the text of the query string; the identifier name must follow the usual rules for identifiers specified in Section 4.4.1; named parameters are case-sensitive; a given named parameter may occur more than once in the query string.

## 4.7.7.1. String Functions

[4.7.7] If the value of any argument to a functional expression is null or unknown, the value of the functional expression is unknown.
[4.7.7.1] The CONCAT function returns a string that is a concatenation of its arguments.
[4.7.7.1] The SUBSTRING function accepts integer arguments for starting position and length; it returns a string.
[4.7.7.1] The TRIM function trims the specified character from a string; defaults to space (blank) if not specified; defaults to BOTH if no trim specification is provided; it returns the trimmed string.
[4.7.7.1] The LOWER and UPPER functions convert a string to lower and upper case, respectively, with regard to the locale of the database.
[4.7.7.1] The LEFT and RIGHT functions return the leftmost or rightmost substring, respectively, of the first argument whose length is given by the second argument.
[4.7.7.1] The REPLACE function replaces all occurrences within the first argument string of the second argument string with the third argument string.
[4.7.7.1] The LOCATE function returns the position at which one string occurs within a second string as an integer; 0 is returned if not found.
[4.7.7.1] The LENGTH function returns the length of the string in characters as an integer.

## 4.7.7.2. Arithmetic Functions

[4.7.7.2] The ABS, CEILING, and FLOOR functions accept a numeric argument and return a number of the same type as the argument.
[4.7.7.2] The SIGN function accepts a numeric argument and returns an integer.
[4.7.7.2] The SQRT, EXP, and LN functions accept a numeric argument and return a double.
[4.7.7.2] The MOD function accepts two integer arguments and returns an integer.
[4.7.7.2] The ROUND function accepts a numeric argument and an integer argument and returns a number of the same type as the first argument.
[4.7.7.2] The POWER function accepts two numeric arguments and returns a double.
[4.7.7.2] Numeric arguments to these functions may correspond to the numeric Java object types as well as the primitive numeric types.
[4.7.7.2] The SIZE function returns an integer value, the number of elements of the collection; if the collection is empty, the SIZE function evaluates to zero.
[4.7.7.2] The INDEX function returns an integer value corresponding to the position of its argument in an ordered list; it can only be applied to identification variables denoting types for which an order column has been specified.

## 4.7.7.3. Datetime Functions

[4.7.7.3] The functions LOCAL DATE, LOCAL TIME, and LOCAL DATETIME return the value of the current date, time, or timestamp on the database server, respectively, with types java.time.LocalDate, java.time.LocalTime, and java.time.LocalDateTime.
[4.7.7.3] The functions CURRENT_DATE, CURRENT_TIME, and CURRENT_TIMESTAMP return the value of the current date, time, or timestamp on the database server, respectively, with types java.sql.Date, java.sql.Time, and java.sql.Timestamp.
[4.7.7.3] The EXTRACT function takes a datetime argument and one of the field type identifiers: YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND, DATE, TIME.
[4.7.7.3] For field type identifiers YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, EXTRACT returns an integer value; for SECOND, it returns a floating point value.
[4.7.7.3] For DATE and TIME field type identifiers, EXTRACT returns a part of the datetime value; it is illegal to pass a datetime argument which does not have the given field type or part to EXTRACT.

## 4.7.8. Typecasts

[4.7.8] The CAST function converts an expression of one type to an expression of a different type.
[4.7.8] The persistence provider is required to accept typecasts: any scalar expression to STRING, any string expression to INTEGER, LONG, FLOAT, or DOUBLE.
[4.7.8] When a typecast occurs as a select expression, the result type is java.lang.String for a cast to STRING, or java.lang.Integer, java.lang.Long, java.lang.Float, or java.lang.Double for a cast to INTEGER, LONG, FLOAT, or DOUBLE respectively.

## 4.7.9. Invocation of Predefined and User-defined Database Functions

[4.7.9] The invocation of functions other than the built-in functions of the Jakarta Persistence query language is supported by means of the function_invocation syntax; the function_name argument is a string that denotes the database function; the arguments and result must be suitable for the invocation context.
[4.7.9] Applications that use the function_invocation syntax will not be portable across databases.

## 4.7.10. Case Expressions

[4.7.10] The following forms of case expressions are supported: general case expressions, simple case expressions, coalesce expressions, and nullif expressions.

## 4.7.11. Identifier and Version Functions

[4.7.11] The ID and VERSION functions evaluate to the primary key or version, respectively, of their argument, which must be an identification variable assigned an entity abstract schema type or a path expression resolving to a one-to-one or many-to-one relationship field.
[4.7.11] The result type of an ID or VERSION function expression is the primary key type or version type of the argument entity, respectively.
[4.7.11] A persistence provider is not required to support the use of the ID function for entities with composite primary keys.

## 4.7.12. Entity Type Expressions

[4.7.12] The TYPE operator returns the exact type of its argument, which must be an identification variable assigned an entity abstract schema type, a path expression resolving to a one-to-one or many-to-one relationship field, or an input parameter.
[4.7.12] An entity_type_literal specifies a literal entity type by its entity name; for an input parameter, the entity type must be specified by calling Query.setParameter() with the java.lang.Class object representing the entity class.

## 4.7.13. Numeric Expressions and Type Promotion

[4.7.13] An expression that corresponds to a persistent state field is of the same type as that persistent state field.
[4.7.13] For a CASE expression, COALESCE expression, NULLIF expression, or arithmetic operator expression, the numeric type is determined by its operand types: Double (if any operand is Double/double), Float (if any operand is Float/float), BigDecimal, BigInteger (unless operator is /), Long (unless operator is /), or Integer (unless operator is /).
[4.7.13] For numeric expressions occurring in the SELECT clause, these rules determine the Java object type returned in the query result list.

## 4.8. GROUP BY, HAVING

[4.8] If a query contains both a WHERE clause and a GROUP BY clause, the effect is that of first applying the where clause, and then forming the groups and filtering them according to the HAVING clause.
[4.8] Any item that appears in the SELECT clause (other than as an aggregate function or as an argument to an aggregate function) must also appear in the GROUP BY clause; null values are treated as the same for grouping purposes.
[4.8] Grouping by an entity is permitted; such an entity must contain no serialized state fields or lob-valued state fields that are eagerly fetched; grouping by embeddables is not supported.
[4.8] The HAVING clause is used to filter over the groups, and can contain aggregate functions over attributes included in the groups and/or functions or other query language operators over the attributes that are used for grouping; it is not required that an aggregate function used in the HAVING clause also be used in the SELECT clause.
[4.8] If there is no GROUP BY clause and the HAVING clause is used, the result is treated as a single group, and the select list can only consist of aggregate functions; the use of HAVING in the absence of GROUP BY is not required to be supported by an implementation of this specification.

## 4.9. SELECT Clause

[4.9] The SELECT clause specifies the query result, as a list of items to be returned by the query; it can contain one or more of: an identification variable, a single-valued path expression, a scalar expression, an aggregate expression, a constructor expression.
[4.9] The SELECT clause must be specified to return only single-valued expressions.
[4.9] The DISTINCT keyword is used to specify that duplicate values must be eliminated from the query result; if not specified, duplicate values are not eliminated; the result of DISTINCT over embeddable objects or map entry results is undefined.
[4.9] Standalone identification variables in the SELECT clause may optionally be qualified by the OBJECT operator; the SELECT clause must not use the OBJECT operator to qualify path expressions.
[4.9] A result_variable assigns a name to a select_item in the query result; it must be a valid identifier, as defined in Section 4.4.1, must not be a reserved identifier, and must not collide with any identification variable declared in the FROM clause; result variables are case-insensitive and may be used to refer to an element of the select clause from an item in the ORDER BY clause.
[4.9] The SELECT clause is optional. A query with a missing SELECT clause is interpreted as if it had the single-item SELECT clause: select this, where this is the implicit identification variable.
[4.9] If the implicit identification variable has not been assigned an abstract schema type, the SELECT clause is required.

## 4.9.1. Result Type of the SELECT Clause

[4.9.1] The type of the query result specified by the SELECT clause is an entity abstract schema type, a state field type, the result of a scalar expression, the result of an aggregate function, the result of a construction operation, or some sequence of these.
[4.9.1] When multiple select expressions are used in the SELECT clause, the elements in the result correspond in order to the order of their specification in the SELECT clause and in type to the result types of each of the select expressions.
[4.9.1] The result type of an identification_variable is the type of the entity object or embeddable object to which the identification variable corresponds.
[4.9.1] The result type of a single_valued_path_expression that is a state_field_path_expression is the same type as the corresponding state field; if the state field is a primitive type, the result type is the corresponding object type.
[4.9.1] The result type of a single_valued_path_expression that is a single_valued_object_path_expression is the type of the entity object or embeddable object to which the path expression corresponds.
[4.9.1] The result type of a scalar_expression is the type of the scalar value to which the expression evaluates.
[4.9.1] The result type of an entity_type_expression scalar expression is the Java class to which the resulting abstract schema type corresponds.
[4.9.1] The result type of aggregate_expression is defined in Section 4.9.5.
[4.9.1] The result type of a constructor_expression is the type of the class for which the constructor is defined.

## 4.9.2. Constructor Expressions in the SELECT Clause

[4.9.2] A constructor may be used in the SELECT list to return an instance of a Java class; the specified class is not required to be an entity or to be mapped to the database; the constructor name must be fully qualified.
[4.9.2] If an entity class name is specified as the constructor name in the SELECT NEW clause, the resulting entity instances will be in either the new or the detached state, depending on whether a primary key is retrieved for the constructed object.
[4.9.2] If a single_valued_path_expression or identification_variable that is an argument to the constructor references an entity, the resulting entity instance referenced by that single_valued_path_expression or identification_variable will be in the managed state.

## 4.9.3. Null Values in the Query Result

[4.9.3] If the result of a query corresponds to an association field or state field whose value is null, that null value is returned in the result of the query method.
[4.9.3] State field types defined in terms of Java numeric primitive types cannot produce NULL values in the query result; a query that returns such a state field type as a result type must not return a null value.

## 4.9.4. Embeddables in the Query Result

[4.9.4] If the result of a query corresponds to an identification variable or state field whose value is an embeddable, the embeddable instance returned by the query will not be in the managed state.

## 4.9.5. Aggregate Functions in the SELECT Clause

[4.9.5] The result of a query may be the result of an aggregate function applied to a path expression.
[4.9.5] The following aggregate functions can be used in the SELECT clause of a query: AVG, COUNT, MAX, MIN, SUM, aggregate functions defined in the database.
