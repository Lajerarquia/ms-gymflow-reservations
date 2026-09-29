package cl.duoc.gymflow.reservations.error;

/**
 * ms-gymflow-catalog no respondió o respondió algo inesperado. Se responde 503 y la reserva no cambia.
 */
public class CatalogoNoDisponibleException extends RuntimeException {

    public CatalogoNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
