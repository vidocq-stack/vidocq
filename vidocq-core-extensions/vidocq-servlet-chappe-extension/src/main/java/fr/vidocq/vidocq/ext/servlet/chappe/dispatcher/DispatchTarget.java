package fr.vidocq.vidocq.ext.servlet.chappe.dispatcher;

import jakarta.servlet.Servlet;

/**
 * Cible résolue d'un dispatch servlet : la servlet et les coordonnées
 * d'URL qu'elle verra ({@code servletPath}, {@code pathInfo}, {@code queryString}).
 */
public record DispatchTarget(Servlet servlet,
                             String servletName,
                             String path,
                             String servletPath,
                             String pathInfo,
                             String queryString) {}
