package cl.duoc.gymflow.reservations.error;

/**
 * La operación choca con el estado actual (sin cupos, reserva duplicada, clase ya iniciada...).
 * Se responde 409.
 */
public class ConflictoException extends RuntimeException {

    public ConflictoException(String mensaje) {
        super(mensaje);
    }
}
