package io.vidocq.heisenberg.internal.subpackage;

public class FallbackVisibilityParent {
    String fallback(String value) {
        return "subpackage:" + value;
    }
}

