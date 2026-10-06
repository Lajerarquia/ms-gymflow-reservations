package cl.duoc.gymflow.reservations.mensajeria;

import java.time.Instant;

/**
 * Payload del comando {@code EMAIL_RESERVA_CONFIRMADA} (cola q.cmd.email): avisar al socio que su reserva quedó confirmada.
 * {@code memberEmail} puede venir vacío si la reserva se creó sin email.
 */
public record EmailReservaConfirmada(Long reservationId, String memberId, String memberName, String memberEmail,
                                     Long classId, String className, Instant classStartsAt) {
}
