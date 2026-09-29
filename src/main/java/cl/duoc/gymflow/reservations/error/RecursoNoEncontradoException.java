package cl.duoc.gymflow.reservations.error;

/** La reserva pedida no existe. Se responde 404. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
