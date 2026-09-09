# Appendix C: Criteria API Interfaces (part 3/3)

 /**
 * Return the joins that have been made from this bound type.
 * Returns empty set if no joins have been made from this
 * bound type.
 * Modifications to the set do not affect the query.
 * @return joins made from this type
 */
 Set<Join<X, ?>> getJoins();
 
 /**
 * Whether the {@link From} object has been obtained as a result
 * of correlation (use of a {@link Subquery#correlate} method).
 * @return boolean indicating whether the object has been
 * obtained through correlation
 */
 boolean isCorrelated();

 /**
 * Returns the parent {@link From} object from which the correlated
 * {@link From} object has been obtained through correlation (use
 * of {@link Subquery#correlate} method).
 * @return the parent of the correlated {@code From} object
 * @throws IllegalStateException if the {@code From} object has
 * not been obtained through correlation
 */
 From<Z, X> getCorrelationParent();

 /**
 * Create and add an inner join to the given entity.
 * @param entityClass the target entity class
 * @return the resulting join
 * @since 3.2
 */
 <Y> Join<X, Y> join(Class<Y> entityClass);

 /**
 * Create and add a join to the given entity.
 * @param entityClass the target entity class
 * @param joinType join type
 * @return the resulting join
 * @since 3.2
 */
 <Y> Join<X, Y> join(Class<Y> entityClass, JoinType joinType);

 /**
 * Create and add an inner join to the given entity.
 * @param entity metamodel entity representing the join target
 * @return the resulting join
 * @since 3.2
 */
 <Y> Join<X, Y> join(EntityType<Y> entity);

 /**
 * Create and add a join to the given entity.
 * @param entity metamodel entity representing the join target
 * @param joinType join type
 * @return the resulting join
 * @since 3.2
 */
 <Y> Join<X, Y> join(EntityType<Y> entity, JoinType joinType);

 /**
 * Create an inner join to the specified single-valued 
 * attribute.
 * @param attribute target of the join
 * @return the resulting join
 */
 <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute);

 /**
 * Create a join to the specified single-valued attribute 
 * using the given join type.
 * @param attribute target of the join
 * @param jt join type 
 * @return the resulting join
 */
 <Y> Join<X, Y> join(SingularAttribute<? super X, Y> attribute, JoinType jt);

 /**
 * Create an inner join to the specified {@link Collection}-valued
 * attribute.
 * @param collection target of the join
 * @return the resulting join
 */
 <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection);

 /**
 * Create an inner join to the specified {@link Set}-valued
 * attribute.
 * @param set target of the join
 * @return the resulting join
 */
 <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set);

 /**
 * Create an inner join to the specified
 * {@link List}-valued attribute.
 * @param list target of the join
 * @return the resulting join
 */
 <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list);

 /**
 * Create an inner join to the specified {@link Map}-valued
 * attribute.
 * @param map target of the join
 * @return the resulting join
 */
 <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map);

 /**
 * Create a join to the specified {@link Collection}-valued
 * attribute using the given join type.
 * @param collection target of the join
 * @param jt join type 
 * @return the resulting join
 */
 <Y> CollectionJoin<X, Y> join(CollectionAttribute<? super X, Y> collection, JoinType jt);

 /**
 * Create a join to the specified {@link Set}-valued attribute
 * using the given join type.
 * @param set target of the join
 * @param jt join type 
 * @return the resulting join
 */
 <Y> SetJoin<X, Y> join(SetAttribute<? super X, Y> set, JoinType jt);

 /**
 * Create a join to the specified {@link List}-valued attribute
 * using the given join type.
 * @param list target of the join
 * @param jt join type 
 * @return the resulting join
 */
 <Y> ListJoin<X, Y> join(ListAttribute<? super X, Y> list, JoinType jt);

 /**
 * Create a join to the specified {@link Map}-valued attribute
 * using the given join type.
 * @param map target of the join
 * @param jt join type 
 * @return the resulting join
 */
 <K, V> MapJoin<X, K, V> join(MapAttribute<? super X, K, V> map, JoinType jt);

 //String-based:

 /**
 * Create an inner join to the specified attribute.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> Join<X, Y> join(String attributeName); 

 /**
 * Create an inner join to the specified {@link Collection}-valued
 * attribute.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> CollectionJoin<X, Y> joinCollection(String attributeName); 

 /**
 * Create an inner join to the specified {@link Set}-valued
 * attribute.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> SetJoin<X, Y> joinSet(String attributeName); 

 /**
 * Create an inner join to the specified {@link List}-valued
 * attribute.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> ListJoin<X, Y> joinList(String attributeName); 
 
 /**
 * Create an inner join to the specified {@link Map}-valued
 * attribute.
 * @param attributeName name of the attribute for the
 * target of the join
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, K, V> MapJoin<X, K, V> joinMap(String attributeName); 

 /**
 * Create a join to the specified attribute using the given
 * join type.
 * @param attributeName name of the attribute for the
 * target of the join
 * @param jt join type
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> Join<X, Y> join(String attributeName, JoinType jt); 
 
 /**
 * Create a join to the specified {@link Collection}-valued
 * attribute using the given join type.
 * @param attributeName name of the attribute for the 
 * target of the join
 * @param jt join type
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> CollectionJoin<X, Y> joinCollection(String attributeName, JoinType jt); 

 /**
 * Create a join to the specified {@link Set}-valued attribute
 * using the given join type.
 * @param attributeName name of the attribute for the
 * target of the join
 * @param jt join type
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> SetJoin<X, Y> joinSet(String attributeName, JoinType jt); 

 /**
 * Create a join to the specified {@link List}-valued attribute
 * using the given join type.
 * @param attributeName name of the attribute for the
 * target of the join
 * @param jt join type
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, Y> ListJoin<X, Y> joinList(String attributeName, JoinType jt); 

 /**
 * Create a join to the specified {@link Map}-valued attribute
 * using the given join type.
 * @param attributeName name of the attribute for the
 * target of the join
 * @param jt join type
 * @return the resulting join
 * @throws IllegalArgumentException if attribute of the given
 * name does not exist
 */
 <X, K, V> MapJoin<X, K, V> joinMap(String attributeName, JoinType jt); 
}

C.14. Join

import jakarta.persistence.metamodel.Attribute;

/**
 * A join to an entity, embeddable, or basic type.
 *
 * @param <Z> the source type of the join
 * @param <X> the target type of the join
 *
 * @since 2.0
 */
public interface Join<Z, X> extends From<Z, X> {

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified join object
 * @since 2.1
 */
 Join<Z, X> on(Expression<Boolean> restriction);

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restrictions zero or more restriction predicates
 * @return the modified join object
 * @since 2.1
 */
 Join<Z, X> on(Predicate... restrictions);

 /** 
 * Return the predicate that corresponds to the ON 
 * restriction(s) on the join, or null if no ON condition 
 * has been specified.
 * @return the ON restriction predicate
 * @since 2.1
 */
 Predicate getOn();

 /**
 * Return the metamodel attribute representing the join
 * target, if any, or null if the target of the join is an
 * entity type.
 * @return metamodel attribute or null
 */
 Attribute<? super Z, ?> getAttribute();

 /**
 * Return the parent of the join.
 * @return join parent
 */
 From<?, Z> getParent();

 /**
 * Return the join type.
 * @return join type
 */
 JoinType getJoinType();
}

C.15. JoinType

/**
 * Defines the three varieties of join.
 *
 * <p>Support for {@link #RIGHT} outer joins is not required. Applications
 * which make use of right joins might not be portable between providers or
 * between SQL databases.
 *
 * @since 2.0
 */
public enum JoinType {

 /**
 * Inner join.
 */
 INNER, 

 /**
 * Left outer join.
 */
 LEFT, 

 /**
 * Right outer join.
 */
 RIGHT,
}

C.16. ListJoin

package jakarta.persistence.criteria;

import java.util.List;
import jakarta.persistence.metamodel.ListAttribute;

/**
 * The {@code ListJoin} interface is the type of the result of
 * joining to a collection over an association or element 
 * collection that has been specified as a {@link java.util.List}.
 *
 * @param <Z> the source type of the join
 * @param <E> the element type of the target List
 *
 * @since 2.0
 */
public interface ListJoin<Z, E> 
 extends PluralJoin<Z, List<E>, E> {

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified join object
 * @since 2.1
 */
 ListJoin<Z, E> on(Expression<Boolean> restriction);

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restrictions zero or more restriction predicates
 * @return the modified join object
 * @since 2.1
 */
 ListJoin<Z, E> on(Predicate... restrictions);

 /**
 * Return the metamodel representation for the list attribute.
 * @return metamodel type representing the {@code List} that is
 * the target of the join
 */
 ListAttribute<? super Z, E> getModel();

 /**
 * Create an expression that corresponds to the index of
 * the object in the referenced association or element
 * collection.
 * This method must only be invoked upon an object that
 * represents an association or element collection for
 * which an order column has been defined.
 * @return expression denoting the index
 */
 Expression<Integer> index();
}

C.17. LocalDateField

import java.time.LocalDate;

/**
 * Each instance represents a type of field which can be
 * extracted from a {@link LocalDate}.
 *
 * @param <N> the resulting type of the extracted value
 *
 * @since 3.2
 */
public class LocalDateField<N> implements TemporalField<N, LocalDate> {

 private final String name;

 private LocalDateField(String name) {
 this.name = name;
 }

 @Override
 public String toString() {
 return name;
 }

 /**
 * The calendar year.
 */
 public static final LocalDateField<Integer> YEAR = new LocalDateField<>("year");
 /**
 * The calendar quarter, numbered from 1 to 4.
 */
 public static final LocalDateField<Integer> QUARTER = new LocalDateField<>("quarter");
 /**
 * The calendar month of the year, numbered from 1.
 */
 public static final LocalDateField<Integer> MONTH = new LocalDateField<>("month");
 /**
 * The ISO-8601 week number.
 */
 public static final LocalDateField<Integer> WEEK = new LocalDateField<>("week");
 /**
 * The calendar day of the month, numbered from 1.
 */
 public static final LocalDateField<Integer> DAY = new LocalDateField<>("day");
}

C.18. LocalDateTimeField

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Each instance represents a type of field which can be
 * extracted from a {@link LocalDateTime}.
 *
 * @param <N> the resulting type of the extracted value
 *
 * @since 3.2
 */
public class LocalDateTimeField<N> implements TemporalField<N, LocalDateTime> {

 private final String name;

 private LocalDateTimeField(String name) {
 this.name = name;
 }

 @Override
 public String toString() {
 return name;
 }

 /**
 * The calendar year.
 */
 public static final LocalDateTimeField<Integer> YEAR = new LocalDateTimeField<>("year");
 /**
 * The calendar quarter, numbered from 1 to 4.
 */
 public static final LocalDateTimeField<Integer> QUARTER = new LocalDateTimeField<>("quarter");
 /**
 * The calendar month of the year, numbered from 1.
 */
 public static final LocalDateTimeField<Integer> MONTH = new LocalDateTimeField<>("month");
 /**
 * The ISO-8601 week number.
 */
 public static final LocalDateTimeField<Integer> WEEK = new LocalDateTimeField<>("week");
 /**
 * The calendar day of the month, numbered from 1.
 */
 public static final LocalDateTimeField<Integer> DAY = new LocalDateTimeField<>("day");

 /**
 * The hour of the day in 24-hour time, numbered from 0 to 23.
 */
 public static final LocalDateTimeField<Integer> HOUR = new LocalDateTimeField<>("hour");
 /**
 * The minute of the hour, numbered from 0 to 59.
 */
 public static final LocalDateTimeField<Integer> MINUTE = new LocalDateTimeField<>("minute");
 /**
 * The second of the minute, numbered from 0 to 59, including a fractional
 * part representing fractions of a second
 */
 public static final LocalDateTimeField<Double> SECOND = new LocalDateTimeField<>("second");

 /**
 * The {@linkplain LocalDate date} part of a datetime.
 */
 public static final LocalDateTimeField<LocalDate> DATE = new LocalDateTimeField<>("date");
 /**
 * The {@linkplain LocalTime time} part of a datetime.
 */
 public static final LocalDateTimeField<LocalTime> TIME = new LocalDateTimeField<>("time");
}

C.19. LocalTimeField

import java.time.LocalTime;

/**
 * Each instance represents a type of field which can be
 * extracted from a {@link LocalTime}.
 *
 * @param <N> the resulting type of the extracted value
 *
 * @since 3.2
 */
public class LocalTimeField<N> implements TemporalField<N, LocalTime> {

 private final String name;

 private LocalTimeField(String name) {
 this.name = name;
 }

 @Override
 public String toString() {
 return name;
 }

 /**
 * The hour of the day in 24-hour time, numbered from 0 to 23.
 */
 public static final LocalTimeField<Integer> HOUR = new LocalTimeField<>("hour");
 /**
 * The minute of the hour, numbered from 0 to 59.
 */
 public static final LocalTimeField<Integer> MINUTE = new LocalTimeField<>("minute");
 /**
 * The second of the minute, numbered from 0 to 59, including a fractional
 * part representing fractions of a second
 */
 public static final LocalTimeField<Double> SECOND = new LocalTimeField<>("second");
}

C.20. MapJoin

import java.util.Map;
import jakarta.persistence.metamodel.MapAttribute;

/**
 * The {@code MapJoin} interface is the type of the result of
 * joining to a collection over an association or element 
 * collection that has been specified as a {@link java.util.Map}.
 *
 * @param <Z> the source type of the join
 * @param <K> the type of the target Map key
 * @param <V> the type of the target Map value
 *
 * @since 2.0
 */
public interface MapJoin<Z, K, V> 
 extends PluralJoin<Z, Map<K, V>, V> {

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified join object
 * @since 2.1
 */
 MapJoin<Z, K, V> on(Expression<Boolean> restriction);

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restrictions zero or more restriction predicates
 * @return the modified join object
 * @since 2.1
 */
 MapJoin<Z, K, V> on(Predicate... restrictions);

 /**
 * Return the metamodel representation for the map attribute.
 * @return metamodel type representing the {@code Map} that is
 * the target of the join
 */
 MapAttribute<? super Z, K, V> getModel();
 
 /**
 * Create a path expression that corresponds to the map key.
 * @return path corresponding to map key
 */
 Path<K> key();
 
 /**
 * Create a path expression that corresponds to the map value.
 * This method is for stylistic use only: it just returns this.
 * @return path corresponding to the map value
 */
 Path<V> value(); 
 
 /**
 * Create an expression that corresponds to the map entry.
 * @return expression corresponding to the map entry
 */
 Expression<Map.Entry<K, V>> entry();
}

C.21. Nulls

/**
 * Specifies the precedence of null values within query result sets.
 *
 * @see CriteriaBuilder#asc(Expression, Nulls)
 * @see CriteriaBuilder#desc(Expression, Nulls)
 *
 * @since 3.2
 */
public enum Nulls {
 /**
 * Null precedence not specified.
 */
 NONE,
 /**
 * Null values occur at the beginning of the result set.
 */
 FIRST,
 /**
 * Null values occur at the end of the result set.
 */
 LAST
}

C.22. Order

/**
 * An object that defines an ordering over the query results.
 *
 * @since 2.0
 */
public interface Order {

 /**
 * Switch the ordering.
 * @return a new {@code Order} instance with the reversed ordering
 */
 Order reverse();

 /**
 * Whether ascending ordering is in effect.
 * @return boolean indicating whether ordering is ascending
 */
 boolean isAscending();

 /**
 * Return the precedence of null values.
 * @return the {@linkplain Nulls precedence of null values}
 * @since 3.2
 */
 Nulls getNullPrecedence();

 /**
 * Return the expression that is used for ordering.
 * @return expression used for ordering
 */
 Expression<?> getExpression();
}

C.23. ParameterExpression

import jakarta.persistence.Parameter;

/**
 * Type of criteria query parameter expressions.
 *
 * @param <T> the type of the parameter expression
 *
 * @since 2.0
 */
public interface ParameterExpression<T> extends Parameter<T>, Expression<T> {}

C.24. Path

import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Bindable;
import jakarta.persistence.metamodel.MapAttribute;

/**
 * Represents a simple or compound attribute path from a 
 * bound type or collection, and is a "primitive" expression.
 *
 * @param <X> the type referenced by the path
 *
 * @since 2.0
 */
public interface Path<X> extends Expression<X> {

 /** 
 * Return the bindable object that corresponds to the path
 * expression.
 * @return bindable object corresponding to the path
 */
 Bindable<X> getModel(); 
 
 /**
 * Return the parent "node" in the path or null if no parent.
 * @return parent
 */
 Path<?> getParentPath();
 
 /**
 * Create a path corresponding to the referenced
 * single-valued attribute.
 * @param attribute single-valued attribute
 * @return path corresponding to the referenced attribute
 */
 <Y> Path<Y> get(SingularAttribute<? super X, Y> attribute);

 /**
 * Create a path corresponding to the referenced 
 * collection-valued attribute.
 * @param collection collection-valued attribute
 * @return expression corresponding to the referenced attribute
 */
 <E, C extends java.util.Collection<E>> Expression<C> get(PluralAttribute<? super X, C, E> collection);

 /**
 * Create a path corresponding to the referenced 
 * map-valued attribute.
 * @param map map-valued attribute
 * @return expression corresponding to the referenced attribute
 */
 <K, V, M extends java.util.Map<K, V>> Expression<M> get(MapAttribute<? super X, K, V> map);

 /**
 * Create an expression corresponding to the type of the path.
 * @return expression corresponding to the type of the path
 */
 Expression<Class<? extends X>> type();

 //String-based:
 
 /**
 * Create a path corresponding to the referenced attribute.
 * 
 * <p> Note: Applications using the string-based API may need to 
 * specify the type resulting from the {@link #get} operation in
 * order to avoid the use of {@code Path} variables.
 *
 * <p>For example:
 * {@snippet :
 * CriteriaQuery<Person> q = cb.createQuery(Person.class);
 * Root<Person> p = q.from(Person.class);
 * q.select(p)
 * .where(cb.isMember("joe",
 * p.<Set<String>>get("nicknames")));
 * }
 * <p>rather than:
 * {@snippet :
 * CriteriaQuery<Person> q = cb.createQuery(Person.class);
 * Root<Person> p = q.from(Person.class);
 * Path<Set<String>> nicknames = p.get("nicknames");
 * q.select(p)
 * .where(cb.isMember("joe", nicknames));
 * }
 *
 * @param attributeName name of the attribute
 * @return path corresponding to the referenced attribute
 * @throws IllegalStateException if invoked on a path that
 * corresponds to a basic type
 * @throws IllegalArgumentException if attribute of the given
 * name does not otherwise exist
 */
 <Y> Path<Y> get(String attributeName);
}

C.25. PluralJoin

import jakarta.persistence.metamodel.PluralAttribute;

/**
 * The {@code PluralJoin} interface defines functionality
 * that is common to joins to all collection types. It is
 * not intended to be used directly in query construction.
 *
 * @param <Z> the source type
 * @param <C> the collection type
 * @param <E> the element type of the collection 
 *
 * @since 2.0
 */
public interface PluralJoin<Z, C, E> extends Join<Z, E> {

 /**
 * Return the metamodel representation for the collection-valued 
 * attribute corresponding to the join.
 * @return metamodel collection-valued attribute corresponding
 * to the target of the join
 */
 PluralAttribute<? super Z, C, E> getModel();
}

C.26. Predicate

import java.util.List;

/**
 * The type of a simple or compound predicate: a conjunction or
 * disjunction of restrictions.
 * A simple predicate is considered to be a conjunction with a
 * single conjunct.
 *
 * @since 2.0
 */
public interface Predicate extends Expression<Boolean> {

 enum BooleanOperator {
 AND, OR
 }
 
 /**
 * Return the boolean operator for the predicate.
 * If the predicate is simple, this is {@code AND}.
 * @return boolean operator for the predicate
 */
 BooleanOperator getOperator();
 
 /**
 * Whether the predicate has been created from another
 * predicate by applying {@link Predicate#not()}
 * or by calling {@link CriteriaBuilder#not}.
 * @return boolean indicating if the predicate is 
 * a negated predicate
 */
 boolean isNegated();

 /**
 * Return the top-level conjuncts or disjuncts of the
 * predicate. Returns empty list if there are no top-level
 * conjuncts or disjuncts of the predicate.
 * Modifications to the list do not affect the query.
 * @return list of boolean expressions forming the predicate
 */
 List<Expression<Boolean>> getExpressions();

 /**
 * Create a negation of the predicate.
 * @return negated predicate 
 */
 Predicate not();

}

C.27. Root

import jakarta.persistence.metamodel.EntityType;

/**
 * A root type in the from clause.
 * Query roots always reference entities.
 *
 * @param <X> the entity type referenced by the root
 *
 * @since 2.0
 */
public interface Root<X> extends From<X, X> {

 /**
 * Return the metamodel entity corresponding to the root.
 * @return metamodel entity corresponding to the root
 */
 EntityType<X> getModel();
}

C.28. Selection

import jakarta.persistence.TupleElement;
import java.util.List;

/**
 * The {@code Selection} interface defines an item that is to be
 * returned in a query result.
 *
 * @param <X> the type of the selection item
 *
 * @since 2.0
 */
public interface Selection<X> extends TupleElement<X> {

 /**
 * Assigns an alias to the selection item.
 * Once assigned, an alias cannot be changed or reassigned.
 * Returns the same selection item.
 * @param name alias
 * @return selection item 
 */
 Selection<X> alias(String name);

 /**
 * Whether the selection item is a compound selection.
 * @return boolean indicating whether the selection is a compound
 * selection
 */
 boolean isCompoundSelection();

 /**
 * Return the selection items composing a compound selection.
 * Modifications to the list do not affect the query.
 * @return list of selection items
 * @throws IllegalStateException if selection is not a 
 * compound selection
 */
 List<Selection<?>> getCompoundSelectionItems();
}

C.29. SetJoin

import java.util.Set;
import jakarta.persistence.metamodel.SetAttribute;

/**
 * The {@code SetJoin} interface is the type of the result of
 * joining to a collection over an association or element 
 * collection that has been specified as a {@link java.util.Set}.
 *
 * @param <Z> the source type of the join
 * @param <E> the element type of the target {@code Set}
 *
 * @since 2.0
 */
public interface SetJoin<Z, E> extends PluralJoin<Z, Set<E>, E> {

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restriction a simple or compound boolean expression
 * @return the modified join object
 * @since 2.1
 */
 SetJoin<Z, E> on(Expression<Boolean> restriction);

 /**
 * Modify the join to restrict the result according to the
 * specified ON condition and return the join object. 
 * Replaces the previous ON condition, if any.
 * @param restrictions zero or more restriction predicates
 * @return the modified join object
 * @since 2.1
 */
 SetJoin<Z, E> on(Predicate... restrictions);

 /**
 * Return the metamodel representation for the set attribute.
 * @return metamodel type representing the {@code Set} that is
 * the target of the join
 */
 SetAttribute<? super Z, E> getModel();
}

C.30. Subquery

package jakarta.persistence.criteria;

import java.util.List;
import java.util.Set;

/**
 * The {@code Subquery} interface defines functionality that is
 * specific to subqueries.
 *
 * <p>A subquery has an expression as its selection item.
 *
 * @param <T> the type of the selection item.
 *
 * @since 2.0
 */
public interface Subquery<T> extends AbstractQuery<T>, Expression<T> {
 
 /**
 * Specify the item that is to be returned as the subquery 
 * result.
 * Replaces the previously specified selection, if any.
 * @param expression expression specifying the item that
 * is to be returned as the subquery result
 * @return the modified subquery
 */
 Subquery<T> select(Expression<T> expression);
 
 /**
 * Modify the subquery to restrict the result according
 * to the specified boolean expression.
 * Replaces the previously added restriction(s), if any.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restriction a simple or compound boolean expression
 * @return the modified subquery
 */
 Subquery<T> where(Expression<Boolean> restriction);

 /**
 * Modify the subquery to restrict the result according 
 * to the conjunction of the specified restriction predicates.
 * Replaces the previously added restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restrictions zero or more restriction predicates
 * @return the modified subquery
 */
 Subquery<T> where(Predicate... restrictions);

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
 Subquery<T> where(List<Predicate> restrictions);

 /**
 * Specify the expressions that are used to form groups over
 * the subquery results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param grouping zero or more grouping expressions
 * @return the modified subquery
 */
 Subquery<T> groupBy(Expression<?>... grouping);

 /**
 * Specify the expressions that are used to form groups over
 * the subquery results.
 * Replaces the previous specified grouping expressions, if any.
 * If no grouping expressions are specified, any previously 
 * added grouping expressions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param grouping list of zero or more grouping expressions
 * @return the modified subquery
 */
 Subquery<T> groupBy(List<Expression<?>> grouping);

 /**
 * Specify a restriction over the groups of the subquery.
 * Replaces the previous having restriction(s), if any.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restriction a simple or compound boolean expression
 * @return the modified subquery
 */
 Subquery<T> having(Expression<Boolean> restriction);

 /**
 * Specify restrictions over the groups of the subquery
 * according the conjunction of the specified restriction 
 * predicates.
 * Replaces the previously added having restriction(s), if any.
 * If no restrictions are specified, any previously added
 * restrictions are simply removed.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param restrictions zero or more restriction predicates
 * @return the modified subquery
 */
 Subquery<T> having(Predicate... restrictions);

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
 Subquery<T> having(List<Predicate> restrictions);

 /**
 * Specify whether duplicate query results are eliminated.
 * A true value will cause duplicates to be eliminated.
 * A false value will cause duplicates to be retained.
 * If distinct has not been specified, duplicate results must
 * be retained.
 * This method only overrides the return type of the 
 * corresponding {@code AbstractQuery} method.
 * @param distinct boolean value specifying whether duplicate
 * results must be eliminated from the subquery result or
 * whether they must be retained
 * @return the modified subquery.
 */
 Subquery<T> distinct(boolean distinct);
 
 /**
 * Create a subquery root correlated to a root of the 
 * enclosing query.
 * @param parentRoot a root of the containing query
 * @return subquery root
 */
 <Y> Root<Y> correlate(Root<Y> parentRoot);

 /**
 * Create a subquery join object correlated to a join object
 * of the enclosing query.
 * @param parentJoin join object of the containing query
 * @return subquery join
 */
 <X, Y> Join<X, Y> correlate(Join<X, Y> parentJoin);

 /**
 * Create a subquery collection join object correlated to a 
 * collection join object of the enclosing query.
 * @param parentCollection join object of the containing query
 * @return subquery join
 */
 <X, Y> CollectionJoin<X, Y> correlate(CollectionJoin<X, Y> parentCollection);

 /**
 * Create a subquery set join object correlated to a set join
 * object of the enclosing query.
 * @param parentSet join object of the containing query
 * @return subquery join
 */
 <X, Y> SetJoin<X, Y> correlate(SetJoin<X, Y> parentSet);

 /**
 * Create a subquery list join object correlated to a list join
 * object of the enclosing query.
 * @param parentList join object of the containing query
 * @return subquery join
 */
 <X, Y> ListJoin<X, Y> correlate(ListJoin<X, Y> parentList);

 /**
 * Create a subquery map join object correlated to a map join
 * object of the enclosing query.
 * @param parentMap join object of the containing query
 * @return subquery join
 */
 <X, K, V> MapJoin<X, K, V> correlate(MapJoin<X, K, V> parentMap);

 /**
 * Return the query of which this is a subquery.
 * This must be a CriteriaQuery or a Subquery.
 * @return the enclosing query or subquery
 */
 AbstractQuery<?> getParent();

 /**
 * Return the query of which this is a subquery.
 * This may be a CriteriaQuery, CriteriaUpdate, CriteriaDelete,
 * or a Subquery.
 * @return the enclosing query or subquery
 * @since 2.1
 */
 CommonAbstractCriteria getContainingQuery();
 
 /**
 * Return the selection expression.
 * @return the item to be returned in the subquery result
 */
 Expression<T> getSelection();

 /**
 * Return the correlated joins of the subquery.
 * Returns empty set if the subquery has no correlated
 * joins.
 * Modifications to the set do not affect the query.
 * @return the correlated joins of the subquery
 */
 Set<Join<?, ?>> getCorrelatedJoins();

}

C.31. TemporalField

import java.time.temporal.Temporal;

/**
 * Each instance represents a type of field which can be
 * extracted from a date, time, or datetime.
 *
 * @param <N> the resulting type of the extracted value
 * @param <T> the temporal type (date, time, or datetime)
 *
 * @see LocalDateField
 * @see LocalTimeField
 * @see LocalDateTimeField
 * @see CriteriaBuilder#extract(TemporalField, Expression)
 *
 * @since 3.2
 */
public interface TemporalField<N,T extends Temporal> {}
