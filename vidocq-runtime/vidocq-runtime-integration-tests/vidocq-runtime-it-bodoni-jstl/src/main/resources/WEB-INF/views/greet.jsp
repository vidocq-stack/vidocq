<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:out value="${fn:trim(requestScope.who)}"/>
