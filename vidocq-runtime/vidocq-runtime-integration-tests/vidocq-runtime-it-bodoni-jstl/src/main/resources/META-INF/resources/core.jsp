<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="who" value="${param.who}"/>
<c:if test="${not empty who}">Hello ${fn:toUpperCase(who)}</c:if>
<c:forEach items="${['a','b','c']}" var="x" varStatus="s">[${s.index}:${x}${s.last ? '.' : ','}]</c:forEach>
<c:choose><c:when test="${fn:length(who) gt 3}">long</c:when><c:otherwise>short</c:otherwise></c:choose>
<c:out value="${'<b>'}"/>
