<%@ page contentType="text/html; charset=UTF-8" %>
<%-- Proof that the host runs with peano.reflection=deny: reading a bean property of a plain
     public class (JspTagException#getMessage) with no registered accessor must fail. --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:catch var="failure"><c:set target="${missing}" property="p" value="v"/></c:catch>
<html><body>
<p id="message">${failure.message}</p>
</body></html>
