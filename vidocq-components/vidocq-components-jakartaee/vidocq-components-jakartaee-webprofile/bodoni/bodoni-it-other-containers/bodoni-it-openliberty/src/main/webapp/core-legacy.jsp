<%@ page contentType="text/plain" %><%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %><%@ taglib prefix="probe" uri="urn:bodoni:it:probe" %><%
    request.setAttribute("items", java.util.List.of("a", "b", "c"));
%><c:forEach items="${items}" var="i"><probe:parent/></c:forEach>
<c:if test="${true}"><probe:parent/></c:if>
ITEMS=<c:forEach items="${items}" var="i" varStatus="s"><c:out value="${i}"/><c:if test="${!s.last}">,</c:if></c:forEach>
