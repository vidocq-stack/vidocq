package acme.jsp;

import io.vidocq.runtime.core.Vidocq;

/** Starts the runtime: Foy serves the pages, the application has no servlet of its own. */
public final class JspApp {

    private JspApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
