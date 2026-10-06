package cl.duoc.gymflow.reservations.support;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.gymflow.reservations.client.CatalogClient;
import cl.duoc.gymflow.reservations.client.ClaseCatalogo;
import cl.duoc.gymflow.reservations.client.CupoResultado;
import cl.duoc.gymflow.reservations.repository.ReservaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base de las pruebas de API: contexto completo con H2 en memoria y el catálogo simulado.
 * Las peticiones llevan las cabeceras X-User-* igual que las enviaría el BFF (nombre URL-encoded).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservas-pruebas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
public abstract class PruebaApiBase {

    protected static final long CLASE = 7L;
    protected static final Instant INICIO_CLASE = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ReservaRepository reservaRepository;

    @MockitoBean
    protected CatalogClient catalogClient;

    /** Sin broker en las pruebas: se verifica qué se publicaría. */
    @MockitoBean
    protected RabbitTemplate rabbitTemplate;

    /** Executor donde se publican las notificaciones (en segundo plano). */
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor tareas;

    /**
     * Espera a que terminen las publicaciones en segundo plano de esta prueba. Si no, una publicación tardía
     * podría llegar al mock de la prueba siguiente y hacerla fallar al azar.
     */
    @AfterEach
    void esperarPublicacionesPendientes() throws InterruptedException {
        long limite = System.currentTimeMillis() + 5000;
        while ((tareas.getActiveCount() > 0 || !tareas.getThreadPoolExecutor().getQueue().isEmpty())
                && System.currentTimeMillis() < limite) {
            Thread.sleep(10);
        }
    }

    @BeforeEach
    void prepararCatalogoYBd() {
        reservaRepository.deleteAll();
        when(catalogClient.obtenerClase(CLASE)).thenReturn(new ClaseCatalogo(CLASE, "Spinning 45", INICIO_CLASE, 10));
        when(catalogClient.tomarCupo(anyLong(), anyLong())).thenAnswer(inv ->
                new CupoResultado(inv.getArgument(0), inv.getArgument(1), 9, true));
        when(catalogClient.devolverCupo(anyLong(), anyLong())).thenAnswer(inv ->
                new CupoResultado(inv.getArgument(0), inv.getArgument(1), 10, true));
    }

    protected ResultActions crear(Object cuerpo, String usuarioId, String usuarioNombre) throws Exception {
        return crear(cuerpo, usuarioId, usuarioNombre, null);
    }

    /** Igual que {@link #crear(Object, String, String)}, con la cabecera X-User-Email que envía el BFF. */
    protected ResultActions crear(Object cuerpo, String usuarioId, String usuarioNombre, String usuarioEmail) throws Exception {
        MockHttpServletRequestBuilder peticion = post("/api/reservations")
                .header("X-User-Id", usuarioId)
                .header("X-User-Name", URLEncoder.encode(usuarioNombre, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cuerpo));
        if (usuarioEmail != null) {
            peticion.header("X-User-Email", usuarioEmail);
        }
        return mvc.perform(peticion);
    }

    protected long crearReserva(String socioId, String socioNombre) throws Exception {
        String json = crear(Map.of("classId", CLASE, "memberId", socioId, "memberName", socioNombre), socioId, socioNombre)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    protected ResultActions cambiarEstado(long id, String estado, String usuarioId, String usuarioNombre) throws Exception {
        return mvc.perform(put("/api/reservations/" + id + "/status")
                .header("X-User-Id", usuarioId)
                .header("X-User-Name", URLEncoder.encode(usuarioNombre, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + estado + "\"}"));
    }

    protected ResultActions cambiarEstadoComoInstructor(long id, String estado) throws Exception {
        return cambiarEstado(id, estado, "oid-instructor", "Camila Rojas");
    }
}
