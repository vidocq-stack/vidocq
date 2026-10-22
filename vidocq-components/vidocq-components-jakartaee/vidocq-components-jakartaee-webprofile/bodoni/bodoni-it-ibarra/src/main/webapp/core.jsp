<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="letters" value="${fn:split('a,b,c', ',')}"/>
<html><body>
<p id="loop"><c:forEach items="${letters}" var="x" varStatus="s">${s.index}:${x}</c:forEach></p>
<p id="if"><c:if test="${fn:length(letters) == 3}">three</c:if><c:if test="${fn:length(letters) != 3}">not-three</c:if></p>
<p id="choose"><c:choose><c:when test="${letters[0] == 'z'}">zed</c:when><c:when test="${letters[1] == 'b'}">bee</c:when><c:otherwise>other</c:otherwise></c:choose></p>
<p id="out"><c:out value="<b>"/></p>
<p id="url"><c:url value="/x"><c:param name="q" value="a b"/></c:url></p>
<c:catch var="failure"><c:set target="${missing}" property="p" value="v"/></c:catch>
<p id="catch">${failure}</p>
</body></html>
