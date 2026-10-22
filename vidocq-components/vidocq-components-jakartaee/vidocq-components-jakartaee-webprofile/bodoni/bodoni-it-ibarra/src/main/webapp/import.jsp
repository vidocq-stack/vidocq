<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<html><body>
<p id="fragment"><c:import url="fragment.jsp"><c:param name="who" value="Bodoni"/></c:import></p>
<c:catch var="failure"><c:import url="missing.jsp"/></c:catch>
<p id="missing">${failure}</p>
<c:catch var="redirected"><c:import url="redirect.jsp"/></c:catch>
<p id="status">${redirected}</p>
</body></html>
