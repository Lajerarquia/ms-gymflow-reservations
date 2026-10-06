package cl.duoc.gymflow.reservations.mensajeria;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre común de todos los mensajes de GymFlow (caso, sección 8). El consumidor decide qué hacer según
 * {@code type} y descarta duplicados por {@code eventId}.
 *
 * @param type          tipo del comando, p. ej. {@code EMAIL_RESERVA_CONFIRMADA}
 * @param eventId       identificador único del mensaje (UUID); base de la idempotencia en ms-gymflow-notify
 * @param timestamp     cuándo se generó, en UTC
 * @param traceId       traza de la petición HTTP que originó el mensaje (cabecera X-Trace-Id o una nueva)
 * @param correlationId agrupa los mensajes de una misma operación (el email y el ticket de una confirmación)
 * @param payload       datos propios del comando
 */
public record EventoEnvelope<T>(String type, String eventId, Instant timestamp, String traceId,
                                String correlationId, T payload) {

    public static <T> EventoEnvelope<T> nuevo(String type, String traceId, String correlationId, T payload) {
        return new EventoEnvelope<>(type, UUID.randomUUID().toString(), Instant.now(), traceId, correlationId, payload);
    }
}
