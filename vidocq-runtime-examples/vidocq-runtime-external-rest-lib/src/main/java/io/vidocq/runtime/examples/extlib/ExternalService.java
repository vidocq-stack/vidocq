package io.vidocq.runtime.examples.extlib;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bean CDI dans une librairie externe non pre-traitee par Vauban.
 */
@ApplicationScoped
public class ExternalService {

    public String compute() {
        return "computed-by-external-lib";
    }
}
