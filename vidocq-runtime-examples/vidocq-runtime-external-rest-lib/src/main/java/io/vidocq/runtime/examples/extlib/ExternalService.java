package io.vidocq.runtime.examples.extlib;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI bean in an external library not pre-processed by Vauban.
 */
@ApplicationScoped
public class ExternalService {

    public String compute() {
        return "computed-by-external-lib";
    }
}
