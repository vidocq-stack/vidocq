<%@ page contentType="text/html; charset=UTF-8" %>
<%-- x: core, flow control and transformation actions (Tags 3.0 §11–§13) under peano.reflection=deny:
     only Document, Node, NodeList, String and the varStatus LoopTagStatus reach EL. --%>
<%@ taglib prefix="x" uri="jakarta.tags.xml" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<x:parse var="doc"><books><book id="1"><title>Manuale</title></book><book id="2"><title>Tipografico</title></book></books></x:parse>
<x:set var="titles" select="$doc//title"/>
<%-- common.xsl is imported through the relative xsltSystemId, read with ServletContext.getResourceAsStream. --%>
<c:set var="xslt"><xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"><xsl:import href="common.xsl"/><xsl:output method="text"/><xsl:param name="sep"/><xsl:template match="/"><xsl:for-each select="//title"><xsl:if test="position() &gt; 1"><xsl:value-of select="$sep"/></xsl:if><xsl:apply-templates select="."/></xsl:for-each></xsl:template></xsl:stylesheet></c:set>
<html><body>
<p id="out"><x:out select="$doc/books/book[@id=$param:id]/title"/></p>
<p id="loop"><x:forEach select="$doc//book" varStatus="s">${s.count}:<x:out select="title"/>;</x:forEach></p>
<p id="if"><x:if select="$doc//book[@id=$param:id]">found</x:if><x:if select="$doc//book[@id='9']">nine</x:if></p>
<p id="when"><x:choose><x:when select="$doc//book[@id='9']">nine</x:when><x:when select="$doc//book[@id=$param:id]/title='Tipografico'">second</x:when><x:otherwise>other</x:otherwise></x:choose></p>
<p id="otherwise"><x:choose><x:when select="$doc//book[@id='9']">nine</x:when><x:otherwise>none</x:otherwise></x:choose></p>
<p id="titles"><x:out select="$titles"/></p>
<p id="transform"><x:transform doc="${doc}" xslt="${xslt}" xsltSystemId="/xml/main.xsl"><x:param name="sep" value="|"/></x:transform></p>
</body></html>
