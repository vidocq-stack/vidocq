package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.trace.StatusCode;

/**
 * Statut d'un span — code + description optionnelle.
 *
 * @param code        OTel status code (UNSET / OK / ERROR)
 * @param description description du statut (peut être {@code null} ou vide)
 */
public record StatusData(StatusCode code, String description) {

    private static final StatusData UNSET = new StatusData(StatusCode.UNSET, "");
    private static final StatusData OK = new StatusData(StatusCode.OK, "");

    public StatusData {
        if (code == null) throw new NullPointerException("code");
        if (description == null) description = "";
    }

    public static StatusData unset() {
        return UNSET;
    }

    public static StatusData ok() {
        return OK;
    }

    public static StatusData error(String description) {
        return new StatusData(StatusCode.ERROR, description);
    }
}
