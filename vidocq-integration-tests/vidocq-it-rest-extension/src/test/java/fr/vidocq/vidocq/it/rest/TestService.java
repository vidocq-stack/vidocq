package fr.vidocq.vidocq.it.rest;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestService {

    public String greet() {
        return "hello-from-cdi";
    }
}
