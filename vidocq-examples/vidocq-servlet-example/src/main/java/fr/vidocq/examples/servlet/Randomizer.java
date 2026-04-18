package fr.vidocq.examples.servlet;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.random.RandomGenerator;

@ApplicationScoped
public class Randomizer {

    public int generate() {
        return RandomGenerator.getDefault().nextInt(1000);
    }
}
