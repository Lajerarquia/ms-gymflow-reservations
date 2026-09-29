package cl.duoc.gymflow.reservations.config;

import cl.duoc.gymflow.reservations.client.CatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP hacia ms-gymflow-catalog, con tiempos máximos explícitos: si el catálogo se cuelga,
 * se responde 503 y la reserva no cambia.
 */
@Configuration
public class ClientesConfig {

    static final Duration TIEMPO_CONEXION = Duration.ofSeconds(3);
    static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(10);

    @Bean
    CatalogClient catalogClient(RestClient.Builder builder, ObjectMapper objectMapper,
                                @Value("${gymflow.services.catalog-url}") String catalogUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIEMPO_CONEXION).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIEMPO_RESPUESTA);
        RestClient restClient = builder.baseUrl(catalogUrl).requestFactory(factory).build();
        return new CatalogClient(restClient, objectMapper);
    }
}
