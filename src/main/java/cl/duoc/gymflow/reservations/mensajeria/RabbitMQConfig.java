package cl.duoc.gymflow.reservations.mensajeria;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lo que reservations necesita de la topología RabbitMQ del caso (sección 8): solo los exchanges donde publica.
 * <p>
 * Las colas, las DLQ y los bindings los declara ms-gymflow-notify, que es su dueño. Si los dos servicios
 * declararan la misma cola con argumentos distintos, RabbitMQ respondería PRECONDITION_FAILED. Declarar un
 * exchange es idempotente: si ya existe con los mismos parámetros (durable, sin auto-delete), no pasa nada.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_DIRECT = "cmd.direct";
    public static final String EXCHANGE_TOPIC = "cmd.topic";

    /** cmd.direct → q.cmd.email (binding exacto "email.send"). */
    public static final String ROUTING_EMAIL = "email.send";
    /** cmd.topic → q.cmd.checkin (binding "checkin.#"). */
    public static final String ROUTING_CHECKIN = "checkin.ticket.created";

    @Bean
    public DirectExchange cmdDirect() {
        return new DirectExchange(EXCHANGE_DIRECT, true, false);
    }

    @Bean
    public TopicExchange cmdTopic() {
        return new TopicExchange(EXCHANGE_TOPIC, true, false);
    }

    /**
     * Los mensajes viajan como JSON (content-type application/json) en vez de String o serialización Java.
     * Spring Boot usa este conversor en el RabbitTemplate autoconfigurado. Se reutiliza el ObjectMapper de
     * Spring Boot para que las fechas {@code Instant} salgan en ISO-8601, igual que en la API REST.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
