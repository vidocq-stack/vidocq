package io.vidocq.runtime.examples.rest;

import jakarta.enterprise.context.Dependent;

@Dependent
public class DependentCounter {
    private int value=0;

    public int getNextValue() {
        return value++;
    }
}
