package cl.duoc.gymflow.reservations.error;

/** Los datos enviados no tienen sentido (por ejemplo, una clase que no existe). Se responde 400. */
public class SolicitudInvalidaException extends RuntimeException {

    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
