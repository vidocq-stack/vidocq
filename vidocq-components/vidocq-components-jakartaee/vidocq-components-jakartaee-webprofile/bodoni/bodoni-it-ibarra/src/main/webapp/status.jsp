<%@ page contentType="text/html; charset=UTF-8" %>
<%-- varStatus properties need an accessor for LoopTagStatus under peano.reflection=deny (BUG.md BOD-001). --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="letters" value="${fn:split('a,b,c', ',')}"/>
<html><body>
<p id="status"><c:forEach items="${letters}" var="x" varStatus="s">${s.index}:${s.count}:${x};</c:forEach></p>
<p id="bounds"><c:forEach items="${letters}" var="x" varStatus="s"><c:if test="${s.first}">F</c:if>${x}<c:if test="${s.last}">L</c:if></c:forEach></p>
</body></html>
