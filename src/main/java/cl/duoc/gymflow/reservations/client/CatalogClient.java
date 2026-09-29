package cl.duoc.gymflow.reservations.client;

import cl.duoc.gymflow.reservations.error.CatalogoNoDisponibleException;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.error.SolicitudInvalidaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente de ms-gymflow-catalog (red interna de Docker).
 * <p>
 * <b>Las rutas de cupo son un contrato</b> con {@code RutasInternas} de catalog y están escritas igual en
 * CLAUDE.md. Las pruebas de ambos lados usan las rutas literales para que un cambio en uno se note en el otro.
 */
public class CatalogClient {

    public static final String RUTA_CLASE = "/api/catalog/services/{id}";
    public static final String RUTA_TOMAR_CUPO = "/internal/services/{id}/take-slot";
    public static final String RUTA_DEVOLVER_CUPO = "/internal/services/{id}/release-slot";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CatalogClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** Datos de la clase. Si no existe, la petición del usuario es inválida (400). */
    public ClaseCatalogo obtenerClase(Long claseId) {
        try {
            return restClient.get().uri(RUTA_CLASE, claseId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw new SolicitudInvalidaException("La clase " + claseId + " no existe");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw noDisponible("respondió " + res.getStatusCode().value(), null);
                    })
                    .body(ClaseCatalogo.class);
        } catch (RestClientException e) {
            throw noDisponible(e.getMessage(), e);
        }
    }

    /** Descuenta un cupo para la reserva (al CONFIRMAR). Sin cupos → 409 con el mensaje del catálogo. */
    public CupoResultado tomarCupo(Long claseId, Long reservaId) {
        return operarCupo(RUTA_TOMAR_CUPO, claseId, reservaId);
    }

    /** Devuelve el cupo que ocupaba la reserva (al CANCELAR una reserva confirmada). */
    public CupoResultado devolverCupo(Long claseId, Long reservaId) {
        return operarCupo(RUTA_DEVOLVER_CUPO, claseId, reservaId);
    }

    private CupoResultado operarCupo(String ruta, Long claseId, Long reservaId) {
        try {
            return restClient.post().uri(ruta, claseId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("reservationId", reservaId))
                    .retrieve()
                    .onStatus(s -> s.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                        throw new ConflictoException(mensajeDe(res, "El catálogo rechazó la operación de cupo"));
                    })
                    .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw new ConflictoException("La clase " + claseId + " ya no existe en el catálogo");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw noDisponible("respondió " + res.getStatusCode().value(), null);
                    })
                    .body(CupoResultado.class);
        } catch (RestClientException e) {
            throw noDisponible(e.getMessage(), e);
        }
    }

    /** Lee el {@code message} del JSON de error del catálogo (mismo formato en todos los servicios). */
    private String mensajeDe(ClientHttpResponse respuesta, String porDefecto) {
        try {
            JsonNode error = objectMapper.readTree(respuesta.getBody());
            return error.path("message").asText(porDefecto);
        } catch (IOException e) {
            return porDefecto;
        }
    }

    /** El mensaje es para el usuario; el detalle técnico queda en la causa, para el log. */
    private static CatalogoNoDisponibleException noDisponible(String detalle, Throwable causa) {
        Throwable motivo = causa != null ? causa : new IllegalStateException("El catálogo " + detalle);
        return new CatalogoNoDisponibleException("El servicio de catálogo no está disponible en este momento", motivo);
    }
}
