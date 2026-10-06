package cl.duoc.gymflow.reservations.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de POST /api/reservations. Si quien reserva es un Socio, el BFF ya reemplazó {@code memberId} y
 * {@code memberName} por los suyos; si es Admin o Instructor, los indica para el socio que corresponda.
 * <p>
 * {@code memberEmail} (opcional) solo se usa cuando Admin o Instructor reservan para otro socio. Si el socio
 * reserva para sí mismo, se usa su email de la cabecera {@code X-User-Email} (viene del token) y se ignora el del cuerpo.
 */
public record ReservaRequest(
        @NotNull(message = "La clase es obligatoria")
        Long classId,

        @NotBlank(message = "El socio es obligatorio")
        @Size(max = 64, message = "El id del socio no puede superar 64 caracteres")
        String memberId,

        @NotBlank(message = "El nombre del socio es obligatorio")
        @Size(max = 150, message = "El nombre del socio no puede superar 150 caracteres")
        String memberName,

        @Email(message = "El email del socio no es válido")
        @Size(max = 254, message = "El email del socio no puede superar 254 caracteres")
        String memberEmail) {
}
