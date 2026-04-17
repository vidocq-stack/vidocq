package fr.vidocq.examples.servlet;

import jakarta.inject.Singleton;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@Singleton
@WebServlet("/count")
public class CounterServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession();
        Integer n = (Integer) session.getAttribute("count");
        n = (n == null ? 0 : n) + 1;
        session.setAttribute("count", n);
        resp.setContentType("text/plain;charset=utf-8");
        resp.getWriter().write("Session " + session.getId() + " — visit #" + n + "\n");
    }
}
