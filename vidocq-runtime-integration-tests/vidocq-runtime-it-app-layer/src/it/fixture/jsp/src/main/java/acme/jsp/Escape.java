package acme.jsp;

/** The functions of the fixture's {@code escape} tag library. */
public final class Escape {

    private Escape() {}

    /** {@code value} with the HTML special characters escaped; empty for {@code null}. */
    public static String escapeXml(String value) {
        if (value == null) return "";
        var out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&#034;");
                case '\'' -> out.append("&#039;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
