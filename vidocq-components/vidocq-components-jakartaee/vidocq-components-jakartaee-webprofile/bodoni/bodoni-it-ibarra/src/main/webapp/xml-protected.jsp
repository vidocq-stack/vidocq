<%@ page contentType="text/html; charset=UTF-8" %>
<%-- /WEB-INF/ under a relative system id (Bodoni PLAN.md, protected directories): a public document
     cannot pull a resource of /WEB-INF/ through an entity, a stylesheet whose system id is under
     /WEB-INF/ imports its sibling there. --%>
<%@ taglib prefix="x" uri="jakarta.tags.xml" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:catch var="webInf"><x:parse var="leaked" systemId="/xml/catalog.xml"><!DOCTYPE catalog [<!ENTITY secret SYSTEM "/WEB-INF/secret.xml">]><catalog>&secret;</catalog></x:parse></c:catch>
<x:parse var="doc"><books><title>Manuale</title></books></x:parse>
<c:set var="xslt"><xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"><xsl:import href="common.xsl"/><xsl:output method="text"/><xsl:template match="/"><xsl:apply-templates select="//title"/></xsl:template></xsl:stylesheet></c:set>
<html><body>
<p id="webinf">${webInf}</p>
<p id="transform"><x:transform doc="${doc}" xslt="${xslt}" xsltSystemId="/WEB-INF/xsl/main.xsl"/></p>
</body></html>
