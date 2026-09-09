# Appendix C: Criteria API Interfaces (part 2/3)

 /**
 * Interface used to build coalesce expressions. 
 * 
 * A coalesce expression is equivalent to a case expression
 * that returns null if all its arguments evaluate to null,
 * and the value of its first non-null argument otherwise.
 */
 interface Coalesce<T> extends Expression<T> {

 /**
 * Add an argument to the coalesce expression.
 * @param value value
 * @return coalesce expression
 */
 Coalesce<T> value(T value);

 /**
 * Add an argument to the coalesce expression.
 * @param value expression
 * @return coalesce expression
 */
 Coalesce<T> value(Expression<? extends T> value);
 }
 
 /**
 * Create a coalesce expression.
 * @return coalesce expression
 */
 <T> Coalesce<T> coalesce();

 //case builders:

 /**
 * Interface used to build simple case expressions.
 * Case conditions are evaluated in the order in which
 * they are specified.
 */
 interface SimpleCase<C,R> extends Expression<R> {

 /**
 * Return the expression to be tested against the
 * conditions.
 * @return expression
 */
 Expression<C> getExpression();

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result value
 * @return simple case expression
 */
 SimpleCase<C, R> when(C condition, R result);

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result expression
 * @return simple case expression
 */
 SimpleCase<C, R> when(C condition, Expression<? extends R> result);

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result value
 * @return simple case expression
 */
 SimpleCase<C, R> when(Expression<? extends C> condition, R result);

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result expression
 * @return simple case expression
 */
 SimpleCase<C, R> when(Expression<? extends C> condition, Expression<? extends R> result);

 /**
 * Add an "else" clause to the case expression.
 * @param result "else" result
 * @return expression
 */
 Expression<R> otherwise(R result);

 /**
 * Add an "else" clause to the case expression.
 * @param result "else" result expression
 * @return expression
 */
 Expression<R> otherwise(Expression<? extends R> result);
 }
 
 /**
 * Create a simple case expression.
 * @param expression to be tested against the case conditions
 * @return simple case expression
 */
 <C, R> SimpleCase<C,R> selectCase(Expression<? extends C> expression);

 /**
 * Interface used to build general case expressions.
 * Case conditions are evaluated in the order in which
 * they are specified.
 */
 interface Case<R> extends Expression<R> {

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result value
 * @return general case expression
 */
 Case<R> when(Expression<Boolean> condition, R result);

 /**
 * Add a when/then clause to the case expression.
 * @param condition "when" condition
 * @param result "then" result expression
 * @return general case expression
 */
 Case<R> when(Expression<Boolean> condition, Expression<? extends R> result);

 /**
 * Add an "else" clause to the case expression.
 * @param result "else" result
 * @return expression
 */
 Expression<R> otherwise(R result);

 /**
 * Add an "else" clause to the case expression.
 * @param result "else" result expression
 * @return expression
 */
 Expression<R> otherwise(Expression<? extends R> result);
 }
 
 /**
 * Create a general case expression.
 * @return general case expression
 */
 <R> Case<R> selectCase();

 /**
 * Create an expression for the execution of a database
 * function.
 * @param name function name
 * @param type expected result type
 * @param args function arguments
 * @return expression
 */
 <T> Expression<T> function(String name, Class<T> type,
Expression<?>... args);

 // methods for downcasting:

 /**
 * Downcast Join object to the specified type.
 * @param join Join object
 * @param type type to be downcast to
 * @return Join object of the specified type
 * @since 2.1
 */
 <X, T, V extends T> Join<X, V> treat(Join<X, T> join, Class<V> type);

 /**
 * Downcast CollectionJoin object to the specified type.
 * @param join CollectionJoin object
 * @param type type to be downcast to
 * @return CollectionJoin object of the specified type
 * @since 2.1
 */
 <X, T, E extends T> CollectionJoin<X, E> treat(CollectionJoin<X, T> join, Class<E> type);

 /**
 * Downcast SetJoin object to the specified type.
 * @param join SetJoin object
 * @param type type to be downcast to
 * @return SetJoin object of the specified type
 * @since 2.1
 */
 <X, T, E extends T> SetJoin<X, E> treat(SetJoin<X, T> join, Class<E> type);

 /**
 * Downcast ListJoin object to the specified type.
 * @param join ListJoin object
 * @param type type to be downcast to
 * @return ListJoin object of the specified type
 * @since 2.1
 */
 <X, T, E extends T> ListJoin<X, E> treat(ListJoin<X, T> join, Class<E> type);

 /**
 * Downcast MapJoin object to the specified type.
 * @param join MapJoin object
 * @param type type to be downcast to
 * @return MapJoin object of the specified type
 * @since 2.1
 */
 <X, K, T, V extends T> MapJoin<X, K, V> treat(MapJoin<X, K, T> join, Class<V> type);

 /**
 * Downcast Path object to the specified type.
 * @param path path
 * @param type type to be downcast to
 * @return Path object of the specified type
 * @since 2.1
 */
 <X, T extends X> Path<T> treat(Path<X> path, Class<T> type);

 /**
 * Downcast Root object to the specified type.
 * @param root root
 * @param type type to be downcast to
 * @return Root object of the specified type
 * @since 2.1
 */
 <X, T extends X> Root<T> treat(Root<X> root, Class<T> type);

 /**
 * Create a query which is the union of the given queries.
 * @return a new criteria query which returns the union of
 * the results of the given queries
 * @since 3.2
 */
 <T> CriteriaSelect<T> union(CriteriaSelect<? extends T> left, CriteriaSelect<? extends T> right);

 /**
 * Create a query which is the union of the given queries,
 * without elimination of duplicate results.
 * @return a new criteria query which returns the union of
 * the results of the given queries
 * @since 3.2
 */
 <T> CriteriaSelect<T> unionAll(CriteriaSelect<? extends T> left, CriteriaSelect<? extends T> right);

 /**
 * Create a query which is the intersection of the given queries.
 * @return a new criteria query which returns the intersection of
 * the results of the given queries
 * @since 3.2
 */
 <T> CriteriaSelect<T> intersect(CriteriaSelect<? super T> left, CriteriaSelect<? super T> right);

 /**
 * Create a query which is the intersection of the given queries,
 * without elimination of duplicate results.
 * @return a new criteria query which returns the intersection of
 * the results of the given queries
 * @since 3.2
 */
 <T> CriteriaSelect<T> intersectAll(CriteriaSelect<? super T> left, CriteriaSelect<? super T> right);

 /**
 * Create a query by (setwise) subtraction of the second query
 * from the first query.
 * @return a new criteria query which returns the result of
 * subtracting the results of the second query from the
 * results of the first query
 * @since 3.2
 */
 <T> CriteriaSelect<T> except(CriteriaSelect<T> left, CriteriaSelect<?> right);

 /**
 * Create a query by (setwise) subtraction of the second query
 * from the first query, without elimination of duplicate results.
 * @return a new criteria query which returns the result of
 * subtracting the results of the second query from the
 * results of the first query
 * @since 3.2
 */
 <T> CriteriaSelect<T> exceptAll(CriteriaSelect<T> left, CriteriaSelect<?> right);
}

C.2. CriteriaDelete

import jakarta.persistence.metamodel.EntityType;

/**
 * The {@code CriteriaDelete} interface defines functionality for 
 * performing bulk delete operations using the Criteria API
 *
 * <p>Criteria API bulk delete operations map directly to database 
 * delete operations. The persistence context is not synchronized 
 * with the result of the bulk delete.
 *
 * <p> A {@code CriteriaDelete} object must have a single root.
 *
 * @param <T> the entity type that is the target of the DELETE
 *
 * @since 2.1
 */
public interface CriteriaDelete<T> extends CommonAbstractCriteria {

 /**
 * Create and add a query root corresponding to the entity
 * that is the target of the DELETE.
 * A {@code CriteriaDelete} object has a single root, the entity that
 * is being deleted.
 * @param entityClass the entity class
 * @return query root corresponding to the given entity
 */
 Root<T> from(Class<T> entityClass);

 /**
 * Create and add a query root corresponding to the entity
 * that is the target of the DELETE.
 * A {@code CriteriaDelete} object has a single root, the entity that
 * is being deleted.
 * @param entity metamodel entity representing the entity
 * of type X
 * @return query root corresponding to the given entity
 */
 Root<T> from(EntityType<T> entity);

 /**
 * Return the query root.
 * @return the query root
 */
 Root<T> getRoot();

 /**
 * Modify the DELETE query to restrict the target of the deletion 
 * according to the specified boolean expression.
 * Replaces the previously added restriction(s), if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified delete query
 */ 
 CriteriaDelete<T> where(Expression<Boolean> restriction);

 /**
 * Modify the DELETE query to restrict the target of the deletion
 * according to the conjunction of the specified restriction 
 * predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions zero or more restriction predicates
 * @return the modified delete query
 */
 CriteriaDelete<T> where(Predicate... restrictions);

}

C.3. CriteriaQuery

package jakarta.persistence.criteria;

import jakarta.persistence.Tuple;

import java.util.List;

/**
 * The {@code CriteriaQuery} interface defines functionality that is
 * specific to top-level queries.
 *
 * @param <T> the type of the defined result
 *
 * @since 2.0
 */
public interface CriteriaQuery<T> extends AbstractQuery<T>, CriteriaSelect<T> {
 
 /**
 * Specify the item that is to be returned in the query result.
 * Replaces the previously specified selection(s), if any.
 * 
 * <p> Note: Applications using the string-based API may need to
 * specify the type of the select item when it results from
 * a get or join operation and the query result type is 
 * specified. 
 *
 * <p>For example:
 * {@snippet :
 * CriteriaQuery<String> q = cb.createQuery(String.class);
 * Root<Order> order = q.from(Order.class);
 * q.select(order.get("shippingAddress").<String>get("state"));
 * 
 * CriteriaQuery<Product> q2 = cb.createQuery(Product.class);
 * q2.select(q2.from(Order.class)
 * .join("items")
 * .<Item, Product>join("product"));
 * }
 *
 * @param selection selection specifying the item that is
 * to be returned in the query result
 * @return the modified query
 * @throws IllegalArgumentException if the selection is
 * a compound selection and more than one selection
 * item has the same assigned alias
 */
 CriteriaQuery<T> select(Selection<? extends T> selection);

 /**
 * Specify the selection items that are to be returned in the query result.
 * Replaces the previously specified selection(s), if any.
 *
 * <p> The type of the result of the query execution depends on the specification
 * of the type of the criteria query object created as well as the arguments
 * to the {@code multiselect} method.
 *
 * <p> An argument to the multiselect method must not be a tuple- or array-valued
 * compound selection item.
 *
 * <p>The semantics of this method are as follows: 
 * <ul> 
 * <li> 
 * If the type of the criteria query is {@code CriteriaQuery<Tuple>}
 * (i.e., a criteria query object created by either the {@code createTupleQuery}
 * method or by passing a {@link Tuple} class argument to the {@code createQuery}
 * method), a {@link Tuple} object corresponding to the arguments of the
 * {@code multiselect} method, in the specified order, will be instantiated and
 * returned for each row that results from the query execution.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<X>}
 * for some user-defined class X (i.e., a criteria query object created by
 * passing a X class argument to the {@code createQuery} method), the arguments
 * to the {@code multiselect} method will be passed to the X constructor and an
 * instance of type X will be returned for each row.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<X[]>}
 * for some class X, an instance of type {@code X[]} will be returned for each row.
 * The elements of the array will correspond to the arguments of the
 * {@code multiselect} method, in the specified order.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<Object>}
 * or if the criteria query was created without specifying a type, and only a single
 * argument is passed to the {@code multiselect} method, an instance of type
 * {@code Object} will be returned for each row.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<Object>}
 * or if the criteria query was created without specifying a type, and more than one
 * argument is passed to the {@code multiselect} method, an instance of type
 * {@code Object[]} will be instantiated and returned for each row. The elements of
 * the array will correspond to the arguments to the {@code multiselect} method, in
 * the specified order.
 * </ul>
 *
 * @param selections selection items corresponding to the
 * results to be returned by the query
 * @return the modified query
 * @throws IllegalArgumentException if a selection item is
 * not valid or if more than one selection item has
 * the same assigned alias
 *
 * @deprecated Since this method is not typesafe, the use of
 * {@link CriteriaBuilder#array} or {@link CriteriaBuilder#tuple}
 * with {@link #select} is strongly preferred.
 */
 @Deprecated(since = "3.2")
 CriteriaQuery<T> multiselect(Selection<?>... selections);

 /**
 * Specify the selection items that are to be returned in the query result.
 * Replaces the previously specified selection(s), if any.
 *
 * <p> The type of the result of the query execution depends on the specification
 * of the type of the criteria query object created as well as the argument to the
 * {@code multiselect} method. An element of the list passed to the {@code multiselect}
 * method must not be a tuple- or array-valued compound selection item.
 *
 * <p> The semantics of this method are as follows:
 * <ul>
 * <li> If the type of the criteria query is {@code CriteriaQuery<Tuple>}
 * (i.e., a criteria query object created by either the {@code createTupleQuery}
 * method or by passing a {@link Tuple} class argument to the {@code createQuery}
 * method), a {@code Tuple} object corresponding to the elements of the list passed
 * to the {@code multiselect} method, in the specified order, will be instantiated
 * and returned for each row that results from the query execution.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<X>}
 * for some user-defined class X (i.e., a criteria query object created by passing
 * a X class argument to the {@code createQuery} method), the elements of the list
 * passed to the {@code multiselect} method will be passed to the X constructor
 * and an instance of type X will be returned for each row.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<X[]>}
 * for some class X, an instance of type {@code X[]} will be returned for
 * each row. The elements of the array will correspond to the elements of
 * the list passed to the {@code multiselect} method, in the specified order.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<Object>}
 * or if the criteria query was created without specifying a type, and the list
 * passed to the {@code multiselect} method contains only a single element, an
 * instance of type {@code Object} will be returned for each row.
 *
 * <li> If the type of the criteria query is {@code CriteriaQuery<Object>}
 * or if the criteria query was created without specifying a type, and the list
 * passed to the {@code multiselect} method contains more than one element, an
 * instance of type {@code Object[]} will be instantiated and returned for each row.
 * The elements of the array will correspond to the elements of the list passed to
 * the {@code multiselect} method, in the specified order.
 * </ul>
 *
 * @param selectionList list of selection items corresponding
 * to the results to be returned by the
 * query
 * @return the modified query
 * @throws IllegalArgumentException if a selection item is
 * not valid or if more than one selection item has
 * the same assigned alias
 *
 * @deprecated Since this method is not typesafe, the use of
 * {@link CriteriaBuilder#array} or {@link CriteriaBuilder#tuple}
 * with {@link #select} is strongly preferred.
 */
 @Deprecated(since = "3.2")
 CriteriaQuery<T> multiselect(List<Selection<?>> selectionList);

 /**
 * Modify the query to restrict the query result according
 * to the specified boolean expression.
 * Replaces the previously added restriction(s), if any.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restriction a simple or compound boolean expression
 * @return the modified query
 */
 CriteriaQuery<T> where(Expression<Boolean> restriction);

 /**
 * Modify the query to restrict the query result according 
 * to the conjunction of the specified restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restrictions zero or more restriction predicates
 * @return the modified query
 */
 CriteriaQuery<T> where(Predicate... restrictions);

 /**
 * Modify the query to restrict the query result according
 * to the conjunction of the specified restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the
 * corresponding {@code AbstractQuery} method.
 * @param restrictions a list of zero or more restriction predicates
 * @return the modified query
 * @since 3.2
 */
 CriteriaQuery<T> where(List<Predicate> restrictions);

 /**
 * Specify the expressions that are used to form groups over
 * the query results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param grouping zero or more grouping expressions
 * @return the modified query
 */
 CriteriaQuery<T> groupBy(Expression<?>... grouping);

 /**
 * Specify the expressions that are used to form groups over
 * the query results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param grouping list of zero or more grouping expressions
 * @return the modified query
 */
 CriteriaQuery<T> groupBy(List<Expression<?>> grouping);

 /**
 * Specify a restriction over the groups of the query.
 * Replaces the previous having restriction(s), if any.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restriction a simple or compound boolean expression
 * @return the modified query
 */
 CriteriaQuery<T> having(Expression<Boolean> restriction);

 /**
 * Specify restrictions over the groups of the query
 * according the conjunction of the specified restriction 
 * predicates.
 * Replaces the previously added having restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restrictions zero or more restriction predicates
 * @return the modified query
 */
 CriteriaQuery<T> having(Predicate... restrictions);

 /**
 * Specify restrictions over the groups of the query
 * according the conjunction of the specified restriction
 * predicates.
 * Replaces the previously added having restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the
 * corresponding {@code AbstractQuery} method.
 * @param restrictions a list of zero or more restriction predicates
 * @return the modified query
 * @since 3.2
 */
 CriteriaQuery<T> having(List<Predicate> restrictions);

 /**
 * Specify the ordering expressions that are used to
 * order the query results.
 * Replaces the previous ordering expressions, if any.
 * If no ordering expressions are specified, the previous
 * ordering, if any, is simply removed, and results will
 * be returned in no particular order.
 * The left-to-right sequence of the ordering expressions
 * determines the precedence, whereby the leftmost has the
 * highest precedence.
 * @param o zero or more ordering expressions
 * @return the modified query
 */
 CriteriaQuery<T> orderBy(Order... o);

 /**
 * Specify the ordering expressions that are used to
 * order the query results.
 * Replaces the previous ordering expressions, if any.
 * If no ordering expressions are specified, the previous
 * ordering, if any, is simply removed, and results will
 * be returned in no particular order.
 * The order of the ordering expressions in the list
 * determines the precedence, whereby the first element in
 * the list has the highest precedence.
 * @param o list of zero or more ordering expressions
 * @return the modified query
 */
 CriteriaQuery<T> orderBy(List<Order> o);

 /**
 * Specify whether duplicate query results are eliminated.
 * A true value will cause duplicates to be eliminated.
 * A false value will cause duplicates to be retained.
 * If distinct has not been specified, duplicate results must
 * be retained.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param distinct boolean value specifying whether duplicate
 * results must be eliminated from the query result or
 * whether they must be retained
 * @return the modified query.
 */
 CriteriaQuery<T> distinct(boolean distinct);
 
 /**
 * Return the ordering expressions in order of precedence.
 * Returns empty list if no ordering expressions have been
 * specified.
 * Modifications to the list do not affect the query.
 * @return the list of ordering expressions
 */
 List<Order> getOrderList();
}

C.4. CriteriaSelect

/**
 * Abstracts over {@linkplain CriteriaQuery top-level queries} and
 * {@linkplain CriteriaBuilder#union unions} and
 * {@linkplain CriteriaBuilder#intersect intersections} of top-level
 * queries.
 *
 * @param <T> the type returned by the query
 *
 * @since 3.2
 */
public interface CriteriaSelect<T> {
}

C.5. CriteriaUpdate

import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.EntityType;

/**
 * The {@code CriteriaUpdate} interface defines functionality for
 * performing bulk update operations using the Criteria API.
 *
 * <p>Criteria API bulk update operations map directly to database
 * update operations, bypassing any optimistic locking checks.
 * Portable applications using bulk update operations must manually
 * update the value of the version column, if desired, and/or manually
 * validate the value of the version column. The persistence context
 * is not automatically synchronized with the result of the bulk update.
 *
 * <p> A {@code CriteriaUpdate} object must have a single root.
 *
 * @param <T> the entity type that is the target of the update
 *
 * @since 2.1
 */
public interface CriteriaUpdate<T> extends CommonAbstractCriteria {

 /**
 * Create and add a query root corresponding to the entity
 * that is the target of the update.
 * A {@code CriteriaUpdate} object has a single root, the
 * entity that is being updated.
 * @param entityClass the entity class
 * @return query root corresponding to the given entity
 */
 Root<T> from(Class<T> entityClass);

 /**
 * Create and add a query root corresponding to the entity
 * that is the target of the update.
 * A {@code CriteriaUpdate} object has a single root, the
 * entity that is being updated.
 * @param entity metamodel entity representing the entity
 * of type X
 * @return query root corresponding to the given entity
 */
 Root<T> from(EntityType<T> entity);

 /**
 * Return the query root.
 * @return the query root
 */
 Root<T> getRoot();

 /**
 * Update the value of the specified attribute.
 * @param attribute attribute to be updated
 * @param value new value
 * @return the modified update query
 */
 <Y, X extends Y> CriteriaUpdate<T> set( SingularAttribute<? super T, Y> attribute, X value);

 /**
 * Update the value of the specified attribute.
 * @param attribute attribute to be updated
 * @param value new value
 * @return the modified update query
 */
 <Y> CriteriaUpdate<T> set( SingularAttribute<? super T, Y> attribute, Expression<? extends Y> value);

 /**
 * Update the value of the specified attribute.
 * @param attribute attribute to be updated
 * @param value new value
 * @return the modified update query
 */
 <Y, X extends Y> CriteriaUpdate<T> set(Path<Y> attribute, X value);

 /**
 * Update the value of the specified attribute.
 * @param attribute attribute to be updated
 * @param value new value
 * @return the modified update query
 */
 <Y> CriteriaUpdate<T> set(Path<Y> attribute, Expression<? extends Y> value);

 /**
 * Update the value of the specified attribute.
 * @param attributeName name of the attribute to be updated
 * @param value new value
 * @return the modified update query
 */
 CriteriaUpdate<T> set(String attributeName, Object value);

 /**
 * Modify the update query to restrict the target of the
 * update according to the specified boolean expression.
 * Replaces the previously added restriction(s), if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified update query
 */ 
 CriteriaUpdate<T> where(Expression<Boolean> restriction);

 /**
 * Modify the update query to restrict the target of the
 * update according to the conjunction of the specified
 * restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions zero or more restriction predicates
 * @return the modified update query
 */
 CriteriaUpdate<T> where(Predicate... restrictions);
}

C.6. AbstractQuery

package jakarta.persistence.criteria;

import java.util.List;
import java.util.Set;
import jakarta.persistence.metamodel.EntityType;

/**
 * The {@code AbstractQuery} interface defines functionality that is common
 * to both top-level queries and subqueries.
 * It is not intended to be used directly in query construction.
 *
 * <p> All queries must have:
 * a set of root entities (which may in turn own joins).
 * <p> All queries may have:
 * a conjunction of restrictions.
 *
 * @param <T> the type of the result
 *
 * @since 2.0
 */
public interface AbstractQuery<T> extends CommonAbstractCriteria {

 /**
 * Create and add a query root corresponding to the given entity,
 * forming a cartesian product with any existing roots.
 * @param entityClass the entity class
 * @return query root corresponding to the given entity
 */
 <X> Root<X> from(Class<X> entityClass);

 /**
 * Create and add a query root corresponding to the given entity,
 * forming a cartesian product with any existing roots.
 * @param entity metamodel entity representing the entity
 * of type X
 * @return query root corresponding to the given entity
 */
 <X> Root<X> from(EntityType<X> entity);

 /**
 * Modify the query to restrict the query results according
 * to the specified boolean expression.
 * Replaces the previously added restriction(s), if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified query
 */ 
 AbstractQuery<T> where(Expression<Boolean> restriction);

 /**
 * Modify the query to restrict the query results according 
 * to the conjunction of the specified restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions zero or more restriction predicates
 * @return the modified query
 */
 AbstractQuery<T> where(Predicate... restrictions);

 /**
 * Modify the query to restrict the query result according
 * to the conjunction of the specified restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions a list of zero or more restriction predicates
 * @return the modified query
 * @since 3.2
 */
 AbstractQuery<T> where(List<Predicate> restrictions);

 /**
 * Specify the expressions that are used to form groups over
 * the query results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * @param grouping zero or more grouping expressions
 * @return the modified query
 */
 AbstractQuery<T> groupBy(Expression<?>... grouping);

 /**
 * Specify the expressions that are used to form groups over
 * the query results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * @param grouping list of zero or more grouping expressions
 * @return the modified query
 */
 AbstractQuery<T> groupBy(List<Expression<?>> grouping);

 /**
 * Specify a restriction over the groups of the query.
 * Replaces the previous having restriction(s), if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified query
 */
 AbstractQuery<T> having(Expression<Boolean> restriction);

 /**
 * Specify restrictions over the groups of the query
 * according the conjunction of the specified restriction 
 * predicates.
 * Replaces the previously having added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions zero or more restriction predicates
 * @return the modified query
 */
 AbstractQuery<T> having(Predicate... restrictions);

 /**
 * Specify restrictions over the groups of the query
 * according the conjunction of the specified restriction
 * predicates.
 * Replaces the previously added having restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * @param restrictions a list of zero or more restriction predicates
 * @return the modified query
 * @since 3.2
 */
 AbstractQuery<T> having(List<Predicate> restrictions);

 /**
 * Specify whether duplicate query results are eliminated.
 * A true value will cause duplicates to be eliminated.
 * A false value will cause duplicates to be retained.
 * If distinct has not been specified, duplicate results must
 * be retained.
 * @param distinct boolean value specifying whether duplicate
 * results must be eliminated from the query result or
 * whether they must be retained
 * @return the modified query
 */
 AbstractQuery<T> distinct(boolean distinct);

 /**
 * Return the query roots. These are the roots that are
 * defined for the {@link CriteriaQuery} or {@link Subquery}
 * itself, including any subquery roots defined as a result of
 * correlation. Returns an empty set if no roots have been
 * defined. Modifications to the set do not affect the query.
 * @return the set of query roots
 */ 
 Set<Root<?>> getRoots();

 /**
 * Return the selection of the query, or null if no selection
 * has been set.
 * @return selection item 
 */
 Selection<T> getSelection();

 /**
 * Return a list of the grouping expressions. Returns empty
 * list if no grouping expressions have been specified.
 * Modifications to the list do not affect the query.
 * @return the list of grouping expressions
 */
 List<Expression<?>> getGroupList();

 /**
 * Return the predicate that corresponds to the restriction(s)
 * over the grouping items, or null if no restrictions have 
 * been specified.
 * @return having clause predicate
 */
 Predicate getGroupRestriction();

 /**
 * Return whether duplicate query results must be eliminated or
 * retained.
 * @return boolean indicating whether duplicate query results 
 * must be eliminated
 */
 boolean isDistinct();

 /**
 * Return the result type of the query or subquery. If
 * a result type was specified as an argument to the
 * {@code createQuery} or {@code subquery} method, that
 * type is returned. If the query was created using the
 * {@code createTupleQuery} method, the result type is
 * {@code Tuple}. Otherwise, the result type is
 * {@code Object}.
 * @return result type
 */
 Class<T> getResultType(); 
}

C.7. CollectionJoin

package jakarta.persistence.criteria;

import java.util.Collection;
import jakarta.persistence.metamodel.CollectionAttribute;

/**
 * The {@code CollectionJoin} interface is the type of the result of
 * joining to a collection over an association or element 
 * collection that has been specified as a {@link java.util.Collection}.
 *
 * @param <Z> the source type of the join
 * @param <E> the element type of the target {@code Collection} 
 *
 * @since 2.0
 */
public interface CollectionJoin<Z, E> 
 extends PluralJoin<Z, Collection<E>, E> {

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified join object
 * @since 2.1
 */
 CollectionJoin<Z, E> on(Expression<Boolean> restriction);

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restrictions zero or more restriction predicates
 * @return the modified join object
 * @since 2.1
 */
 CollectionJoin<Z, E> on(Predicate... restrictions);

 /**
 * Return the metamodel representation for the collection
 * attribute.
 * @return metamodel type representing the {@code Collection} that is
 * the target of the join
 */
 CollectionAttribute<? super Z, E> getModel();
}

C.8. CommonAbstractCriteria

import jakarta.persistence.metamodel.EntityType;

import java.util.Set;

/**
 * The {@code CommonAbstractCriteria} interface defines functionality
 * that is common to both top-level criteria queries and subqueries as 
 * well as to update and delete criteria operations.
 * It is not intended to be used directly in query construction.
 *
 * <p> Note that criteria queries and criteria update and delete operations
 * are typed differently.
 * Criteria queries are typed according to the query result type.
 * Update and delete operations are typed according to the target of the
 * update or delete.
 *
 * @since 2.1
 */
public interface CommonAbstractCriteria {

 /**
 * Create a subquery of the query. 
 * @param type the subquery result type
 * @return subquery 
 */
 <U> Subquery<U> subquery(Class<U> type);

 /**
 * Create a subquery of the query.
 * @param type the subquery result type
 * @return subquery
 */
 <U> Subquery<U> subquery(EntityType<U> type);

 /**
 * Return the predicate that corresponds to the where clause
 * restriction(s), or null if no restrictions have been
 * specified.
 * @return where clause predicate
 */
 Predicate getRestriction();

 /**
 * Return the parameters of the query. Returns empty set if
 * there are no parameters.
 * Modifications to the set do not affect the query.
 * @return the query parameters
 */
 Set<ParameterExpression<?>> getParameters();
}

C.9. CompoundSelection

/**
 * The {@code CompoundSelection} interface defines a compound
 * selection item (a tuple, array, or result of a constructor).
 *
 * @param <X> the type of the selection item
 *
 * @since 2.0
 */
public interface CompoundSelection<X> extends Selection<X> {}

C.10. Expression

package jakarta.persistence.criteria;

import java.util.Collection;

/**
 * Type for query expressions.
 *
 * @param <T> the type of the expression
 *
 * @since 2.0
 */
public interface Expression<T> extends Selection<T> {

 /**
 * Create a predicate to test whether the expression is null.
 * @return predicate testing whether the expression is null
 */
 Predicate isNull();

 /**
 * Create a predicate to test whether the expression is 
 * not null.
 * @return predicate testing whether the expression is not null
 */
 Predicate isNotNull();

 /**
 * Create a predicate to test whether the expression is equal to
 * the argument.
 * @param value expression to be tested against
 * @return predicate testing for equality
 * @since 3.2
 */
 Predicate equalTo(Expression<?> value);

 /**
 * Create a predicate to test whether the expression is equal to
 * the argument.
 * @param value value to be tested against
 * @return predicate testing for equality
 * @since 3.2
 */
 Predicate equalTo(Object value);

 /**
 * Create a predicate to test whether the expression is unequal
 * to the argument.
 * @param value expression to be tested against
 * @return predicate testing for inequality
 * @since 3.2
 */
 Predicate notEqualTo(Expression<?> value);

 /**
 * Create a predicate to test whether the expression is unequal
 * to the argument.
 * @param value value to be tested against
 * @return predicate testing for inequality
 * @since 3.2
 */
 Predicate notEqualTo(Object value);

 /**
 * Create a predicate to test whether the expression is a member
 * of the argument list.
 * @param values values to be tested against
 * @return predicate testing for membership
 */
 Predicate in(Object... values);

 /**
 * Create a predicate to test whether the expression is a member
 * of the argument list.
 * @param values expressions to be tested against
 * @return predicate testing for membership
 */
 Predicate in(Expression<?>... values);

 /**
 * Create a predicate to test whether the expression is a member
 * of the collection.
 * @param values collection of values to be tested against
 * @return predicate testing for membership
 */
 Predicate in(Collection<?> values);

 /**
 * Create a predicate to test whether the expression is a member
 * of the collection.
 * @param values expression corresponding to collection to be
 * tested against
 * @return predicate testing for membership
 */
 Predicate in(Expression<Collection<?>> values);

 /**
 * Perform a typecast upon the expression, returning a new
 * expression object.
 * Unlike {@link #cast(Class)}, this method does not cause
 * type conversion: the runtime type is not changed.
 * <p><em>Warning: may result in a runtime failure.</em>
 * @param type intended type of the expression
 * @return new expression of the given type
 * @see #cast(Class)
 */
 <X> Expression<X> as(Class<X> type);

 /**
 * Cast this expression to the specified type, returning a
 * new expression object.
 * Unlike {@link #as(Class)}, this method <em>does</em>
 * result in a runtime type conversion.
 * <p><em>Providers are required to support casting
 * scalar expressions to {@link String}, and
 * {@code String} expressions to {@link Integer},
 * {@link Long}, {@link Float}, and {@link Double}.
 * Support for typecasts between other basic types is
 * not required.</em>
 * @param type a basic type
 * @return a scalar expression of the given basic type
 * @since 3.2
 */
 <X> Expression<X> cast(Class<X> type);
}

C.11. Fetch

import jakarta.persistence.metamodel.Attribute;

/**
 * Represents a join-fetched association or attribute.
 *
 * @param <Z> the source type of the fetch
 * @param <X> the target type of the fetch
 *
 * @since 2.0
 */
public interface Fetch<Z, X> extends FetchParent<Z, X> {

 /**
 * Return the metamodel attribute corresponding to the 
 * fetch join.
 * @return metamodel attribute for the join
 */
 Attribute<? super Z, ?> getAttribute();

 /**
 * Return the parent of the fetched item.
 * @return fetch parent
 */
 FetchParent<?, Z> getParent();

 /**
 * Return the join type used in the fetch join.
 * @return join type
 */
 JoinType getJoinType();
}

C.12. FetchParent

import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

/**
 * Represents an element of the from clause which may
 * function as the parent of Fetches.
 *
 * @param <Z> the source type
 * @param <X> the target type
 *
 * @since 2.0
 */
public interface FetchParent<Z, X> {

 /**
 * Return the fetch joins that have been made from this type.
 * Returns empty set if no fetch joins have been made from
 * this type.
 * Modifications to the set do not affect the query.
 * @return fetch joins made from this type
 */
 java.util.Set<Fetch<X, ?>> getFetches();

 /**
 * Create a fetch join to the specified single-valued attribute 
 * using an inner join.
 * @param attribute target of the join
 * @return the resulting fetch join
 */ 
 <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute);

 /**
 * Create a fetch join to the specified single-valued attribute 
 * using the given join type.
 * @param attribute target of the join
 * @param jt join type
 * @return the resulting fetch join
 */ 
 <Y> Fetch<X, Y> fetch(SingularAttribute<? super X, Y> attribute, JoinType jt);

 /**
 * Create a fetch join to the specified collection-valued 
 * attribute using an inner join. 
 * @param attribute target of the join
 * @return the resulting join
 */
 <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute);
 
 /**
 * Create a fetch join to the specified collection-valued 
 * attribute using the given join type.
 * @param attribute target of the join
 * @param jt join type
 * @return the resulting join
 */
 <Y> Fetch<X, Y> fetch(PluralAttribute<? super X, ?, Y> attribute, JoinType jt);

 //String-based:
 
 /**
 * Create a fetch join to the specified attribute using an 
 * inner join.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting fetch join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */ 
 @SuppressWarnings("hiding")
 <X, Y> Fetch<X, Y> fetch(String attributeName);

 /**
 * Create a fetch join to the specified attribute using 
 * the given join type.
 * @param attributeName name of the attribute for the
 * target of the join
 * @param jt join type
 * @return the resulting fetch join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */ 
 @SuppressWarnings("hiding")
 <X, Y> Fetch<X, Y> fetch(String attributeName, JoinType jt);
}

C.13. AbstractQuery

package jakarta.persistence.criteria;

import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.CollectionAttribute;
import jakarta.persistence.metamodel.ListAttribute;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.SetAttribute;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a bound type, usually an entity that appears in
 * the from clause, but may also be an embeddable belonging to
 * an entity in the from clause. 
 * <p> Serves as a factory for {@link Join}s of associations,
 * embeddables, and collections belonging to the type, and for
 * {@link Path}s of attributes belonging to the type.
 *
 * @param <Z> the source type
 * @param <X> the target type
 *
 * @since 2.0
 */
@SuppressWarnings("hiding")
public interface From<Z, X> extends Path<X>, FetchParent<Z, X> {
