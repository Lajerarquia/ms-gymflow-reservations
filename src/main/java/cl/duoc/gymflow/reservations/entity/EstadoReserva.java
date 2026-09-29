package cl.duoc.gymflow.reservations.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de una reserva y las transiciones permitidas.
 * <pre>
 * RESERVADA ──> CONFIRMADA ──> EN_ESPERA ──> EN_CLASE ──> COMPLETADA
 *     │             │  └───────────────────────^
 *     └─────────────┴──────────┴──> CANCELADA
 * </pre>
 * <ul>
 *   <li>RESERVADA: el socio pidió el cupo; todavía no lo ocupa.</li>
 *   <li>CONFIRMADA: el instructor confirmó; aquí se descuenta el cupo de la clase.</li>
 *   <li>EN_ESPERA: se registró el ingreso del socio; espera que empiece la clase.</li>
 *   <li>EN_CLASE: la clase está en curso. Solo se llega desde un estado confirmado.</li>
 *   <li>COMPLETADA y CANCELADA: estados finales.</li>
 * </ul>
 */
public enum EstadoReserva {
    RESERVADA,
    CONFIRMADA,
    EN_ESPERA,
    EN_CLASE,
    COMPLETADA,
    CANCELADA;

    public Set<EstadoReserva> siguientesPermitidos() {
        return switch (this) {
            case RESERVADA -> EnumSet.of(CONFIRMADA, CANCELADA);
            case CONFIRMADA -> EnumSet.of(EN_ESPERA, EN_CLASE, CANCELADA);
            case EN_ESPERA -> EnumSet.of(EN_CLASE, CANCELADA);
            case EN_CLASE -> EnumSet.of(COMPLETADA);
            case COMPLETADA, CANCELADA -> EnumSet.noneOf(EstadoReserva.class);
        };
    }

    public boolean puedePasarA(EstadoReserva nuevo) {
        return siguientesPermitidos().contains(nuevo);
    }

    /** CONFIRMADA y EN_ESPERA ocupan un cupo de la clase: si se cancelan, hay que devolverlo. */
    public boolean ocupaCupo() {
        return this == CONFIRMADA || this == EN_ESPERA;
    }

    /** Estados en que la reserva sigue viva: un socio no puede tener dos de estas para la misma clase. */
    public static Set<EstadoReserva> activos() {
        return EnumSet.of(RESERVADA, CONFIRMADA, EN_ESPERA, EN_CLASE);
    }

    /**
     * Motivo en español de por qué no se puede pasar de este estado a {@code nuevo}.
     * El caso RESERVADA → EN_CLASE tiene su propio mensaje porque es la regla explícita del negocio.
     */
    public String motivoRechazo(EstadoReserva nuevo) {
        if (this == nuevo) {
            return "La reserva ya está en estado " + this;
        }
        if (nuevo == EN_CLASE && this == RESERVADA) {
            return "No se puede pasar a EN_CLASE sin CONFIRMAR";
        }
        if (siguientesPermitidos().isEmpty()) {
            return "La reserva está " + this + " y ya no admite cambios";
        }
        return "No se puede pasar de " + this + " a " + nuevo;
    }
}
