<%@ page contentType="text/html; charset=UTF-8" %>
<%-- The JSTL 1.2 URI maps to the same sql: handlers; the dataSource attribute is a JDBC
     parameter string (Tags 3.0 §10.1.1). Requires /sql-setup.jsp first. --%>
<%@ taglib prefix="sql" uri="http://java.sun.com/jsp/jstl/sql" %>
<sql:query var="r" dataSource="jdbc:h2:mem:bodoni;DB_CLOSE_DELAY=-1,org.h2.Driver,sa,">SELECT COUNT(*) AS c FROM book</sql:query>
<html><body>
<p id="legacy">${r.rows[0].c}</p>
</body></html>
