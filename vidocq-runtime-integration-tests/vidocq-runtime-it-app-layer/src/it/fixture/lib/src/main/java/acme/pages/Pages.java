package acme.pages;

import java.net.URL;

/** Looks a page up through its own class loader, as a framework built for a flat class path does. */
public final class Pages {

    private Pages() {}

    /** The URL of {@code META-INF/acme/<name>} as this library's loader sees it, or {@code null}. */
    public static URL find(String name) {
        return Pages.class.getClassLoader().getResource("META-INF/acme/" + name);
    }

    /** Starts a daemon poller, as a library with a background thread does; it never stops by itself. */
    public static void startPoller() {
        Thread poller = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "acme-pages-poller");
        poller.setDaemon(true);
        poller.start();
    }
}
