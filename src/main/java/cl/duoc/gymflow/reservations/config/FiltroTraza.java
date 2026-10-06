package cl.duoc.gymflow.reservations.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un {@code traceId} a cada petición: el de la cabecera {@code X-Trace-Id} si viene una válida, o uno nuevo.
 * Queda en el MDC de los logs y viaja en el envelope de los mensajes RabbitMQ, así se puede seguir una
 * confirmación desde la API hasta el log de ms-gymflow-notify. También se devuelve en la respuesta.
 */
@Component
public class FiltroTraza extends OncePerRequestFilter {

    public static final String CABECERA = "X-Trace-Id";
    public static final String CLAVE_MDC = "traceId";

    /** Solo se acepta un valor corto y sin caracteres raros, para no meter cualquier cosa en logs y mensajes. */
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(CABECERA);
        String traceId = recibido != null && VALIDO.matcher(recibido).matches() ? recibido : UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, traceId);
        response.setHeader(CABECERA, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
