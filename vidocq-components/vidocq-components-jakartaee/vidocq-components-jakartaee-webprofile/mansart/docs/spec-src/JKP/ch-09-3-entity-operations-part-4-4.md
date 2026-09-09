# 3. Entity Operations (part 4/4)

StoredProcedureParameter metadata needs to
be provided for all parameters. Parameters must be specified in the
order in which they occur in the parameter list of the stored procedure.
If parameter names are used, the parameter name is used to bind the
parameter value and to extract the output value (if the parameter is an
INOUT or OUT parameter). If parameter names are not specified, it is
assumed that positional parameters are used. The mixing of named and
positional parameters is invalid.

3.11.12.2. Dynamically-specified Stored Procedure Queries

If the stored procedure is not defined using
metadata, parameter and result set information must be provided
dynamically.

All parameters of a dynamically-specified
stored procedure query must be registered using the
registerStoredProcedureParameter method of the StoredProcedureQuery
interface.

Result set mapping information can be
provided by means of the createStoredProcedureQuery method.

3.11.12.3. Stored Procedure Query Execution

Stored procedure query execution can be
controlled as described below.

The setParameter methods are used to set
the values of all required IN and INOUT parameters. It is not required
to set the values of stored procedure parameters for which default
values have been defined by the stored procedure.

When getResultList, getSingleResult, and getSingleResultOrNull
are called on a StoredProcedureQuery object, the persistence provider
will call execute on an unexecuted stored procedure query before
processing getResultList, getSingleResult or getSingleResultOrNull.

When executeUpdate is called on a
StoredProcedureQuery object, the persistence provider will call
execute on an unexecuted stored procedure query followed by
getUpdateCount. The results of executeUpdate will be those of
getUpdateCount.

The execute method supports both the simple
case where scalar results are passed back only via INOUT and OUT
parameters as well as the most general case (multiple result sets and/or
update counts, possibly also in combination with output parameter
values).

The execute method returns true if the
first result is a result set, and false if it is an update count or
there are no results other than through INOUT and OUT parameters, if
any.

If the execute method returns true, the pending result set can be
obtained by calling getResultList, getSingleResult, or
getSingleResultOrNull. The hasMoreResults method can then be used to
test for further results.

If execute or hasMoreResults returns
false, the getUpdateCount method can be called to obtain the
pending result if it is an update count. The getUpdateCount method
will return either the update count (zero or greater) or -1 if there is
no update count (i.e., either the next result is a result set or there
is no next update count).

For portability, results that correspond to
JDBC result sets and update counts need to be processed before the
values of any INOUT or OUT parameters are extracted.

After results returned through
getResultList and getUpdateCount have been exhausted, results
returned through INOUT and OUT parameters can be retrieved.

The getOutputParameterValue methods are
used to retrieve the values passed back from the procedure through INOUT
and OUT parameters.

When using REF_CURSOR parameters for result
sets, the update counts should be exhausted before calling
getResultList to retrieve the result set. Alternatively, the
REF_CURSOR result set can be retrieved through
getOutputParameterValue. Result set mappings will be applied to
results corresponding to REF_CURSOR parameters in the order the
REF_CURSOR parameters were registered with the query.

In the simplest case, where results are
returned only via INOUT and OUT parameters, execute can be followed
immediately by calls to getOutputParameterValue.

3.12. Summary of Exceptions

The following is a summary of the exceptions defined by this specification:

PersistenceException

The PersistenceException is thrown by the
persistence provider when a problem occurs. It may be thrown to report
that the invoked operation could not complete because of an unexpected
error (e.g., failure of the persistence provider to open a database
connection).

All other exceptions defined by this
specification are subclasses of the PersistenceException. All
instances of PersistenceException except for instances of
NoResultException, NonUniqueResultException, LockTimeoutException
, and QueryTimeoutException will cause the current transaction, if one
is active and the persistence context has been joined to it, to be
marked for rollback.

TransactionRequiredException

The TransactionRequiredException is thrown
by the persistence provider when a transaction is required but is not
active.

OptimisticLockException

The OptimisticLockException is thrown by
the persistence provider when an optimistic locking conflict occurs.
This exception may be thrown as part of an API call, at flush, or at
commit time. The current transaction, if one is active, will be marked
for rollback.

PessimisticLockException

The PessimisticLockException is thrown by
the persistence provider when a pessimistic locking conflict occurs. The
current transaction will be marked for rollback. Typically the
PessimisticLockException occurs because the database transaction has
been rolled back due to deadlock or because the database uses
transaction-level rollback when a pessimistic lock cannot be granted.

LockTimeoutException

The LockTimeoutException is thrown by the
persistence provider when a pessimistic locking conflict occurs that
does not result in transaction rollback. Typically this occurs because
the database uses statement-level rollback when a pessimistic lock
cannot be granted (and there is no deadlock). The LockTimeoutException
does not cause the current transaction to be marked for rollback.

RollbackException

The RollbackException is thrown by the
persistence provider when EntityTransaction.commit fails.

EntityExistsException

The EntityExistsException may thrown by the
persistence provider when the persist operation is invoked and the
entity already exists. The EntityExistsException may be thrown when
the persist operation is invoked, or the EntityExistsException or
another PersistenceException may be thrown at commit time. The current
transaction, if one is active and the persistence context has been
joined to it, will be marked for rollback.

EntityNotFoundException

The EntityNotFoundException is thrown by
the persistence provider when an entity reference obtained by
getReference is accessed but the entity does not exist. It is thrown
by the refresh operation when the entity no longer exists in the
database. It is also thrown by the lock operation when pessimistic
locking is used and the entity no longer exists in the database. The
current transaction, if one is active and the persistence context has
been joined to it, will be marked for rollback.

NoResultException

The NoResultException is thrown by the persistence provider when
Query.getSingleResult is invoked and there is no result to return.
This exception will not cause the current transaction, if one is
active, to be marked for rollback.

NonUniqueResultException

The NonUniqueResultException is thrown by the persistence provider
when Query.getSingleResult or Query.getSingleResultOrNull is
invoked and there is more than one result from the query. This
exception will not cause the current transaction, if one is active,
to be marked for rollback.

QueryTimeoutException

The QueryTimeoutException is thrown by the
persistence provider when a query times out and only the statement is
rolled back. The QueryTimeoutException does not cause the current
transaction, if one is active, to be marked for rollback.
