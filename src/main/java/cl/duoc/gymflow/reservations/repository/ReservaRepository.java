package cl.duoc.gymflow.reservations.repository;

import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.entity.Reserva;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReservaRepository extends JpaRepository<Reserva, Long>, JpaSpecificationExecutor<Reserva> {

    /** Para impedir que un socio tenga dos reservas vivas en la misma clase. */
    boolean existsByMiembroIdAndClaseIdAndEstadoIn(String miembroId, Long claseId, Collection<EstadoReserva> estados);
}
