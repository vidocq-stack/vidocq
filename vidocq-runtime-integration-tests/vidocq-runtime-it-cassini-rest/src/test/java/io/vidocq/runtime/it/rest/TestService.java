package io.vidocq.runtime.it.rest;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestService {

    public String greet() {
        return "hello-from-cdi";
    }
}
