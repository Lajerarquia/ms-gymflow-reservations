package cl.duoc.gymflow.reservations.entity;

/**
 * Quién hace la operación, según las cabeceras que envía el BFF ({@code X-User-Id} y {@code X-User-Name}).
 *
 * @param id     {@code oid} del usuario en Azure AD
 * @param nombre nombre para mostrar (ya decodificado desde UTF-8)
 */
public record Autor(String id, String nombre) {
}
