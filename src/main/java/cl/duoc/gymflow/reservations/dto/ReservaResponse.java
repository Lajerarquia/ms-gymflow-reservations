package cl.duoc.gymflow.reservations.dto;

import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.entity.Reserva;
import java.time.Instant;

/**
 * Reserva tal como la ven el BFF y el frontend. El BFF lee {@code memberId} para aplicar las reglas del Socio;
 * /audit usa {@code createdBy/updatedBy/createdAt/updatedAt}; /reports usa {@code createdAt}, {@code status}
 * y {@code classId}.
 */
public record ReservaResponse(
        Long id,
        Long classId,
        String className,
        Instant classStartsAt,
        String memberId,
        String memberName,
        String memberEmail,
        EstadoReserva status,
        String createdBy,
        String createdById,
        Instant createdAt,
        String updatedBy,
        String updatedById,
        Instant updatedAt) {

    public static ReservaResponse desde(Reserva reserva) {
        return new ReservaResponse(
                reserva.getId(),
                reserva.getClaseId(),
                reserva.getClaseNombre(),
                reserva.getClaseInicio(),
                reserva.getMiembroId(),
                reserva.getMiembroNombre(),
                reserva.getMiembroEmail(),
                reserva.getEstado(),
                reserva.getCreadoPor(),
                reserva.getCreadoPorId(),
                reserva.getCreadoEn(),
                reserva.getActualizadoPor(),
                reserva.getActualizadoPorId(),
                reserva.getActualizadoEn());
    }
}
