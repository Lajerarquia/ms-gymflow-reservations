package cl.duoc.gymflow.reservations.controller;

import cl.duoc.gymflow.reservations.entity.Autor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Identidad que envía el BFF. Este microservicio no valida JWT: no está expuesto a internet y solo lo
 * llama el BFF, que ya validó el token y autorizó por rol.
 */
final class CabecerasUsuario {

    static final String ID = "X-User-Id";
    static final String NOMBRE = "X-User-Name";

    private CabecerasUsuario() {
    }

    /** El nombre llega URL-encoded en UTF-8 (las cabeceras HTTP no admiten tildes con seguridad). */
    static Autor autor(String id, String nombreCodificado) {
        String nombre = decodificar(nombreCodificado);
        return new Autor(id.trim(), nombre == null || nombre.isBlank() ? id.trim() : nombre.trim());
    }

    private static String decodificar(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return URLDecoder.decode(valor, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malCodificado) {
            return valor;
        }
    }
}
