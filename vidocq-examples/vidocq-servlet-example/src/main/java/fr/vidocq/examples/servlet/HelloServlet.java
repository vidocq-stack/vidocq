package fr.vidocq.examples.servlet;

import jakarta.inject.Singleton;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@Singleton
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=utf-8");
        String name = req.getParameter("name");
        resp.getWriter().write("Hello, " + (name == null ? "world" : name) + "!\n");
    }
}
