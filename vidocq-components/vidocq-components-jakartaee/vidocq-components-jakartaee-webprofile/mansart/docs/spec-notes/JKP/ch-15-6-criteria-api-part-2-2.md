# ch-15-6-criteria-api-part-2-2 — Normative Requirements

## Section 6 — Criteria API (bulk operations)

6.1 Bulk update and delete operations map directly to database operations, bypassing any optimistic locking checks.
6.2 Portable applications using bulk update operations must manually update the value of the version column, if desired.
6.3 Portable applications using bulk update operations must manually validate the value of the version column.
6.4 The persistence context is not synchronized with the result of the bulk update or delete.

## Section 6.6 — Query Modification

6.6.1 Query elements — predicate conditions, select lists, etc. — are dependent on the CriteriaQuery, CriteriaUpdate, or CriteriaDelete instance, and are thus not portably reusable with different instances.

## Section 6.7 — Query Execution

6.7.1 A criteria query is executed by passing the CriteriaQuery, CriteriaUpdate, or CriteriaDelete object to the createQuery method of the EntityManager interface to create an executable TypedQuery object (or, in the case of CriteriaUpdate and CriteriaDelete, a Query object).
6.7.2 The modification of the CriteriaQuery, CriteriaUpdate, or CriteriaDelete object does not have any impact on the already created executable query object.
6.7.3 If the modified CriteriaQuery, CriteriaUpdate, or CriteriaDelete object is passed to the createQuery method, the persistence provider must ensure that a new executable query object is created and returned that reflects the semantics of the changed query definition.
6.7.4 CriteriaQuery, CriteriaUpdate, and CriteriaDelete objects must be serializable.
6.7.5 A persistence vendor is required to support the subsequent deserialization of such an object into a separate JVM instance of that vendor's runtime.
6.7.6 Both runtime instances must have access to any required vendor implementation classes.
6.7.7 CriteriaQuery, CriteriaUpdate, and CriteriaDelete objects are not required to be interoperable across vendors.
