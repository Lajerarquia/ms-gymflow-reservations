package cl.duoc.gymflow.reservations.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.gymflow.reservations.client.ClaseCatalogo;
import cl.duoc.gymflow.reservations.error.CatalogoNoDisponibleException;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.error.SolicitudInvalidaException;
import cl.duoc.gymflow.reservations.support.PruebaApiBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReservasApiTest extends PruebaApiBase {

    // ---- Crear ----

    /** Contrato con el BFF y el frontend: estos son exactamente los campos de una reserva. */
    @Test
    void crear_devuelve201_reservada_conLosCamposDelContratoYLaAuditoria() throws Exception {
        String json = crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez"),
                "oid-socio", "José Pérez")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/reservations/")))
                .andReturn().getResponse().getContentAsString();
        JsonNode reserva = objectMapper.readTree(json);

        List<String> campos = new ArrayList<>();
        reserva.fieldNames().forEachRemaining(campos::add);
        assertThat(campos).containsExactly("id", "classId", "className", "classStartsAt", "memberId", "memberName",
                "status", "createdBy", "createdById", "createdAt", "updatedBy", "updatedById", "updatedAt");
        assertThat(reserva.get("status").asText()).isEqualTo("RESERVADA");
        assertThat(reserva.get("className").asText()).isEqualTo("Spinning 45");
        assertThat(reserva.get("classStartsAt").asText()).isEqualTo(INICIO_CLASE.toString());
        assertThat(reserva.get("memberId").asText()).isEqualTo("oid-socio");
        // El nombre llegó URL-encoded en la cabecera y se guardó decodificado, con tildes
        assertThat(reserva.get("createdBy").asText()).isEqualTo("José Pérez");
        assertThat(reserva.get("createdById").asText()).isEqualTo("oid-socio");
        assertThat(reserva.get("updatedBy").asText()).isEqualTo("José Pérez");
        verify(catalogClient, never()).tomarCupo(anyLong(), anyLong());
    }

    @Test
    void instructorCreaParaUnSocio_quedaRegistradoQuienLaCreo() throws Exception {
        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez"), "oid-instructor", "Camila Rojas")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberName").value("José Pérez"))
                .andExpect(jsonPath("$.createdBy").value("Camila Rojas"))
                .andExpect(jsonPath("$.createdById").value("oid-instructor"));
    }

    @Test
    void sinCabeceraDeIdentidad_responde400() throws Exception {
        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":7,\"memberId\":\"oid-socio\",\"memberName\":\"José\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta la cabecera X-User-Id con la identidad del usuario"));
    }

    @Test
    void datosFaltantes_responde400() throws Exception {
        crear(Map.of("classId", CLASE), "oid-instructor", "Camila Rojas")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "memberId: El socio es obligatorio; memberName: El nombre del socio es obligatorio"));
    }

    @Test
    void claseInexistente_responde400() throws Exception {
        when(catalogClient.obtenerClase(99L)).thenThrow(new SolicitudInvalidaException("La clase 99 no existe"));

        crear(Map.of("classId", 99, "memberId", "oid-socio", "memberName", "José"), "oid-socio", "José")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La clase 99 no existe"));
    }

    @Test
    void claseQueYaComenzo_responde409() throws Exception {
        when(catalogClient.obtenerClase(8L)).thenReturn(
                new ClaseCatalogo(8L, "Yoga Flow", Instant.now().minus(10, ChronoUnit.MINUTES), 5));

        crear(Map.of("classId", 8, "memberId", "oid-socio", "memberName", "José"), "oid-socio", "José")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La clase 'Yoga Flow' ya comenzó; no se puede reservar"));
    }

    @Test
    void segundaReservaActivaEnLaMismaClase_responde409_peroTrasCancelarSePuede() throws Exception {
        long primera = crearReserva("oid-socio", "José Pérez");

        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José Pérez"), "oid-socio", "José Pérez")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El socio ya tiene una reserva activa en la clase 'Spinning 45'"));

        cambiarEstado(primera, "CANCELADA", "oid-socio", "José Pérez").andExpect(status().isOk());
        crearReserva("oid-socio", "José Pérez");
    }

    @Test
    void catalogoCaidoAlCrear_responde503() throws Exception {
        when(catalogClient.obtenerClase(CLASE)).thenThrow(
                new CatalogoNoDisponibleException("El servicio de catálogo no está disponible en este momento", null));

        crear(Map.of("classId", CLASE, "memberId", "oid-socio", "memberName", "José"), "oid-socio", "José")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El servicio de catálogo no está disponible en este momento"));
        assertThat(reservaRepository.count()).isZero();
    }

    // ---- Estados y cupos ----

    @Test
    void flujoCompleto_hastaCompletada_tomandoElCupoAlConfirmar() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");

        cambiarEstadoComoInstructor(id, "CONFIRMADA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.createdBy").value("José Pérez"))
                .andExpect(jsonPath("$.updatedBy").value("Camila Rojas"))
                .andExpect(jsonPath("$.updatedById").value("oid-instructor"));
        verify(catalogClient).tomarCupo(CLASE, id);

        cambiarEstadoComoInstructor(id, "EN_ESPERA").andExpect(jsonPath("$.status").value("EN_ESPERA"));
        cambiarEstadoComoInstructor(id, "EN_CLASE").andExpect(jsonPath("$.status").value("EN_CLASE"));
        cambiarEstadoComoInstructor(id, "COMPLETADA").andExpect(jsonPath("$.status").value("COMPLETADA"));

        cambiarEstadoComoInstructor(id, "CANCELADA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La reserva está COMPLETADA y ya no admite cambios"));
        verify(catalogClient, never()).devolverCupo(anyLong(), anyLong());
    }

    @Test
    void enClaseSinConfirmar_responde409_sinTocarElCatalogo() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");

        cambiarEstadoComoInstructor(id, "EN_CLASE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("No se puede pasar a EN_CLASE sin CONFIRMAR"));

        verify(catalogClient, never()).tomarCupo(anyLong(), anyLong());
        mvc.perform(get("/api/reservations/" + id)).andExpect(jsonPath("$.status").value("RESERVADA"));
    }

    @Test
    void confirmarSinCupos_responde409_yLaReservaSigueReservada() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        when(catalogClient.tomarCupo(CLASE, id))
                .thenThrow(new ConflictoException("La clase 'Spinning 45' no tiene cupos disponibles"));

        cambiarEstadoComoInstructor(id, "CONFIRMADA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La clase 'Spinning 45' no tiene cupos disponibles"));

        mvc.perform(get("/api/reservations/" + id)).andExpect(jsonPath("$.status").value("RESERVADA"));
    }

    @Test
    void cancelarUnaReservaConfirmada_devuelveElCupo() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        cambiarEstadoComoInstructor(id, "CONFIRMADA");

        cambiarEstado(id, "CANCELADA", "oid-socio", "José Pérez")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.updatedBy").value("José Pérez"));

        verify(catalogClient).devolverCupo(CLASE, id);
    }

    @Test
    void cancelarDesdeEnEspera_tambienDevuelveElCupo() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        cambiarEstadoComoInstructor(id, "CONFIRMADA");
        cambiarEstadoComoInstructor(id, "EN_ESPERA");

        cambiarEstadoComoInstructor(id, "CANCELADA").andExpect(status().isOk());

        verify(catalogClient).devolverCupo(CLASE, id);
    }

    @Test
    void cancelarUnaReservaNoConfirmada_noTocaElCupo() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");

        cambiarEstado(id, "CANCELADA", "oid-socio", "José Pérez").andExpect(status().isOk());

        verify(catalogClient, never()).devolverCupo(anyLong(), anyLong());
    }

    @Test
    void catalogoCaidoAlConfirmar_responde503_yNoCambiaElEstado() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        when(catalogClient.tomarCupo(CLASE, id)).thenThrow(
                new CatalogoNoDisponibleException("El servicio de catálogo no está disponible en este momento", null));

        cambiarEstadoComoInstructor(id, "CONFIRMADA").andExpect(status().isServiceUnavailable());

        mvc.perform(get("/api/reservations/" + id)).andExpect(jsonPath("$.status").value("RESERVADA"));
    }

    @Test
    void estadoInexistente_responde400() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");

        cambiarEstadoComoInstructor(id, "PAGADA")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El valor 'PAGADA' no es válido para 'status'"));
    }

    @Test
    void reservaInexistente_responde404() throws Exception {
        mvc.perform(get("/api/reservations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("La reserva 999 no existe"));
        cambiarEstadoComoInstructor(999, "CONFIRMADA").andExpect(status().isNotFound());
    }

    // ---- Listar ----

    @Test
    void listar_filtraPorEstadoSocioClaseYFechas() throws Exception {
        when(catalogClient.obtenerClase(8L)).thenReturn(new ClaseCatalogo(8L, "Yoga Flow", INICIO_CLASE, 10));
        long deJose = crearReserva("oid-jose", "José Pérez");
        crearReserva("oid-ana", "Ana Díaz");
        crear(Map.of("classId", 8, "memberId", "oid-jose", "memberName", "José Pérez"), "oid-jose", "José Pérez");
        cambiarEstadoComoInstructor(deJose, "CONFIRMADA");

        mvc.perform(get("/api/reservations")).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/reservations").param("memberId", "oid-jose")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/reservations").param("classId", String.valueOf(CLASE))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/reservations").param("status", "CONFIRMADA"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(deJose));
        mvc.perform(get("/api/reservations")
                        .param("from", Instant.now().minus(1, ChronoUnit.HOURS).toString())
                        .param("to", Instant.now().plus(1, ChronoUnit.HOURS).toString()))
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/reservations").param("from", Instant.now().plus(1, ChronoUnit.HOURS).toString()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listar_masRecientesPrimero() throws Exception {
        long primera = crearReserva("oid-jose", "José Pérez");
        long segunda = crearReserva("oid-ana", "Ana Díaz");

        mvc.perform(get("/api/reservations"))
                .andExpect(jsonPath("$[0].id").value(segunda))
                .andExpect(jsonPath("$[1].id").value(primera));
    }

    @Test
    void listar_filtrosInvalidos_responde400() throws Exception {
        mvc.perform(get("/api/reservations").param("status", "PAGADA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'status' no es válido"));
        mvc.perform(get("/api/reservations")
                        .param("from", Instant.now().toString())
                        .param("to", Instant.now().minus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El filtro 'from' debe ser anterior a 'to'"));
    }
}
