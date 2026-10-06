package cl.duoc.gymflow.reservations.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.gymflow.reservations.client.CupoResultado;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.support.PruebaApiBase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.http.MediaType;

/**
 * Publicación en RabbitMQ al CONFIRMAR una reserva (EP2) y email del socio.
 * El RabbitTemplate está simulado; la publicación ocurre en otro hilo, por eso se verifica con {@code timeout}.
 */
class NotificacionesTest extends PruebaApiBase {

    private static final long ESPERA_MS = 2000;

    // ---- Qué se publica al confirmar ----

    @Test
    void confirmar_publicaEmailPorDirectYTicketPorTopic_conElMismoCorrelationId() throws Exception {
        long id = crearReservaConEmail("oid-socio", "José Pérez", "jose@gymflow.cl");

        mvc.perform(put("/api/reservations/" + id + "/status")
                        .header("X-User-Id", "oid-instructor")
                        .header("X-User-Name", URLEncoder.encode("Camila Rojas", StandardCharsets.UTF_8))
                        .header("X-Trace-Id", "traza-prueba-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMADA\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "traza-prueba-1"));

        EventoEnvelope<?> email = publicado(RabbitMQConfig.EXCHANGE_DIRECT, "email.send");
        EventoEnvelope<?> ticket = publicado(RabbitMQConfig.EXCHANGE_TOPIC, "checkin.ticket.created");

        assertThat(email.type()).isEqualTo("EMAIL_RESERVA_CONFIRMADA");
        assertThat(ticket.type()).isEqualTo("CHECKIN_TICKET_CREADO");
        assertThat(UUID.fromString(email.eventId())).isNotEqualTo(UUID.fromString(ticket.eventId()));
        assertThat(email.timestamp()).isNotNull();
        assertThat(email.traceId()).isEqualTo("traza-prueba-1");
        assertThat(ticket.traceId()).isEqualTo("traza-prueba-1");
        assertThat(email.correlationId()).startsWith("reserva-" + id + "-confirmada-").isEqualTo(ticket.correlationId());

        EmailReservaConfirmada datosEmail = (EmailReservaConfirmada) email.payload();
        assertThat(datosEmail.reservationId()).isEqualTo(id);
        assertThat(datosEmail.memberEmail()).isEqualTo("jose@gymflow.cl");
        assertThat(datosEmail.memberName()).isEqualTo("José Pérez");
        assertThat(datosEmail.className()).isEqualTo("Spinning 45");
        assertThat(datosEmail.classStartsAt()).isEqualTo(INICIO_CLASE);

        TicketCheckin datosTicket = (TicketCheckin) ticket.payload();
        assertThat(datosTicket.reservationId()).isEqualTo(id);
        assertThat(datosTicket.classId()).isEqualTo(CLASE);
        assertThat(datosTicket.confirmedBy()).isEqualTo("Camila Rojas");
    }

    /** Las propiedades AMQP repiten el envelope (se ven en la Management UI) y la confirmación del broker usa el eventId. */
    @Test
    void confirmar_completaLasPropiedadesAmqpDelMensaje() throws Exception {
        long id = crearReservaConEmail("oid-socio", "José Pérez", "jose@gymflow.cl");
        cambiarEstadoComoInstructor(id, "CONFIRMADA").andExpect(status().isOk());

        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> propiedades = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate, timeout(ESPERA_MS)).convertAndSend(eq("cmd.direct"), eq("email.send"), evento.capture(),
                propiedades.capture(), correlacion.capture());
        EventoEnvelope<?> email = (EventoEnvelope<?>) evento.getValue();

        Message mensaje = propiedades.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(mensaje.getMessageProperties().getMessageId()).isEqualTo(email.eventId());
        assertThat(mensaje.getMessageProperties().getCorrelationId()).isEqualTo(email.correlationId());
        assertThat(mensaje.getMessageProperties().getType()).isEqualTo("EMAIL_RESERVA_CONFIRMADA");
        assertThat((String) mensaje.getMessageProperties().getHeader("X-Trace-Id")).isEqualTo(email.traceId());
        assertThat(correlacion.getValue().getId()).isEqualTo(email.eventId());
    }

    @Test
    void sinCabeceraDeTraza_seGeneraUnaYSeDevuelveEnLaRespuesta() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        String traza = cambiarEstadoComoInstructor(id, "CONFIRMADA")
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Trace-Id");

        assertThat(UUID.fromString(traza)).isNotNull();
        assertThat(publicado("cmd.direct", "email.send").traceId()).isEqualTo(traza);
    }

    // ---- Cuándo NO se publica ----

    @Test
    void crearYOtrasTransiciones_noPublicanNada() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        cambiarEstadoComoInstructor(id, "CANCELADA").andExpect(status().isOk());

        verify(rabbitTemplate, after(300).never())
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void confirmacionRechazadaPorFaltaDeCupo_noPublica() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        when(catalogClient.tomarCupo(anyLong(), anyLong())).thenThrow(new ConflictoException("La clase no tiene cupos disponibles"));

        cambiarEstadoComoInstructor(id, "CONFIRMADA").andExpect(status().isConflict());

        verify(rabbitTemplate, after(300).never())
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    // ---- RabbitMQ caído no bloquea el core ----

    @Test
    void rabbitCaido_laReservaIgualQuedaConfirmada() throws Exception {
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        when(catalogClient.tomarCupo(anyLong(), anyLong())).thenAnswer(inv ->
                new CupoResultado(inv.getArgument(0), inv.getArgument(1), 9, true));
        long id = crearReserva("oid-socio", "José Pérez");

        cambiarEstadoComoInstructor(id, "CONFIRMADA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"));

        // Se intentaron los dos envíos (el fallo del email no impide intentar el ticket)
        verify(rabbitTemplate, timeout(ESPERA_MS)).convertAndSend(eq("cmd.direct"), eq("email.send"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(rabbitTemplate, timeout(ESPERA_MS)).convertAndSend(eq("cmd.topic"), eq("checkin.ticket.created"), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reservations/" + id))
                .andExpect(jsonPath("$.status").value("CONFIRMADA"));
    }

    // ---- Email del socio ----

    @Test
    void socioReservaParaSiMismo_seUsaElEmailDelToken_yNoElDelCuerpo() throws Exception {
        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez",
                        "memberEmail", "otro@correo.cl"), "oid-socio", "José Pérez", "jose@gymflow.cl")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberEmail").value("jose@gymflow.cl"));
    }

    @Test
    void instructorReservaParaUnSocio_seUsaElEmailDelCuerpo() throws Exception {
        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez",
                        "memberEmail", "jose@gymflow.cl"), "oid-instructor", "Camila Rojas", "camila@gymflow.cl")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberEmail").value("jose@gymflow.cl"))
                .andExpect(jsonPath("$.createdBy").value("Camila Rojas"));
    }

    @Test
    void instructorSinEmailDelSocio_quedaSinEmail() throws Exception {
        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez"),
                "oid-instructor", "Camila Rojas", "camila@gymflow.cl")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberEmail").doesNotExist());
    }

    @Test
    void emailInvalido_responde400() throws Exception {
        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez",
                        "memberEmail", "no-es-un-email"), "oid-instructor", "Camila Rojas")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("El email del socio no es válido")));
    }

    // ---- utilidades ----

    private long crearReservaConEmail(String socioId, String socioNombre, String email) throws Exception {
        String json = crear(Map.of("classId", CLASE, "memberId", socioId, "memberName", socioNombre), socioId, socioNombre, email)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private EventoEnvelope<?> publicado(String exchange, String routingKey) {
        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, timeout(ESPERA_MS)).convertAndSend(eq(exchange), eq(routingKey), evento.capture(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        return (EventoEnvelope<?>) evento.getValue();
    }
}
