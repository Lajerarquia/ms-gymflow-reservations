package cl.duoc.gymflow.reservations.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Lo que reservations necesita de una clase del catálogo ({@code GET /api/catalog/services/{id}}).
 * El resto de los campos de la respuesta se ignora.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaseCatalogo(Long id, String name, Instant startsAt, int availableSlots) {
}
