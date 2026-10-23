<%@ page contentType="text/html; charset=UTF-8" %>
<%-- Recreates the book table of the in-memory H2 database every sql test reads (Tags 3.0 §10.4).
     No dataSource attribute: the jakarta.servlet.jsp.jstl.sql.dataSource context parameter of
     web.xml applies, in its JDBC parameter string form (§10.1.1, §2.8). --%>
<%@ taglib prefix="sql" uri="jakarta.tags.sql" %>
<sql:update>DROP TABLE IF EXISTS book</sql:update>
<sql:update>CREATE TABLE book (id INT PRIMARY KEY, title VARCHAR(40), published DATE)</sql:update>
<sql:update>INSERT INTO book VALUES (1,'Manuale','1788-01-01')</sql:update>
<sql:update>INSERT INTO book VALUES (2,'Tipografico',NULL)</sql:update>
<sql:update>INSERT INTO book VALUES (3,'Parma','1818-01-01')</sql:update>
<html><body>
<p id="setup">ok</p>
</body></html>
