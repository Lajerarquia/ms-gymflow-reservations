package cl.duoc.gymflow.reservations.repository;

import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.entity.Reserva;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros opcionales de {@code GET /api/reservations}. Cada filtro en null no se aplica.
 * {@code from}/{@code to} se aplican sobre la fecha de creación: así /reports cuenta "reservas por hora".
 */
public final class ReservaFiltros {

    private ReservaFiltros() {
    }

    public static Specification<Reserva> con(EstadoReserva estado, String miembroId, Long claseId,
                                              Instant desde, Instant hasta) {
        Specification<Reserva> filtros = (reserva, consulta, cb) -> cb.conjunction();
        if (estado != null) {
            filtros = filtros.and((reserva, consulta, cb) -> cb.equal(reserva.get("estado"), estado));
        }
        if (miembroId != null && !miembroId.isBlank()) {
            filtros = filtros.and((reserva, consulta, cb) -> cb.equal(reserva.get("miembroId"), miembroId));
        }
        if (claseId != null) {
            filtros = filtros.and((reserva, consulta, cb) -> cb.equal(reserva.get("claseId"), claseId));
        }
        if (desde != null) {
            filtros = filtros.and((reserva, consulta, cb) -> cb.greaterThanOrEqualTo(reserva.get("creadoEn"), desde));
        }
        if (hasta != null) {
            filtros = filtros.and((reserva, consulta, cb) -> cb.lessThan(reserva.get("creadoEn"), hasta));
        }
        return filtros;
    }
}
