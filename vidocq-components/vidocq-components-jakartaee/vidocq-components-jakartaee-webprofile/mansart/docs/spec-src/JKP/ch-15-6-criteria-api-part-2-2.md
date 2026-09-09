# 6. Criteria API (part 2/2)

CriteriaDelete<Customer> q = cb.createCriteriaDelete(Customer.class);
Root<Customer> c = q.from(Customer.class);

q.where(
 cb.equal(c.get(Customer_.status), "inactive"),
 cb.isEmpty(c.get(Customer_.orders)));

The following Jakarta Persistence query language
delete statement is equivalent.

DELETE
FROM Customer c
WHERE c.status = 'inactive'
 AND c.orders IS EMPTY

Like bulk update and delete operations made
through the Jakarta Persistence query language, criteria API bulk update
and delete operations map directly to database operations, bypassing any
optimistic locking checks. Portable applications using bulk update
operations must manually update the value of the version column, if
desired, and/or manually validate the value of the version column.

The persistence context is not synchronized
with the result of the bulk update or delete. See Section 4.11.

6.4. Constructing Strongly-typed Queries using the jakarta.persistence.metamodel Interfaces

Strongly-typed queries can also be
constructed, either statically or dynamically, in the absence of
generated metamodel classes. The jakarta.persistence.metamodel
interfaces are used to access the metamodel objects that correspond to
the managed classes.

The following examples illustrate this
approach. These are equivalent to the example queries shown in Section 6.3.5.

The Metamodel interface is obtained from
the EntityManager or EntityManagerFactory for the persistence unit, and
then used to obtain the corresponding metamodel objects for the managed
types referenced by the queries.

Example 1:

EntityManager em = ...;

Metamodel mm = em.getMetamodel();
EntityType<Employee> emp_ =mm.entity(Employee.class);
EmbeddableType<ContactInfo> cinfo_ = mm.embeddable(ContactInfo.class);
EntityType<Phone> phone_ = mm.entity(Phone.class);
EmbeddableType<Address> addr_ = mm.embeddable(Address.class);

CriteriaQuery<Vendor> q = cb.createQuery(Vendor.class);
Root<Employee> emp = q.from(Employee.class);
Join<Employee, ContactInfo> cinfo =
 emp.join(emp_.getSingularAttribute("contactInfo", ContactInfo.class));
Join<ContactInfo, Phone> p =
 cinfo.join(cinfo_.getSingularAttribute("phones", Phone.class));
q.where(
 cb.equal(emp.get(emp_.getSingularAttribute("contactInfo", ContactInfo.class))
 .get(cinfo_.getSingularAttribute("address", Address.class))
 .get(addr_.getSingularAttribute("zipcode", String.class)), "95054"))
 .select(p.get(phone_.getSingularAttribute("vendor",Vendor.class)));

Example 2:

EntityManager em = ...;
Metamodel mm = em.getMetamodel();

EntityType<Item> item_ = mm.entity(Item.class);
CriteriaQuery<Tuple> q = cb.createTupleQuery();
Root<Item> item = q.from(Item.class);
MapJoin<Item, String, Object> photo =
 item.join(item_.getMap("photos", String.class, Object.class));
q.multiselect(
 item.get(item_.getSingularAttribute("name", String.class)), photo)
 .where(cb.like(photo.key(), "%egret%"));

6.5. Use of the Criteria API with Strings to Reference Attributes

The Criteria API provides the option of
specifying the attribute references used in joins and navigation by
attribute names used as arguments to the various join, fetch, and
get methods.

The resulting queries have the same semantics
as described in Section 6.3, but do not provide the same level of type safety.

The examples in this section illustrate this
approach. These examples are derived from among those of sections
Section 6.3.3 and Section 6.3.5.

Example 1:

CriteriaBuilder cb = ...
CriteriaQuery<String> q = cb.createQuery(String.class);
Root<Customer> cust = q.from(Customer.class);
Join<Order, Item> item = cust.join("orders").join("lineItems");
q.select(cust.<String>get("name"))
 .where(cb.equal(item.get("product").get("productType"), "printer"));

This query is equivalent to the following
Jakarta Persistence query language query:

SELECT c.name
FROM Customer c JOIN c.orders o JOIN o.lineItems i
WHERE i.product.productType = 'printer'

It is not required that type parameters be
used. However, their omission may result in compiler warnings, as with
the below version of the same query:

CriteriaBuilder cb = ...
CriteriaQuery q = cb.createQuery();
Root cust = q.from(Customer.class);
Join item = cust.join("orders").join("lineItems");
q.select(cust.get("name")).where(
 cb.equal(item.get("product").get("productType"),"printer"));

Example 2:

The following query uses an outer join:

CriteriaQuery<Customer> q = cb.createQuery(Customer.class);
Root<Customer> cust = q.from(Customer.class);
Join<Customer,Order> order = cust.join("orders", JoinType.LEFT);
q.where(cb.equal(cust.get("status"), 1))
 .select(cust);

This query is equivalent to the following
Jakarta Persistence query language query:

SELECT c FROM Customer c LEFT JOIN c.orders o
WHERE c.status = 1

Example 3:

In the following example, ContactInfo is an
embeddable class consisting of an address and set of phones. Phone is
an entity.

CriteriaQuery<Vendor> q = cb.createQuery(Vendor.class);
Root<Employee> emp = q.from(Employee.class);
Join<ContactInfo, Phone> phone = emp.join("contactInfo").join("phones");
q.where(cb.equal(emp.get("contactInfo")
 .get("address")
 .get("zipcode"), "95054"));
q.select(phone.<Vendor>get("vendor"));

The following Jakarta Persistence query language
query is equivalent:

SELECT p.vendor
FROM Employee e JOIN e.contactInfo.phones p
WHERE e.contactInfo.address.zipcode = '95054'

Example 4:

In this example, the photos attribute
corresponds to a map from photo label to filename. The map key is a
string, the value an object.

CriteriaQuery<Object> q = cb.createQuery();
Root<Item> item = q.from(Item.class);
MapJoin<Item, String, Object> photo = item.joinMap("photos");
q.multiselect(item.get("name"), photo)
 .where(cb.like(photo.key(), "%egret%"));

This query is equivalent to the following
Jakarta Persistence query language query:

SELECT i.name, p
FROM Item i JOIN i.photos p
WHERE KEY(p) LIKE '%egret%'

6.6. Query Modification

A CriteriaQuery, CriteriaUpdate, or
CriteriaDelete object may be modified, either before or after Query
or TypedQuery objects have been created and executed from it. For
example, such modification may entail replacement of the where
predicate or the select list. Modifications may thus result in the
same query object “base” being reused for several query instances.

For example, the user might create and
execute a query from the following CriteriaQuery object:

CriteriaQuery<Customer> q = cb.createQuery(Customer.class);
Root<Customer> c = q.from(Customer.class);

Predicate pred = cb.equal(c.get(Customer_.address).get(Address_.city),"Chicago");

q.select(c);
q.where(pred);

The CriteriaQuery object might then be
modified to reflect a different predicate condition, for example:

Predicate pred2 = cb.gt(c.get(Customer_.balanceOwed), 1000);
q.where(pred2);

Note, however, that query elements—-in this
example, predicate conditions—are dependent on the CriteriaQuery,
CriteriaUpdate, or CriteriaDelete instance, and are thus not
portably reusable with different instances.

6.7. Query Execution

A criteria query is executed by passing the
CriteriaQuery, CriteriaUpdate, or CriteriaDelete object to the
createQuery method of the EntityManager interface to create an
executable TypedQuery object (or, in the case of CriteriaUpdate and
CriteriaDelete, a Query object), which can then be passed to one of
the query execution methods of the TypedQuery or Query interface.

A CriteriaQuery, CriteriaUpdate, or
CriteriaDelete object may be further modified after an executable
query object has been created from it. The modification of the
CriteriaQuery, CriteriaUpdate, or CriteriaDelete object does not
have any impact on the already created executable query object. If the
modified CriteriaQuery, CriteriaUpdate, or CriteriaDelete object
is passed to the createQuery method, the persistence provider must
insure that a new executable query object is created and returned that
reflects the semantics of the changed query definition.

CriteriaQuery, CriteriaUpdate, and
CriteriaDelete objects must be serializable. A persistence vendor is
required to support the subsequent deserialization of such an object
into a separate JVM instance of that vendor’s runtime, where both
runtime instances have access to any required vendor implementation
classes. CriteriaQuery, CriteriaUpdate, and CriteriaDelete
objects are not required to be interoperable across vendors.
