<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:forEach items="${param}" var="e">${e.key}=${e.value};</c:forEach>
