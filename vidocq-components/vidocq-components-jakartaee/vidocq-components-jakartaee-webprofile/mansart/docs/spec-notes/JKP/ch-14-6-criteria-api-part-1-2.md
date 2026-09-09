# 6. Criteria API (part 1/2) — Requirements Note

## 6.3.3 Joins

- 6.3.3: The join methods may be applied to instances of the Root and Join types.
- 6.3.3: The result of a join method is a Join object (instance of the Join, CollectionJoin, SetJoin, ListJoin, or MapJoin types) that captures the source and target types of the join.
- 6.3.3: By default, the join method defines an inner join. Outer joins are defined by explicitly specifying a JoinType argument.

## 6.3.4 Fetch Joins

- 6.3.4: An association or attribute referenced by the fetch method must be referenced from an entity or embeddable that is returned as the result of the query.
- 6.3.4: The fetch method must not be used in a subquery.
- 6.3.4: Multiple levels of fetch joins are not required to be supported by an implementation of this specification.

## 6.3.5 Path Navigation

- 6.3.5: When a criteria query is executed, path navigation—like path navigation using the Jakarta Persistence query language—is obtained using "inner join" semantics.

## 6.3.6 Restricting the Query Result

- 6.3.6: The result of a query can be restricted by specifying one or more predicate conditions.
- 6.3.6: Restriction predicates are applied to the CriteriaQuery object by means of the where method.
- 6.3.6: The argument to the where method can be either an Expression<Boolean> instance or zero or more Predicate instances.
- 6.3.6: A simple predicate is created by invoking one of the conditional methods of the CriteriaBuilder interface, or by the isNull, isNotNull, and in methods of the Expression interface.
- 6.3.6: Compound predicates are constructed by means of the and, or, and not methods of the CriteriaBuilder interface.
- 6.3.6: The restrictions upon the types to which conditional operations are permitted to be applied are the same as the respective operators of the Jakarta Persistence query language.
- 6.3.6: The same null value semantics as described in Section 4.6.13 and the subsections of Section 4.6 apply.
- 6.3.6: The equality and comparison semantics described in Section 4.6.14 apply.

## 6.3.8 Expressions

- 6.3.8: An Expression or one of its subtypes can be used in the construction of the query's select list or in the construction of where or having method conditions.
- 6.3.8: Paths and boolean predicates are expressions.
- 6.3.8: The CriteriaBuilder interface provides methods corresponding to the built-in arithmetic, string, datetime, and case operators and functions of the Jakarta Persistence query language.
- 6.3.8: The type method can only be applied to a path expression.
- 6.3.8: The index method can be applied to a ListJoin object that corresponds to a list for which an order column has been specified.
- 6.3.8: The aggregation methods avg, max, min, sum, count can only be used in the construction of the select list or in having method conditions.
- 6.3.8: The size method can be applied to a path expression that corresponds to an association or element collection.

## 6.3.8.1 Result Types of Expressions

- 6.3.8.1: The getJavaType method, as defined in the TupleElement interface, returns the runtime type of the object on which it is invoked.
- 6.3.8.1: For non-numerical operands, the implementation must return the most specific common superclass of the types of the operands used to form the result.
- 6.3.8.1: The following rules must be observed by the implementation when materializing the results of numeric expressions involving these methods:
  - If there is an operand of type Double, the result of the operation is of type Double.
  - Otherwise, if there is an operand of type Float, the result of the operation is of type Float.
  - Otherwise, if there is an operand of type BigDecimal, the result of the operation is of type BigDecimal.
  - Otherwise, if there is an operand of type BigInteger, the result of the operation is of type BigInteger, unless the method is quot, in which case the numeric result type is not further defined.
  - Otherwise, if there is an operand of type Long, the result of the operation is of type Long, unless the method is quot, in which case the numeric result type is not further defined.
  - Otherwise, if there is an operand of integral type, the result of the operation is of type Integer, unless the method is quot, in which case the numeric result type is not further defined.

## 6.3.8.2 Literals

- 6.3.8.2: An Expression literal instance is obtained by passing a value to the literal method of the CriteriaBuilder interface.
- 6.3.8.2: An Expression instance representing a null is created by the nullLiteral method of the CriteriaBuilder interface.

## 6.3.9 Coalesce and Nullif

- 6.3.9: The coalesce method returns the first non-null argument or null if all arguments are null.
- 6.3.9: The nullif method returns null if the two arguments are equal, otherwise returns the first argument.

## 6.3.10 Case Expressions

- 6.3.10: Simple case expressions are created by means of the selectCase method of the CriteriaBuilder interface.
- 6.3.10: General case expressions are created by means of the selectCase method of the CriteriaBuilder interface.

## 6.3.11 Specifying the Query Select List

- 6.3.11: The select method takes a single Selection argument, which can be either an Expression instance or a CompoundSelection instance.
- 6.3.11: The type of the Selection item must be assignable to the defined CriteriaQuery result type.
- 6.3.11: The construct, tuple and array methods of the CriteriaBuilder interface are used to aggregate multiple selection items into a CompoundSelection instance.
- 6.3.11: The multiselect method supports the specification and aggregation of multiple selection items.
- 6.3.11: A Selection instance passed to the construct, tuple, array, or multiselect methods can be one of the following:
  - An Expression instance.
  - A Selection instance obtained as the result of the invocation of the CriteriaBuilder construct method.
- 6.3.11: The distinct method of the CriteriaQuery interface is used to specify that duplicate values must be eliminated from the query result.
- 6.3.11: If the distinct method is not used or distinct(false) is invoked on the criteria query object, duplicate values are not eliminated.
- 6.3.11: When distinct(true) is used, and the select items include embeddable objects or map entry results, the elimination of duplicates is performed as if the entire result of the select items were treated as a single value.

## 6.3.12 Specifying Query Result Grouping

- 6.3.12: The groupBy method is used to specify the grouping of query results.
- 6.3.12: The having method is used to specify conditions on grouped results.
- 6.3.12: When the groupBy method is used, each selection item that is not the result of applying an aggregate method must correspond to a path expression that is used for defining the grouping.
- 6.3.12: Requirements on the types that correspond to the elements of the grouping and having constructs and their relationship to the select items are as specified in Section 4.8.

## 6.3.13 Specifying Query Result Ordering

- 6.3.13: The ordering of the results of a query is defined by use of the orderBy method of the CriteriaQuery instance.
- 6.3.13: The arguments to the orderBy method are Order instances.
- 6.3.13: An Order instance is created by means of the asc and desc methods of the CriteriaBuilder interface.
- 6.3.13: An argument to either of the asc and desc methods must be one of the following:
  - Any Expression instance that corresponds to an orderable state field of an entity or embeddable class abstract schema type that is specified as an argument to the select or multiselect method or that is an argument to a tuple or array constructor that is passed as an argument to the select method.
  - A Selection instance obtained as the result of the invocation of the CriteriaBuilder construct, tuple, or array method that is passed as an argument to the select method.

## 6.3.15 CriteriaUpdate, CriteriaDelete, and Root

- 6.3.15: The update and delete query methods of the CriteriaBuilder interface are used to construct CriteriaUpdate and CriteriaDelete objects.
- 6.3.15: The from method of the AbstractQuery interface is used to create and add query roots.
- 6.3.15: The argument to the from method is the entity class or EntityType instance for the entity.
- 6.3.15: The result of the from method is a Root object.
- 6.3.15: The Root interface extends the From interface, which represents objects that may occur in the from clause of a query.
- 6.3.15: A CriteriaQuery object may have more than one root.
- 6.3.15: The addition of a query root has the semantic effect of creating a cartesian product between the entity type referenced by the added root and those of the other roots.
