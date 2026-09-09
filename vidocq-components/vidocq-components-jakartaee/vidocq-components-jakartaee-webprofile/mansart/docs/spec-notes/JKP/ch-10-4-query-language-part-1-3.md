# 4. Query Language (part 1/3) — Requirements Note

4.2 — A Jakarta Persistence query language statement may be either a select statement, an update statement, or a delete statement.
4.2 — Any Jakarta Persistence query language statement may be constructed dynamically or may be statically defined in a metadata annotation or XML descriptor element.
4.2 — All statement types may have parameters.
4.2.1 — Every select statement has a FROM clause.
4.2.1.1 — A select statement may combine multiple select queries using the binary left-associative operators UNION, UNION ALL, INTERSECT, INTERSECT ALL, EXCEPT, and EXCEPT ALL.
4.2.1.1 — A provider is only required to support select statements where every constituent select query has the same number of items in the select clause.
4.2.1.1 — Corresponding items in the select clauses of constituent select queries must either have exactly the same type or be entity types which inherit a common entity type.
4.3 — The Jakarta Persistence query language is a typed language, and every expression has a type.
4.3.1 — The entity name is defined by the name element of the Entity annotation (or the entity-name XML descriptor element), and defaults to the unqualified name of the entity class.
4.3.1 — Entity names are scoped within the persistence unit and must be unique within the persistence unit.
4.4 — The FROM clause defines the domain of the query: one or more named entity abstract schema types, together with zero or more joined associations and collections.
4.4 — An identification variable is an identifier declared in the FROM clause of a query.
4.4 — Each identification variable is assigned an abstract schema type.
4.4 — If the domain has exactly one named entity abstract schema type and no joins, then the named entity does not require an explicit identification variable, and its identification variable defaults to the implicit identification variable, `this`.
4.4 — Otherwise, every element of the FROM clause — that is, every named entity abstract schema types and every join — must declare an identification variable.
4.4.1 — The following are reserved identifiers: ABS, ALL, AND, ANY, AS, ASC, AVG, BETWEEN, BIT_LENGTH, BOTH, BY, CASE, CEILING, CHAR_LENGTH, CHARACTER_LENGTH, CLASS, COALESCE, CONCAT, COUNT, CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP, DELETE, DESC, DISTINCT, ELSE, EMPTY, END, ENTRY, ESCAPE, EXISTS, EXP, EXTRACT, FALSE, FETCH, FIRST, FLOOR, FROM, FUNCTION, GROUP, HAVING, IN, INDEX, INNER, IS, JOIN, KEY, LEADING, LAST, LEFT, LENGTH, LIKE, LOCAL, LN, LOCATE, LOWER, MAX, MEMBER, MIN, MOD, NEW, NOT, NULL, NULLS, NULLIF, OBJECT, OF, ON, OR, ORDER, OUTER, POSITION, POWER, REPLACE, RIGHT, ROUND, SELECT, SET, SIGN, SIZE, SOME, SQRT, SUBSTRING, SUM, THEN, TRAILING, TREAT, TRIM, TRUE, TYPE, UNKNOWN, UPDATE, UPPER, VALUE, WHEN, WHERE.
4.4.1 — Reserved identifiers are case-insensitive.
4.4.1 — Reserved identifiers must not be used as identification variables or result variables.
4.4.2 — Every identification variable must be declared in the FROM clause, except for the implicit identification variable `this`.
4.4.2 — An identification variable must not be a reserved identifier.
4.4.2 — An identification variable may have the same name as an entity.
4.4.2 — Identification variables are case-insensitive.
4.4.2 — All identification variables used in the SELECT, WHERE, ORDER BY, GROUP BY, or HAVING clause of a SELECT or DELETE statement must be declared in the FROM clause.
4.4.2 — The identification variables used in the WHERE clause of an UPDATE statement must be declared in the UPDATE clause.
4.4.3 — The entity name in a range variable declaration is case-sensitive.
4.4.3 — If the query domain has more than one element, each named entity abstract schema type listed in the FROM clause must be a range variable declaration, and the implicit identification variable is not implicitly assigned an abstract schema type.
4.4.4 — A path expression is a sequence of identifiers uniquely identifying a state field or association field of an element of the query domain.
4.4.4 — A path expression may begin with a reference to an identification variable, followed by the navigation operator (.).
4.4.4 — If the first element of a path expression is not an identification variable, then the path expression is interpreted exactly as if it began with the implicit identification variable `this`.
4.4.4 — A reference to a state field or association field in a path expression is case-sensitive.
4.4.4 — The type of a path expression that navigates to an association field may be specified as a subtype of the declared type of the association field by means of the TREAT operator.
4.4.4 — The KEY, VALUE, and ENTRY operators may only be applied to identification variables that correspond to map-valued associations or map-valued element collections.
4.4.4 — A path expression using the ENTRY operator is terminal. It cannot be further composed and can only appear in the SELECT list of a query.
4.4.4 — Path expression navigability is composed using "inner join" semantics. That is, if the value of a non-terminal field in the path expression is null, the path is considered to have no value, and does not participate in the determination of the result.
