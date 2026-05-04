package io.vidocq.mansart.data.dialect;

import java.util.List;

public record OrderBy(List<Order> orders) {

    public static final OrderBy NONE = new OrderBy(List.of());

    public OrderBy { orders = List.copyOf(orders); }

    public boolean isEmpty() { return orders.isEmpty(); }

    public record Order(Attribute<?, ?> attr, Direction direction) {
        public enum Direction { ASC, DESC }

        public static Order asc(Attribute<?, ?> a)  { return new Order(a, Direction.ASC); }
        public static Order desc(Attribute<?, ?> a) { return new Order(a, Direction.DESC); }
    }
}
