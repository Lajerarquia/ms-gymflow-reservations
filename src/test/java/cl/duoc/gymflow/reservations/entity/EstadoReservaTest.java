package cl.duoc.gymflow.reservations.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.duoc.gymflow.reservations.error.TransicionInvalidaException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tabla completa de transiciones: las 36 combinaciones (estado actual × estado nuevo).
 */
class EstadoReservaTest {

    private static final Map<EstadoReserva, Set<EstadoReserva>> PERMITIDAS = Map.of(
            EstadoReserva.RESERVADA, EnumSet.of(EstadoReserva.CONFIRMADA, EstadoReserva.CANCELADA),
            EstadoReserva.CONFIRMADA, EnumSet.of(EstadoReserva.EN_ESPERA, EstadoReserva.EN_CLASE, EstadoReserva.CANCELADA),
            EstadoReserva.EN_ESPERA, EnumSet.of(EstadoReserva.EN_CLASE, EstadoReserva.CANCELADA),
            EstadoReserva.EN_CLASE, EnumSet.of(EstadoReserva.COMPLETADA),
            EstadoReserva.COMPLETADA, EnumSet.noneOf(EstadoReserva.class),
            EstadoReserva.CANCELADA, EnumSet.noneOf(EstadoReserva.class));

    @Test
    void todasLasCombinaciones() {
        for (EstadoReserva actual : EstadoReserva.values()) {
            for (EstadoReserva nuevo : EstadoReserva.values()) {
                assertThat(actual.puedePasarA(nuevo))
                        .as("%s → %s", actual, nuevo)
                        .isEqualTo(PERMITIDAS.get(actual).contains(nuevo));
            }
        }
    }

    @Test
    void enClaseSinConfirmar_tieneElMensajeDeLaRegla() {
        assertThat(EstadoReserva.RESERVADA.motivoRechazo(EstadoReserva.EN_CLASE))
                .isEqualTo("No se puede pasar a EN_CLASE sin CONFIRMAR");
    }

    @Test
    void soloLosEstadosConfirmadosOcupanCupo() {
        for (EstadoReserva estado : EstadoReserva.values()) {
            assertThat(estado.ocupaCupo()).as("%s", estado)
                    .isEqualTo(estado == EstadoReserva.CONFIRMADA || estado == EstadoReserva.EN_ESPERA);
        }
    }

    @Test
    void cancelarSoloEsPosibleAntesDeLaClase() {
        for (EstadoReserva estado : EstadoReserva.values()) {
            boolean antesDeLaClase = estado == EstadoReserva.RESERVADA || estado == EstadoReserva.CONFIRMADA
                    || estado == EstadoReserva.EN_ESPERA;
            assertThat(estado.puedePasarA(EstadoReserva.CANCELADA)).as("%s → CANCELADA", estado)
                    .isEqualTo(antesDeLaClase);
        }
    }

    @Test
    void reserva_registraQuienYCuandoCambiaElEstado() throws InterruptedException {
        Reserva reserva = new Reserva("oid-socio", "José Pérez", 7L, "Spinning 45",
                Instant.now().plusSeconds(3600), new Autor("oid-socio", "José Pérez"));
        Instant creada = reserva.getActualizadoEn();
        Thread.sleep(5);

        reserva.cambiarEstado(EstadoReserva.CONFIRMADA, new Autor("oid-inst", "Camila Rojas"));

        assertThat(reserva.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(reserva.getCreadoPor()).isEqualTo("José Pérez");
        assertThat(reserva.getActualizadoPor()).isEqualTo("Camila Rojas");
        assertThat(reserva.getActualizadoPorId()).isEqualTo("oid-inst");
        assertThat(reserva.getActualizadoEn()).isAfter(creada);
    }

    @Test
    void reserva_rechazaTransicionInvalida_sinCambiarNada() {
        Autor socio = new Autor("oid-socio", "José Pérez");
        Reserva reserva = new Reserva("oid-socio", "José Pérez", 7L, "Spinning 45",
                Instant.now().plusSeconds(3600), socio);

        assertThatThrownBy(() -> reserva.cambiarEstado(EstadoReserva.EN_CLASE, new Autor("oid-inst", "Camila")))
                .isInstanceOf(TransicionInvalidaException.class)
                .hasMessage("No se puede pasar a EN_CLASE sin CONFIRMAR");
        assertThat(reserva.getEstado()).isEqualTo(EstadoReserva.RESERVADA);
        assertThat(reserva.getActualizadoPor()).isEqualTo("José Pérez");
    }
}
