package cl.duoc.gymflow.reservations.mensajeria;

import cl.duoc.gymflow.reservations.config.FiltroTraza;
import cl.duoc.gymflow.reservations.dto.ReservaResponse;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Publica en RabbitMQ los comandos que genera una reserva CONFIRMADA:
 * <ul>
 *   <li>email al socio → {@code cmd.direct} con routing key {@code email.send} (cola q.cmd.email);</li>
 *   <li>ticket de check-in al instructor → {@code cmd.topic} con {@code checkin.ticket.created} (calza con
 *       {@code checkin.#}, cola q.cmd.checkin).</li>
 * </ul>
 * <b>La notificación no bloquea ni hace fallar la reserva.</b> Se publica en segundo plano (executor de tareas de
 * Spring Boot), después de que la confirmación quedó guardada. Si RabbitMQ no está disponible, el RabbitTemplate
 * reintenta (ver {@code spring.rabbitmq.template.retry}) y, si aun así falla, se registra el error: la reserva
 * sigue confirmada. La garantía completa ("si se guardó, se notifica sí o sí") requiere el patrón outbox; queda
 * anotado como mejora.
 */
@Component
public class PublicadorNotificaciones {

    public static final String TIPO_EMAIL = "EMAIL_RESERVA_CONFIRMADA";
    public static final String TIPO_CHECKIN = "CHECKIN_TICKET_CREADO";

    private static final Logger log = LoggerFactory.getLogger(PublicadorNotificaciones.class);

    private final RabbitTemplate rabbitTemplate;
    private final Executor executor;

    public PublicadorNotificaciones(RabbitTemplate rabbitTemplate,
                                    @Qualifier("applicationTaskExecutor") Executor executor) {
        this.rabbitTemplate = rabbitTemplate;
        this.executor = executor;
        // Ack del broker (publisher confirms): RabbitMQ avisa si recibió o no cada mensaje.
        rabbitTemplate.setConfirmCallback((correlacion, ack, causa) -> {
            if (!ack) {
                log.error("RabbitMQ no aceptó el mensaje {}: {}", correlacion == null ? "?" : correlacion.getId(), causa);
            }
        });
        // mandatory=true: si el exchange no tiene una cola que calce con la routing key, el mensaje vuelve aquí.
        rabbitTemplate.setReturnsCallback(devuelto -> log.error(
                "Mensaje sin cola de destino: exchange={}, routingKey={}, motivo={}. ¿Está corriendo ms-gymflow-notify?",
                devuelto.getExchange(), devuelto.getRoutingKey(), devuelto.getReplyText()));
    }

    /** Llamar solo después de guardar la confirmación. Nunca lanza excepciones. */
    public void reservaConfirmada(ReservaResponse reserva) {
        String traceId = MDC.get(FiltroTraza.CLAVE_MDC);
        String trazaFinal = traceId != null ? traceId : UUID.randomUUID().toString();
        // Mismo correlationId para el email y el ticket: son consecuencia de la misma confirmación.
        String correlationId = "reserva-" + reserva.id() + "-confirmada-" + UUID.randomUUID();

        EventoEnvelope<EmailReservaConfirmada> email = EventoEnvelope.nuevo(TIPO_EMAIL, trazaFinal, correlationId,
                new EmailReservaConfirmada(reserva.id(), reserva.memberId(), reserva.memberName(), reserva.memberEmail(),
                        reserva.classId(), reserva.className(), reserva.classStartsAt()));
        EventoEnvelope<TicketCheckin> ticket = EventoEnvelope.nuevo(TIPO_CHECKIN, trazaFinal, correlationId,
                new TicketCheckin(reserva.id(), reserva.classId(), reserva.className(), reserva.classStartsAt(),
                        reserva.memberId(), reserva.memberName(), reserva.updatedBy()));

        try {
            executor.execute(() -> {
                publicar(RabbitMQConfig.EXCHANGE_DIRECT, RabbitMQConfig.ROUTING_EMAIL, email);
                publicar(RabbitMQConfig.EXCHANGE_TOPIC, RabbitMQConfig.ROUTING_CHECKIN, ticket);
            });
        } catch (RuntimeException rechazado) {
            log.error("Reserva {}: no se pudo programar el envío de notificaciones", reserva.id(), rechazado);
        }
    }

    private void publicar(String exchange, String routingKey, EventoEnvelope<?> evento) {
        // Las propiedades AMQP repiten los datos del envelope: así se ven en la Management UI sin abrir el JSON.
        MessagePostProcessor propiedades = mensaje -> {
            mensaje.getMessageProperties().setMessageId(evento.eventId());
            mensaje.getMessageProperties().setCorrelationId(evento.correlationId());
            mensaje.getMessageProperties().setType(evento.type());
            mensaje.getMessageProperties().setHeader(FiltroTraza.CABECERA, evento.traceId());
            return mensaje;
        };
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, evento, propiedades, new CorrelationData(evento.eventId()));
            log.info("Publicado {} eventId={} en {} ({}) traceId={}", evento.type(), evento.eventId(), exchange,
                    routingKey, evento.traceId());
        } catch (AmqpException fallo) {
            log.error("No se pudo publicar {} eventId={} en {} ({}): {}. La reserva sigue confirmada.",
                    evento.type(), evento.eventId(), exchange, routingKey, fallo.getMessage());
        }
    }
}
