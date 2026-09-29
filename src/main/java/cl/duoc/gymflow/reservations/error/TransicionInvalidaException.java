package cl.duoc.gymflow.reservations.error;

/**
 * El cambio de estado no está permitido desde el estado actual (por ejemplo, RESERVADA → EN_CLASE). Se responde 409.
 */
public class TransicionInvalidaException extends ConflictoException {

    public TransicionInvalidaException(String mensaje) {
        super(mensaje);
    }
}
