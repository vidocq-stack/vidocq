<%@ page contentType="text/html; charset=UTF-8" %>
<%-- sql: actions (Tags 3.0 §10) under peano.reflection=deny: Result properties are read through
     bodoni-peano's accessor table, rows through Peano's Map support, the failure through toString().
     Requires /sql-setup.jsp first (rows 1 to 3). Each paragraph follows the action it reports. --%>
<%@ taglib prefix="sql" uri="jakarta.tags.sql" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<html><body>
<sql:setDataSource var="jdbc" url="jdbc:h2:mem:bodoni;DB_CLOSE_DELAY=-1" driver="org.h2.Driver" user="sa" password=""/>
<sql:query var="one" dataSource="${jdbc}">SELECT title FROM book WHERE id = ?<sql:param value="${param.id}"/></sql:query>
<p id="param">${one.rows[0].title}|${one.rows[0].TITLE}</p>
<%-- The DataSource object becomes the page-scope data source setting (§10.6). --%>
<sql:setDataSource dataSource="${jdbc}"/>
<sql:query var="all">SELECT id, title FROM book ORDER BY id</sql:query>
<p id="count">${all.rowCount}</p>
<p id="columns"><c:forEach items="${all.columnNames}" var="n">${n};</c:forEach></p>
<p id="rows"><c:forEach items="${all.rowsByIndex}" var="row">${row[0]}:${row[1]};</c:forEach></p>
<sql:query var="page" startRow="1" maxRows="1">SELECT id, title FROM book ORDER BY id</sql:query>
<p id="paging">${page.rowCount}:${page.rows[0].title}:${page.limitedByMaxRows}</p>
<%-- parseLocale: the request carries no Accept-Language, and without a parsing locale
     fmt:parseDate fails (§9.9). --%>
<fmt:parseDate var="d" value="1788-01-01" pattern="yyyy-MM-dd" parseLocale="en"/>
<sql:query var="byDate">SELECT title FROM book WHERE published = ?<sql:dateParam value="${d}" type="date"/></sql:query>
<p id="date">${byDate.rows[0].title}</p>
<sql:transaction>
<sql:update>INSERT INTO book VALUES (4,'Bodoni',NULL)</sql:update>
<sql:update>INSERT INTO book VALUES (5,'Ibarra',NULL)</sql:update>
</sql:transaction>
<%-- The duplicated key fails the second statement: the whole transaction, row 6 included, rolls back (§10.5). --%>
<c:catch var="failure"><sql:transaction>
<sql:update>INSERT INTO book VALUES (6,'Lost',NULL)</sql:update>
<sql:update>INSERT INTO book VALUES (6,'Duplicate',NULL)</sql:update>
</sql:transaction></c:catch>
<%-- Standard syntax: '>' is written as is; an entity would reach the database. --%>
<sql:update var="n">UPDATE book SET title = UPPER(title) WHERE id > ?<sql:param value="3"/></sql:update>
<p id="updated">${n}</p>
<sql:query var="after">SELECT COUNT(*) AS c FROM book</sql:query>
<p id="after">${after.rows[0].c}</p>
<p id="failure">${failure}</p>
</body></html>
