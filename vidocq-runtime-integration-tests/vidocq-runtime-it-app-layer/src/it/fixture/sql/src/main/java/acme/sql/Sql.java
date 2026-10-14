package acme.sql;

import java.sql.Types;

/** Touches java.sql, which only this module requires. */
public final class Sql {

    private Sql() {
    }

    public static String probe() {
        return "SQL-OK " + Types.VARCHAR;
    }
}
