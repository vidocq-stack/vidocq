package fr.vidocq.examples.servlet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@ApplicationScoped
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {

    @Inject
    Randomizer randomizer;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=utf-8");
        String name = req.getParameter("name");
        resp.getWriter().write(randomizer.generate()+" - Hello, " + (name == null ? "world" : name) + "! \n");
    }
}
