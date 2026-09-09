# 4. Query Language (part 3/3) — Requirements Note

## Aggregate Functions (Section 4)

4.1. For all aggregate functions except COUNT, the path expression argument must terminate in a state field.
4.2. The path expression argument to COUNT may terminate in a state field, an association field, or be an identification variable.
4.3. Arguments to SUM and AVG must be numeric.
4.4. Arguments to MAX and MIN must correspond to orderable state field types (numeric, string, character, or date types).
4.5. COUNT returns Long.
4.6. MAX and MIN return the type of the state field to which they are applied.
4.7. AVG returns Double.
4.8. SUM returns Long for integral state fields (other than BigInteger), Double for floating point state fields, BigInteger for BigInteger state fields, and BigDecimal for BigDecimal state fields.
4.9. Null values are eliminated before the aggregate function is applied, regardless of whether DISTINCT is specified.
4.10. If SUM, AVG, MAX, or MIN is used and there are no values to which the aggregate function can be applied, the result is NULL.
4.11. If COUNT is used and there are no values to which COUNT can be applied, the result is 0.
4.12. The argument to an aggregate function may be preceded by DISTINCT to specify that duplicate values are eliminated before application.
4.13. DISTINCT with COUNT is not supported for arguments of embeddable types or map entry types.
4.14. Invocation of aggregate database functions, including user-defined functions, is supported by means of the FUNCTION operator.

## 4.10. ORDER BY Clause

4.10.1. The ORDER BY clause specifies how query results should be sorted.
4.10.2. Each orderby_expression must be one of: a state_field_path_expression evaluating to an orderable state field of an entity/embeddable class designated in the SELECT clause, a state_field_path_expression evaluating to the same state field of the same type as one in the SELECT clause, a general_identification_variable evaluating to the same map field as one in the SELECT clause, a result_variable declared by an orderable item in the SELECT clause, or a scalar_expression involving only allowed state_field_path_expressions.
4.10.3. Items occurring earlier in the ORDER BY clause take precedence over later items.
4.10.4. The order of query results must be preserved in the result list or stream returned by a query execution method when an ORDER BY clause is specified.
4.10.5. The keyword ASC specifies ascending ordering; the keyword DESC specifies descending ordering. If neither is specified, ascending ordering is the default.
4.10.6. The keyword NULLS specifies ordering of null values as either FIRST (nulls before non-nulls) or LAST (nulls after non-nulls). If NULLS is not specified, the database determines null ordering.

## 4.11. Bulk Update and Delete Operations

4.11.1. Bulk update and delete operations apply to entities of a single entity class (together with its subclasses, if any).
4.11.2. Only one entity abstract schema type may be specified in the FROM or UPDATE clause.
4.11.3. A delete operation only applies to entities of the specified class and its subclasses; it does not cascade to related entities.
4.11.4. The new_value specified for an update operation must be compatible in type with the field to which it is assigned.
4.11.5. Portable applications must manually update the value of the version column, if desired, and/or manually validate the value of the version column, because bulk update bypasses optimistic locking checks.
4.11.6. The persistence context is not synchronized with the result of a bulk update or delete.

## Total requirements: 28
