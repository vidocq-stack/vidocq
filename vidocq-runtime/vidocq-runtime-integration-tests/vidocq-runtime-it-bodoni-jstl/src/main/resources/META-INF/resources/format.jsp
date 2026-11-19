<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<fmt:setLocale value="en_US"/>
<fmt:formatNumber value="1234.5" type="number"/>|<fmt:formatNumber value="0.25" type="percent"/>
<fmt:parseNumber var="n" value="42"/>${n + 1}
