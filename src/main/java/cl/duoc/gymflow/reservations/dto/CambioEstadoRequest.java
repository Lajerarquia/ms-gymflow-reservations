package cl.duoc.gymflow.reservations.dto;

import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de PUT /api/reservations/{id}/status. */
public record CambioEstadoRequest(
        @NotNull(message = "El estado es obligatorio")
        EstadoReserva status) {
}
