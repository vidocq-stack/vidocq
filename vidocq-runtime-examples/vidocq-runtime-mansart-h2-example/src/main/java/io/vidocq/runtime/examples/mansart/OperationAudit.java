package io.vidocq.runtime.examples.mansart;

import jakarta.transaction.TransactionScoped;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-transaction operation audit bean. Demonstrates the {@code @TransactionScoped} CDI scope
 * provided by {@code mansart-transactions-cdi}: an instance is created on first access inside a
 * transaction, accumulates audit entries while the transaction is active, and is destroyed when
 * the transaction completes (commit or rollback).
 *
 * <p>Per Jakarta Transactions 2.0 §3.7, beans annotated with {@link TransactionScoped} must be
 * {@link Serializable} so that containers can passivate them between calls.
 */
@TransactionScoped
public class OperationAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<String> entries = new ArrayList<>();

    public void record(String op) {
        entries.add(op);
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }
}
