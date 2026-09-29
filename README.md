# ms-gymflow-reservations

Reservas de GymFlow: crea reservas, controla sus estados y coordina los cupos con ms-gymflow-catalog.
Persiste en Oracle Autonomous DB. No está expuesto a internet: solo lo llama el BFF, que ya validó el JWT
y autorizó por rol, y que envía la identidad en `X-User-Id`, `X-User-Name` (URL-encoded UTF-8) y `X-User-Email`.

## Estados

```
RESERVADA ──> CONFIRMADA ──> EN_ESPERA ──> EN_CLASE ──> COMPLETADA
    │             │  └───────────────────────^
    └─────────────┴──────────┴──> CANCELADA
```

| Transición | Efecto en el catálogo |
|---|---|
| → CONFIRMADA | `take-slot`: descuenta un cupo. Sin cupos → 409 |
| CONFIRMADA / EN_ESPERA → CANCELADA | `release-slot`: devuelve el cupo |
| RESERVADA → CANCELADA | ninguno (aún no ocupaba cupo) |
| RESERVADA → EN_CLASE | **409 "No se puede pasar a EN_CLASE sin CONFIRMAR"** |

Además: no se puede reservar una clase que ya comenzó (409) ni tener dos reservas activas en la misma clase (409).

## Consistencia con el catálogo

El cupo vive en catalog y la reserva aquí; no hay una transacción que abarque ambos. Al confirmar o cancelar:

1. se valida la transición (si no es válida, 409 sin tocar el catálogo);
2. se toma o devuelve el cupo (idempotente por `reservationId`);
3. se guarda el nuevo estado; `@Version` detecta si otra persona cambió la reserva entremedio;
4. si el guardado falla, se deshace el paso 2, **solo si el catálogo respondió `changed: true`**.

`CompensacionCupoTest` prueba los tres casos, incluido el de dos instructores confirmando a la vez.

## API

| Método y ruta | Descripción |
|---|---|
| `GET /api/reservations?status=&memberId=&classId=&from=&to=` | Lista, más recientes primero. `from`/`to` sobre `createdAt` |
| `GET /api/reservations/{id}` | Detalle |
| `POST /api/reservations` `{"classId", "memberId", "memberName"}` | Crea en estado RESERVADA (201) |
| `PUT /api/reservations/{id}/status` `{"status": "CONFIRMADA"}` | Cambia el estado |

Reserva:
```json
{
  "id": 12, "classId": 7, "className": "Spinning 45", "classStartsAt": "2026-10-01T11:00:00Z",
  "memberId": "<oid>", "memberName": "José Pérez", "status": "CONFIRMADA",
  "createdBy": "José Pérez", "createdById": "<oid>", "createdAt": "2026-09-29T19:30:00Z",
  "updatedBy": "Camila Rojas", "updatedById": "<oid>", "updatedAt": "2026-09-29T19:45:00Z"
}
```
`createdBy/updatedBy` alimentan el timeline de /audit en la EP1. En la EP2 vendrá de ms-gymflow-audit vía Kafka.

Errores: mismo JSON que el BFF y catalog (`{"timestamp","status","error","message","path"}`).
503 si el catálogo no responde; la reserva no cambia.

## Ejecutar

```bash
# Local con H2 (necesita catalog corriendo)
CATALOG_URL=http://localhost:8082 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Con Oracle: descomprimir el wallet en ./wallet (ignorado por git) y completar .env
cp .env.example .env
```

## Pruebas

```bash
./mvnw test
```
Usan H2 en memoria y el catálogo simulado; `CatalogClientTest` verifica las rutas y cuerpos exactos del contrato.
