package cl.duoc.gymflow.reservations.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Respuesta de take-slot y release-slot.
 *
 * @param changed false si el catálogo ya había hecho esa operación para esta reserva (reintento idempotente)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CupoResultado(Long serviceId, Long reservationId, int availableSlots, boolean changed) {
}
