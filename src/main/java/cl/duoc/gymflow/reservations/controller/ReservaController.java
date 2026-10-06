package cl.duoc.gymflow.reservations.controller;

import cl.duoc.gymflow.reservations.dto.CambioEstadoRequest;
import cl.duoc.gymflow.reservations.dto.ReservaRequest;
import cl.duoc.gymflow.reservations.dto.ReservaResponse;
import cl.duoc.gymflow.reservations.entity.EstadoReserva;
import cl.duoc.gymflow.reservations.service.ReservaService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reservas. Los permisos por rol y las reglas "solo sus reservas" del Socio ya los aplicó el BFF;
 * aquí se aplican las reglas del negocio (estados, cupos, duplicados).
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservaController {

    private final ReservaService reservaService;

    public ReservaController(ReservaService reservaService) {
        this.reservaService = reservaService;
    }

    /**
     * Filtros opcionales: {@code status}, {@code memberId}, {@code classId}, {@code from} y {@code to}
     * (ISO-8601, sobre la fecha de creación). Ordenadas de la más reciente a la más antigua.
     */
    @GetMapping
    public List<ReservaResponse> listar(@RequestParam(required = false) EstadoReserva status,
                                        @RequestParam(required = false) String memberId,
                                        @RequestParam(required = false) Long classId,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to) {
        return reservaService.listar(status, memberId, classId, from, to);
    }

    @GetMapping("/{id}")
    public ReservaResponse obtener(@PathVariable Long id) {
        return reservaService.obtener(id);
    }

    @PostMapping
    public ResponseEntity<ReservaResponse> crear(@Valid @RequestBody ReservaRequest datos,
                                                 @RequestHeader(CabecerasUsuario.ID) String usuarioId,
                                                 @RequestHeader(value = CabecerasUsuario.NOMBRE, required = false) String usuarioNombre,
                                                 @RequestHeader(value = CabecerasUsuario.EMAIL, required = false) String usuarioEmail) {
        ReservaResponse creada = reservaService.crear(datos, CabecerasUsuario.autor(usuarioId, usuarioNombre), usuarioEmail);
        return ResponseEntity.created(URI.create("/api/reservations/" + creada.id())).body(creada);
    }

    @PutMapping("/{id}/status")
    public ReservaResponse cambiarEstado(@PathVariable Long id, @Valid @RequestBody CambioEstadoRequest datos,
                                         @RequestHeader(CabecerasUsuario.ID) String usuarioId,
                                         @RequestHeader(value = CabecerasUsuario.NOMBRE, required = false) String usuarioNombre) {
        return reservaService.cambiarEstado(id, datos.status(), CabecerasUsuario.autor(usuarioId, usuarioNombre));
    }
}
