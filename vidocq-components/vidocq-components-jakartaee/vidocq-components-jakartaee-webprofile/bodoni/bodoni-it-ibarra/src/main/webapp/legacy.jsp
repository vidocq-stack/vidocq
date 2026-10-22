<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<c:set var="letters" value="${fn:split('a,b,c', ',')}"/>
<html><body>
<p id="loop"><c:forEach items="${letters}" var="x" varStatus="s">${s.index}:${x}</c:forEach></p>
<p id="length">${fn:length(letters)}</p>
</body></html>
