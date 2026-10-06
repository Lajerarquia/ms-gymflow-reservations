package cl.duoc.gymflow.reservations.service;

import cl.duoc.gymflow.reservations.client.CatalogClient;
import cl.duoc.gymflow.reservations.client.ClaseCatalogo;
import cl.duoc.gymflow.reservations.client.CupoResultado;
import cl.duoc.gymflow.reservations.dto.ReservaRequest;
import cl.duoc.gymflow.reservations.dto.ReservaResponse;
import cl.duoc.gymflow.reservations.entity.Autor;
import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.entity.Reserva;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.error.RecursoNoEncontradoException;
import cl.duoc.gymflow.reservations.error.SolicitudInvalidaException;
import cl.duoc.gymflow.reservations.error.TransicionInvalidaException;
import cl.duoc.gymflow.reservations.mensajeria.PublicadorNotificaciones;
import cl.duoc.gymflow.reservations.repository.ReservaFiltros;
import cl.duoc.gymflow.reservations.repository.ReservaRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reglas de la reserva.
 * <p>
 * <b>Cupos y consistencia entre servicios.</b> El cupo vive en ms-gymflow-catalog y la reserva en esta BD;
 * no hay una transacción que abarque ambos. Por eso, al CONFIRMAR o al CANCELAR una reserva confirmada:
 * <ol>
 *   <li>se valida la transición (si no es válida, 409 sin tocar el catálogo);</li>
 *   <li>se toma o devuelve el cupo en el catálogo (operación idempotente por reserva);</li>
 *   <li>se guarda el nuevo estado en una transacción local;</li>
 *   <li>si el guardado falla, se <b>compensa</b> con la operación inversa, pero solo si el paso 2 cambió algo
 *       ({@code changed: true}). Así, si dos instructores confirman a la vez, el que pierde no devuelve el
 *       cupo que tomó el otro.</li>
 * </ol>
 * La llamada HTTP queda fuera de la transacción para no mantener una conexión a la BD abierta mientras
 * se espera al catálogo.
 * <p>
 * <b>Notificaciones (EP2).</b> Una vez guardada la confirmación, se publican en RabbitMQ el email al socio y el
 * ticket de check-in al instructor ({@link PublicadorNotificaciones}). Si RabbitMQ falla, la reserva sigue confirmada.
 */
@Service
public class ReservaService {

    private static final Logger log = LoggerFactory.getLogger(ReservaService.class);

    private final ReservaRepository reservaRepository;
    private final CatalogClient catalogClient;
    private final PublicadorNotificaciones notificaciones;
    private final TransactionTemplate transaccion;
    private final TransactionTemplate lectura;

    public ReservaService(ReservaRepository reservaRepository, CatalogClient catalogClient,
                          PublicadorNotificaciones notificaciones, PlatformTransactionManager transactionManager) {
        this.reservaRepository = reservaRepository;
        this.catalogClient = catalogClient;
        this.notificaciones = notificaciones;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.lectura = new TransactionTemplate(transactionManager);
        this.lectura.setReadOnly(true);
    }

    public List<ReservaResponse> listar(EstadoReserva estado, String miembroId, Long claseId,
                                        Instant desde, Instant hasta) {
        if (desde != null && hasta != null && !desde.isBefore(hasta)) {
            throw new SolicitudInvalidaException("El filtro 'from' debe ser anterior a 'to'");
        }
        return lectura.execute(s -> reservaRepository
                .findAll(ReservaFiltros.con(estado, miembroId, claseId, desde, hasta), Sort.by(Sort.Direction.DESC, "creadoEn"))
                .stream().map(ReservaResponse::desde).toList());
    }

    public ReservaResponse obtener(Long id) {
        return ReservaResponse.desde(buscar(id));
    }

    /**
     * @param emailUsuario email de quien hace la petición (cabecera {@code X-User-Email}). Si el socio reserva para
     *                     sí mismo, es su email; si un Admin o Instructor reserva para otro, se usa el del cuerpo.
     */
    public ReservaResponse crear(ReservaRequest datos, Autor autor, String emailUsuario) {
        ClaseCatalogo clase = catalogClient.obtenerClase(datos.classId());
        if (clase.startsAt() == null || !clase.startsAt().isAfter(Instant.now())) {
            throw new ConflictoException("La clase '" + clase.name() + "' ya comenzó; no se puede reservar");
        }
        String miembroId = datos.memberId().trim();
        if (reservaRepository.existsByMiembroIdAndClaseIdAndEstadoIn(miembroId, clase.id(), EstadoReserva.activos())) {
            throw new ConflictoException("El socio ya tiene una reserva activa en la clase '" + clase.name() + "'");
        }
        String miembroEmail = miembroId.equals(autor.id()) ? emailUsuario : datos.memberEmail();
        Reserva reserva = new Reserva(miembroId, datos.memberName().trim(), vacioANull(miembroEmail), clase.id(),
                clase.name(), clase.startsAt(), autor);
        return ReservaResponse.desde(reservaRepository.save(reserva));
    }

    private static String vacioANull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    public ReservaResponse cambiarEstado(Long id, EstadoReserva nuevo, Autor autor) {
        Reserva leida = buscar(id);
        EstadoReserva previo = leida.getEstado();
        if (!previo.puedePasarA(nuevo)) {
            throw new TransicionInvalidaException(previo.motivoRechazo(nuevo));
        }

        Runnable compensacion = moverCupo(leida, previo, nuevo);

        ReservaResponse guardada;
        try {
            guardada = transaccion.execute(s -> {
                Reserva reserva = buscar(id);
                if (reserva.getVersion() != leida.getVersion()) {
                    throw new ConflictoException("La reserva cambió mientras se procesaba; vuelve a cargarla e intenta de nuevo");
                }
                reserva.cambiarEstado(nuevo, autor);
                reservaRepository.saveAndFlush(reserva);
                return ReservaResponse.desde(reserva);
            });
        } catch (RuntimeException fallo) {
            compensar(compensacion, id, fallo);
            throw fallo;
        }

        // Fuera de la transacción y solo si se guardó: nunca se notifica una confirmación que no quedó registrada.
        if (nuevo == EstadoReserva.CONFIRMADA) {
            notificaciones.reservaConfirmada(guardada);
        }
        return guardada;
    }

    /**
     * Toma o devuelve el cupo en el catálogo según la transición, y retorna la operación inversa
     * (o null si no hay nada que deshacer).
     */
    private Runnable moverCupo(Reserva reserva, EstadoReserva previo, EstadoReserva nuevo) {
        Long claseId = reserva.getClaseId();
        Long reservaId = reserva.getId();
        if (nuevo == EstadoReserva.CONFIRMADA) {
            CupoResultado resultado = catalogClient.tomarCupo(claseId, reservaId);
            return resultado.changed() ? () -> catalogClient.devolverCupo(claseId, reservaId) : null;
        }
        if (nuevo == EstadoReserva.CANCELADA && previo.ocupaCupo()) {
            CupoResultado resultado = catalogClient.devolverCupo(claseId, reservaId);
            return resultado.changed() ? () -> catalogClient.tomarCupo(claseId, reservaId) : null;
        }
        return null;
    }

    private static void compensar(Runnable compensacion, Long reservaId, RuntimeException fallo) {
        if (compensacion == null) {
            return;
        }
        try {
            compensacion.run();
            log.info("Reserva {}: se deshizo el movimiento de cupo porque no se pudo guardar el nuevo estado", reservaId);
        } catch (RuntimeException errorCompensando) {
            fallo.addSuppressed(errorCompensando);
            log.error("Reserva {}: no se pudo deshacer el movimiento de cupo; revisar el cupo de la clase a mano",
                    reservaId, errorCompensando);
        }
    }

    private Reserva buscar(Long id) {
        return reservaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("La reserva " + id + " no existe"));
    }
}
