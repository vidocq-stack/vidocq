<%@ page pageEncoding="UTF-8" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="bistro" uri="https://vidocq.io/examples/bistro" %>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Receipt · Vidocq Bistro</title></head>
<body>
<h1>Receipt</h1>
<ul>
<bistro:each in="receiptLines" var="line">  <li>
<bistro:out value="${line.quantity}" escapeXml="true"/> × <bistro:out value="${line.dish}" escapeXml="true"/> — <bistro:out value="${line.amount}" escapeXml="true"/></li>
</bistro:each></ul>
<p>
Total <bistro:out value="${receipt.total}" escapeXml="true"/></p>
<p><a href="basket">Back to the basket</a></p>
</body></html>
