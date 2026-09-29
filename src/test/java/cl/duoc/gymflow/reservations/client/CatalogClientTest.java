package cl.duoc.gymflow.reservations.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cl.duoc.gymflow.reservations.error.CatalogoNoDisponibleException;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.error.SolicitudInvalidaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contrato con ms-gymflow-catalog, visto desde reservations. Las rutas y los cuerpos están escritos a mano
 * y son los mismos que prueba {@code CuposInternosTest} en catalog: si uno cambia, las dos pruebas lo delatan.
 */
class CatalogClientTest {

    private static final String CATALOGO = "http://ms-gymflow-catalog:8082";

    private MockRestServiceServer catalogo;
    private CatalogClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CATALOGO);
        catalogo = MockRestServiceServer.bindTo(builder).build();
        cliente = new CatalogClient(builder.build(), new ObjectMapper());
    }

    @Test
    void tomarCupo_usaLaRutaYElCuerpoDelContrato() {
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/take-slot"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"reservationId\": 42}", true))
                .andRespond(withSuccess("{\"serviceId\":7,\"reservationId\":42,\"availableSlots\":11,\"changed\":true}",
                        MediaType.APPLICATION_JSON));

        CupoResultado resultado = cliente.tomarCupo(7L, 42L);

        catalogo.verify();
        assertThat(resultado.changed()).isTrue();
        assertThat(resultado.availableSlots()).isEqualTo(11);
    }

    @Test
    void devolverCupo_usaLaRutaYElCuerpoDelContrato() {
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/release-slot"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"reservationId\": 42}", true))
                .andRespond(withSuccess("{\"serviceId\":7,\"reservationId\":42,\"availableSlots\":12,\"changed\":false}",
                        MediaType.APPLICATION_JSON));

        assertThat(cliente.devolverCupo(7L, 42L).changed()).isFalse();
        catalogo.verify();
    }

    @Test
    void sinCupos_propagaEl409ConElMensajeDelCatalogo() {
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/take-slot"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"message\":\"La clase 'Spinning 45' no tiene cupos disponibles\"}"));

        assertThatThrownBy(() -> cliente.tomarCupo(7L, 42L))
                .isInstanceOf(ConflictoException.class)
                .hasMessage("La clase 'Spinning 45' no tiene cupos disponibles");
    }

    @Test
    void claseEliminadaAlTomarCupo_es409() {
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/take-slot")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> cliente.tomarCupo(7L, 42L))
                .isInstanceOf(ConflictoException.class)
                .hasMessage("La clase 7 ya no existe en el catálogo");
    }

    @Test
    void obtenerClase_leeLosCamposQueUsaReservations() {
        catalogo.expect(requestTo(CATALOGO + "/api/catalog/services/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":7,"name":"Spinning 45","description":"x","instructor":"Camila Rojas","roomId":1,
                         "roomName":"Sala A","branch":"Providencia","startsAt":"2026-10-01T11:00:00Z",
                         "endsAt":"2026-10-01T11:45:00Z","durationMinutes":45,"capacity":20,"availableSlots":18,
                         "occupiedSlots":2,"plan":"BASICO"}""", MediaType.APPLICATION_JSON));

        ClaseCatalogo clase = cliente.obtenerClase(7L);

        assertThat(clase.name()).isEqualTo("Spinning 45");
        assertThat(clase.startsAt()).isEqualTo(Instant.parse("2026-10-01T11:00:00Z"));
        assertThat(clase.availableSlots()).isEqualTo(18);
    }

    @Test
    void claseInexistente_es400ParaQuienReserva() {
        catalogo.expect(requestTo(CATALOGO + "/api/catalog/services/99")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> cliente.obtenerClase(99L))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessage("La clase 99 no existe");
    }

    @Test
    void errorInternoOCaidaDelCatalogo_esCatalogoNoDisponible() {
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/take-slot")).andRespond(withServerError());
        assertThatThrownBy(() -> cliente.tomarCupo(7L, 42L))
                .isInstanceOf(CatalogoNoDisponibleException.class)
                .hasMessage("El servicio de catálogo no está disponible en este momento");

        catalogo.reset();
        catalogo.expect(requestTo(CATALOGO + "/internal/services/7/release-slot"))
                .andRespond(withException(new IOException("Connection refused")));
        assertThatThrownBy(() -> cliente.devolverCupo(7L, 42L))
                .isInstanceOf(CatalogoNoDisponibleException.class);
    }
}
