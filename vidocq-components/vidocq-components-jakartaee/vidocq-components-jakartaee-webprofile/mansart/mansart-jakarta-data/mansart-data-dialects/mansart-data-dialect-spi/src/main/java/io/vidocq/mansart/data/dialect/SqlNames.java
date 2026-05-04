package io.vidocq.mansart.data.dialect;

/**
 * Mansart SQL naming conventions.
 *
 * <ul>
 *   <li>Table name = pluralized snake_case of the simple class name (Book → books, OrderLine → order_lines).</li>
 *   <li>Column name = snake_case of the attribute name (publishedOn → published_on).</li>
 *   <li>Foreign key column = {@code <attr>_id} for {@code @ManyToOne} / {@code @OneToOne} (author → author_id).</li>
 * </ul>
 *
 * Override per-attribute via {@code @Column(name=…)} and per-entity via {@code @Table(name=…)}.
 */
public final class SqlNames {

    private SqlNames() {}

    public static String tableName(String simpleClassName) {
        return pluralize(toSnakeCase(simpleClassName));
    }

    public static String columnName(String attributeName) {
        return toSnakeCase(attributeName);
    }

    public static String foreignKeyColumn(String attributeName) {
        return toSnakeCase(attributeName) + "_id";
    }

    static String toSnakeCase(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String pluralize(String snake) {
        if (snake == null || snake.isEmpty()) return snake;
        char last = snake.charAt(snake.length() - 1);
        if (snake.endsWith("s") || snake.endsWith("x") || snake.endsWith("z")
                || snake.endsWith("ch") || snake.endsWith("sh")) {
            return snake + "es";
        }
        if (last == 'y' && snake.length() >= 2) {
            char prev = snake.charAt(snake.length() - 2);
            if ("aeiou".indexOf(prev) < 0) {
                return snake.substring(0, snake.length() - 1) + "ies";
            }
        }
        return snake + "s";
    }
}
