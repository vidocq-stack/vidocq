package io.vidocq.mansart.data.dialect;

public sealed interface Pagination {

    Pagination NONE = new None();

    record None() implements Pagination {}
    record Offset(long offset, int limit) implements Pagination {}
    record Keyset(int limit, Object[] cursor, OrderBy order) implements Pagination {}
}
