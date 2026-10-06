package cl.duoc.gymflow.reservations.mensajeria;

import java.time.Instant;

/**
 * Payload del comando {@code CHECKIN_TICKET_CREADO} (cola q.cmd.checkin): ticket para que el instructor controle
 * el ingreso del socio a la clase. {@code confirmedBy} es quien confirmó la reserva.
 */
public record TicketCheckin(Long reservationId, Long classId, String className, Instant classStartsAt,
                            String memberId, String memberName, String confirmedBy) {
}
