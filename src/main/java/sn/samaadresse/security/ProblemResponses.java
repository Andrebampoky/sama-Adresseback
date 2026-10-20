package sn.samaadresse.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Réponses d'erreur JSON (RFC 9457) écrites par les filtres de sécurité, hors des contrôleurs. */
final class ProblemResponses {
    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
