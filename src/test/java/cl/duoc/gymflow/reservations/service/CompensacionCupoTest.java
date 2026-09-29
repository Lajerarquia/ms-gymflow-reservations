package cl.duoc.gymflow.reservations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.duoc.gymflow.reservations.client.CupoResultado;
import cl.duoc.gymflow.reservations.entity.Autor;
import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.entity.Reserva;
import cl.duoc.gymflow.reservations.error.ConflictoException;
import cl.duoc.gymflow.reservations.support.PruebaApiBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Qué pasa si el cupo ya se movió en el catálogo pero el nuevo estado no se puede guardar aquí.
 * Se simula el caso real: otra persona cambia la misma reserva justo mientras se esperaba al catálogo.
 */
class CompensacionCupoTest extends PruebaApiBase {

    private static final Autor INSTRUCTOR = new Autor("oid-instructor", "Camila Rojas");
    private static final Autor OTRO_INSTRUCTOR = new Autor("oid-otro", "Diego Soto");

    @Autowired
    private ReservaService reservaService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void siNoSePuedeGuardarLaConfirmacion_seDevuelveElCupoQueSeTomo() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        when(catalogClient.tomarCupo(CLASE, id)).thenAnswer(inv -> {
            otroUsuarioCambia(id, EstadoReserva.CANCELADA);
            return new CupoResultado(CLASE, id, 9, true);
        });

        assertThatThrownBy(() -> reservaService.cambiarEstado(id, EstadoReserva.CONFIRMADA, INSTRUCTOR))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("cambió mientras se procesaba");

        verify(catalogClient).devolverCupo(CLASE, id);
        assertThat(estadoEnBd(id)).isEqualTo(EstadoReserva.CANCELADA);
    }

    /**
     * Dos instructores confirman a la vez: el catálogo descuenta una sola vez (el segundo recibe
     * {@code changed: false}). El que pierde no debe devolver el cupo que ganó el otro.
     */
    @Test
    void siElCupoNoLoTomoEstaOperacion_noSeDevuelve() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        when(catalogClient.tomarCupo(CLASE, id)).thenAnswer(inv -> {
            otroUsuarioCambia(id, EstadoReserva.CONFIRMADA);
            return new CupoResultado(CLASE, id, 9, false);
        });

        assertThatThrownBy(() -> reservaService.cambiarEstado(id, EstadoReserva.CONFIRMADA, INSTRUCTOR))
                .isInstanceOf(ConflictoException.class);

        verify(catalogClient, never()).devolverCupo(anyLong(), anyLong());
        assertThat(estadoEnBd(id)).isEqualTo(EstadoReserva.CONFIRMADA);
    }

    @Test
    void siNoSePuedeGuardarLaCancelacion_seVuelveATomarElCupoDevuelto() throws Exception {
        long id = crearReserva("oid-socio", "José Pérez");
        reservaService.cambiarEstado(id, EstadoReserva.CONFIRMADA, INSTRUCTOR);
        when(catalogClient.devolverCupo(CLASE, id)).thenAnswer(inv -> {
            otroUsuarioCambia(id, EstadoReserva.EN_ESPERA);
            return new CupoResultado(CLASE, id, 10, true);
        });

        assertThatThrownBy(() -> reservaService.cambiarEstado(id, EstadoReserva.CANCELADA, INSTRUCTOR))
                .isInstanceOf(ConflictoException.class);

        // una vez al confirmar y otra al compensar
        verify(catalogClient, times(2)).tomarCupo(CLASE, id);
        assertThat(estadoEnBd(id)).isEqualTo(EstadoReserva.EN_ESPERA);
    }

    private void otroUsuarioCambia(long id, EstadoReserva estado) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            Reserva reserva = reservaRepository.findById(id).orElseThrow();
            reserva.cambiarEstado(estado, OTRO_INSTRUCTOR);
        });
    }

    private EstadoReserva estadoEnBd(long id) {
        return reservaRepository.findById(id).orElseThrow().getEstado();
    }
}
