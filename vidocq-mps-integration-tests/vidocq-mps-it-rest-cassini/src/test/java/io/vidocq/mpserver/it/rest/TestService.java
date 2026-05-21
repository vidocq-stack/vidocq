package io.vidocq.mpserver.it.rest;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestService {

    public String greet() {
        return "hello-from-cdi";
    }
}
