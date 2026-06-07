package io.vidocq.mansart.transactions.jpmsit;

/** A unit of work run inside a {@code @Transactional} method (may throw checked exceptions). */
@FunctionalInterface
public interface Block {
    void run() throws Exception;
}
