package cl.duoc.gymflow.reservations.entity;

import cl.duoc.gymflow.reservations.error.TransicionInvalidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Reserva de un socio en una clase.
 * <p>
 * Guarda quién la creó y quién la modificó por última vez (id = {@code oid} de Azure AD y nombre), con sus
 * fechas. Con eso la pantalla /audit arma el timeline en la EP1; en la EP2 vendrá de ms-gymflow-audit vía Kafka.
 * <p>
 * El nombre y el inicio de la clase se copian al crear la reserva, para que el historial siga siendo legible
 * aunque la clase cambie o se elimine del catálogo.
 */
@Entity
@Table(name = "RESERVA", indexes = {
        @Index(name = "IX_RESERVA_MIEMBRO", columnList = "miembro_id"),
        @Index(name = "IX_RESERVA_CLASE", columnList = "clase_id"),
        @Index(name = "IX_RESERVA_ESTADO", columnList = "estado")
})
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "miembro_id", nullable = false, length = 64)
    private String miembroId;

    @Column(name = "miembro_nombre", nullable = false, length = 150)
    private String miembroNombre;

    @Column(name = "clase_id", nullable = false)
    private Long claseId;

    @Column(name = "clase_nombre", nullable = false, length = 100)
    private String claseNombre;

    @Column(name = "clase_inicio", nullable = false)
    private Instant claseInicio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoReserva estado;

    @Column(name = "creado_por", nullable = false, length = 150)
    private String creadoPor;

    @Column(name = "creado_por_id", nullable = false, length = 64)
    private String creadoPorId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_por", nullable = false, length = 150)
    private String actualizadoPor;

    @Column(name = "actualizado_por_id", nullable = false, length = 64)
    private String actualizadoPorId;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    /** Bloqueo optimista: si dos personas cambian la misma reserva a la vez, la segunda recibe 409. */
    @Version
    @Column(name = "num_version", nullable = false)
    private long version;

    protected Reserva() {
        // requerido por JPA
    }

    public Reserva(String miembroId, String miembroNombre, Long claseId, String claseNombre, Instant claseInicio,
                   Autor autor) {
        Instant ahora = Instant.now();
        this.miembroId = miembroId;
        this.miembroNombre = miembroNombre;
        this.claseId = claseId;
        this.claseNombre = claseNombre;
        this.claseInicio = claseInicio;
        this.estado = EstadoReserva.RESERVADA;
        this.creadoPor = autor.nombre();
        this.creadoPorId = autor.id();
        this.creadoEn = ahora;
        this.actualizadoPor = autor.nombre();
        this.actualizadoPorId = autor.id();
        this.actualizadoEn = ahora;
    }

    /** Aplica la transición si está permitida; si no, lanza 409 con el motivo. */
    public void cambiarEstado(EstadoReserva nuevo, Autor autor) {
        if (!estado.puedePasarA(nuevo)) {
            throw new TransicionInvalidaException(estado.motivoRechazo(nuevo));
        }
        this.estado = nuevo;
        this.actualizadoPor = autor.nombre();
        this.actualizadoPorId = autor.id();
        this.actualizadoEn = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMiembroId() {
        return miembroId;
    }

    public String getMiembroNombre() {
        return miembroNombre;
    }

    public Long getClaseId() {
        return claseId;
    }

    public String getClaseNombre() {
        return claseNombre;
    }

    public Instant getClaseInicio() {
        return claseInicio;
    }

    public EstadoReserva getEstado() {
        return estado;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public String getCreadoPorId() {
        return creadoPorId;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public String getActualizadoPorId() {
        return actualizadoPorId;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }

    public long getVersion() {
        return version;
    }
}
