package cl.duoc.gymflow.reservations.error;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/**
 * Mismo formato de error que el BFF: el BFF reenvía este JSON tal cual y el frontend siempre lee {@code message}.
 */
public record ErrorResponse(String timestamp, int status, String error, String message, String path) {

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(Instant.now().toString(), status.value(), status.getReasonPhrase(), message, path);
    }
}
