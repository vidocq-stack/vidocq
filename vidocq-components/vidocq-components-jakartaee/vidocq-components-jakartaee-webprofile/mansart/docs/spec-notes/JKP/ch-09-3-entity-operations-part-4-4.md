# ch-09-3-entity-operations-part-4-4 — Normative Requirements

## 3.11.12.1 — Stored Procedure Metadata

- StoredProcedureParameter metadata **must** be provided for all parameters (Section 3.11.12.1)
- Parameters **must** be specified in the order in which they occur in the parameter list of the stored procedure (Section 3.11.12.1)
- If parameter names are not specified, positional parameters **must** be assumed (Section 3.11.12.1)
- The mixing of named and positional parameters **is** invalid (Section 3.11.12.1)

## 3.11.12.2 — Dynamically-specified Stored Procedure Queries

- Parameter and result set information **must** be provided dynamically when the stored procedure is not defined using metadata (Section 3.11.12.2)
- All parameters of a dynamically-specified stored procedure query **must** be registered using the `registerStoredProcedureParameter` method of the StoredProcedureQuery interface (Section 3.11.12.2)

## 3.11.12.3 — Stored Procedure Query Execution

- The `setParameter` methods **are** used to set the values of all required IN and INOUT parameters (Section 3.11.12.3)
- It **is** not required to set the values of stored procedure parameters for which default values have been defined by the stored procedure (Section 3.11.12.3)
- When `getResultList`, `getSingleResult`, or `getSingleResultOrNull` **are** called on a StoredProcedureQuery object, the persistence provider **will** call `execute` on an unexecuted stored procedure query before processing the result (Section 3.11.12.3)
- When `executeUpdate` **is** called on a StoredProcedureQuery object, the persistence provider **will** call `execute` on an unexecuted stored procedure query followed by `getUpdateCount` (Section 3.11.12.3)
- The `execute` method **supports** both the simple case where scalar results **are** passed back only via INOUT and OUT parameters as well as the most general case (multiple result sets and/or update counts, possibly also in combination with output parameter values) (Section 3.11.12.3)
- The `execute` method **returns** true if the first result **is** a result set, and false if it **is** an update count or there **are** no results other than through INOUT and OUT parameters (Section 3.11.12.3)
- If the `execute` method **returns** true, the pending result set **can** be obtained by calling `getResultList`, `getSingleResult`, or `getSingleResultOrNull` (Section 3.11.12.3)
- The `hasMoreResults` method **can** then **be** used to test for further results (Section 3.11.12.3)
- If `execute` or `hasMoreResults` **returns** false, the `getUpdateCount` method **can** **be** called to obtain the pending result if it **is** an update count (Section 3.11.12.3)
- The `getUpdateCount` method **will** return either the update count (zero or greater) or -1 if there **is** no update count (Section 3.11.12.3)
- Results that correspond to JDBC result sets and update counts **need** to be processed before the values of any INOUT or OUT parameters **are** extracted (Section 3.11.12.3)
- After results returned through `getResultList` and `getUpdateCount` **have** been exhausted, results returned through INOUT and OUT parameters **can** **be** retrieved (Section 3.11.12.3)
- The `getOutputParameterValue` methods **are** used to retrieve the values passed back from the procedure through INOUT and OUT parameters (Section 3.11.12.3)
- When using REF_CURSOR parameters for result sets, the update counts **should** **be** exhausted before calling `getResultList` to retrieve the result set (Section 3.11.12.3)
- Alternatively, the REF_CURSOR result set **can** **be** retrieved through `getOutputParameterValue` (Section 3.11.12.3)
- Result set mappings **will** **be** applied to results corresponding to REF_CURSOR parameters in the order the REF_CURSOR parameters **were** registered with the query (Section 3.11.12.3)
- In the simplest case, where results **are** returned only via INOUT and OUT parameters, `execute` **can** **be** followed immediately by calls to `getOutputParameterValue` (Section 3.11.12.3)

## 3.12 — Summary of Exceptions

- The PersistenceException **is** thrown by the persistence provider when a problem occurs (Section 3.12)
- It **may** **be** thrown to report that the invoked operation could not complete because of an unexpected error (Section 3.12)
- All other exceptions defined by this specification **are** subclasses of the PersistenceException (Section 3.12)
- All instances of PersistenceException except for instances of NoResultException, NonUniqueResultException, LockTimeoutException, and QueryTimeoutException **will** cause the current transaction, if one **is** active and the persistence context **has** been joined to it, **to** **be** marked for rollback (Section 3.12)
- The TransactionRequiredException **is** thrown by the persistence provider when a transaction **is** required but **is** not active (Section 3.12)
- The OptimisticLockException **is** thrown by the persistence provider when an optimistic locking conflict occurs (Section 3.12)
- This exception **may** **be** thrown as part of an API call, at flush, or at commit time (Section 3.12)
- The current transaction, if one **is** active, **will** **be** marked for rollback (Section 3.12)
- The PessimisticLockException **is** thrown by the persistence provider when a pessimistic locking conflict occurs (Section 3.12)
- The current transaction **will** **be** marked for rollback (Section 3.12)
- The LockTimeoutException **is** thrown by the persistence provider when a pessimistic locking conflict occurs that does not result in transaction rollback (Section 3.12)
- The LockTimeoutException **does** not cause the current transaction **to** **be** marked for rollback (Section 3.12)
- The RollbackException **is** thrown by the persistence provider when EntityTransaction.commit fails (Section 3.12)
- The EntityExistsException **may** **be** thrown by the persistence provider when the persist operation **is** invoked and the entity already exists (Section 3.12)
- The EntityExistsException **may** **be** thrown when the persist operation **is** invoked, or the EntityExistsException or another PersistenceException **may** **be** thrown at commit time (Section 3.12)
- The current transaction, if one **is** active and the persistence context **has** been joined to it, **will** **be** marked for rollback (Section 3.12)
- The EntityNotFoundException **is** thrown by the persistence provider when an entity reference obtained by `getReference` **is** accessed but the entity does not exist (Section 3.12)
- It **is** thrown by the refresh operation when the entity no longer exists in the database (Section 3.12)
- It **is** also thrown by the lock operation when pessimistic locking **is** used and the entity no longer exists in the database (Section 3.12)
- The current transaction, if one **is** active and the persistence context **has** been joined to it, **will** **be** marked for rollback (Section 3.12)
- The NoResultException **is** thrown by the persistence provider when `Query.getSingleResult` **is** invoked and there **is** no result to return (Section 3.12)
- This exception **will** not cause the current transaction, if one **is** active, **to** **be** marked for rollback (Section 3.12)
- The NonUniqueResultException **is** thrown by the persistence provider when `Query.getSingleResult` or `Query.getSingleResultOrNull` **is** invoked and there **is** more than one result from the query (Section 3.12)
- This exception **will** not cause the current transaction, if one **is** active, **to** **be** marked for rollback (Section 3.12)
- The QueryTimeoutException **is** thrown by the persistence provider when a query times out and only the statement **is** rolled back (Section 3.12)
- The QueryTimeoutException **does** not cause the current transaction, if one **is** active, **to** **be** marked for rollback (Section 3.12)
