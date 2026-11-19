<%@ page contentType="text/plain" %><%@ taglib prefix="sql" uri="jakarta.tags.sql" %><sql:setDataSource var="ds" url="jdbc:h2:mem:bodoniItUrl;DB_CLOSE_DELAY=-1" user="sa" password=""/>
<sql:transaction dataSource="${ds}">
<sql:update>create table if not exists t(id int)</sql:update>
<sql:update>delete from t</sql:update>
<sql:update>insert into t values (1)</sql:update>
<sql:update>insert into t values (2)</sql:update>
<sql:query var="r">select id from t</sql:query>
</sql:transaction>
ROWS=${r.rowCount}
