package fr.vidocq.vidocq.examples.rest;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

import java.util.Random;

@ApplicationScoped
public class MyProducer {

    @Produces
    @Dependent
    public Generator generator() {
        return () -> "Nb:" + new Random().nextInt(1000);
    }
}
