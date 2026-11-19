<%@ page contentType="text/html; charset=UTF-8" %>
<%-- Map.Entry key/value of a c:forEach over a Map (Tags 3.0 §6.1.2): checked under peano.reflection=deny. --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<html><body>
<p id="entries"><c:forEach items="${param}" var="e">${e.key}=${e.value};</c:forEach></p>
</body></html>
