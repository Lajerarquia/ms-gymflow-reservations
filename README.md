# ms-gymflow-reservations

Reservas de GymFlow: crea reservas, controla sus estados y coordina los cupos con ms-gymflow-catalog.
Persiste en Amazon RDS PostgreSQL. No está expuesto a internet: solo lo llama el BFF, que ya validó el JWT
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
| `POST /api/reservations` `{"classId", "memberId", "memberName", "memberEmail"?}` | Crea en estado RESERVADA (201) |
| `PUT /api/reservations/{id}/status` `{"status": "CONFIRMADA"}` | Cambia el estado |

Reserva:
```json
{
  "id": 12, "classId": 7, "className": "Spinning 45", "classStartsAt": "2026-10-01T11:00:00Z",
  "memberId": "<oid>", "memberName": "José Pérez", "memberEmail": "jose@...", "status": "CONFIRMADA",
  "createdBy": "José Pérez", "createdById": "<oid>", "createdAt": "2026-09-29T19:30:00Z",
  "updatedBy": "Camila Rojas", "updatedById": "<oid>", "updatedAt": "2026-09-29T19:45:00Z"
}
```
`createdBy/updatedBy` alimentan el timeline de /audit en la EP1. En la EP2 vendrá de ms-gymflow-audit vía Kafka.

`memberEmail` (EP2) es el destinatario de las notificaciones. Si el socio reserva para sí mismo se toma de
`X-User-Email` (su token) y se ignora el del cuerpo; si Admin o Instructor reservan para otro socio, se usa el del
cuerpo (opcional). Las reservas anteriores a la EP2 no lo tienen.

Errores: mismo JSON que el BFF y catalog (`{"timestamp","status","error","message","path"}`).
503 si el catálogo no responde; la reserva no cambia.

## Notificaciones por RabbitMQ (EP2)

Al quedar guardada una reserva **CONFIRMADA** se publican dos comandos (los consume ms-gymflow-notify):

| Comando (`type`) | Exchange | Routing key | Cola |
|---|---|---|---|
| `EMAIL_RESERVA_CONFIRMADA` | `cmd.direct` | `email.send` | `q.cmd.email` |
| `CHECKIN_TICKET_CREADO` | `cmd.topic` | `checkin.ticket.created` (calza con `checkin.#`) | `q.cmd.checkin` |

Envelope JSON común: `{"type","eventId","timestamp","traceId","correlationId","payload"}`. `eventId` (UUID) es la
base de la idempotencia del consumidor; el email y el ticket comparten `correlationId`; `traceId` viene de la cabecera
`X-Trace-Id` (o se genera uno) y se devuelve en la respuesta HTTP.

- Este servicio declara solo los exchanges. Colas, DLQ y bindings los declara ms-gymflow-notify.
- Se publica **después** de guardar y en segundo plano: la respuesta no espera a RabbitMQ. Si RabbitMQ no responde,
  el RabbitTemplate reintenta 3 veces y luego registra el error; **la reserva sigue confirmada**. Mejora pendiente:
  patrón outbox para no perder la notificación en ese caso.
- Publisher confirms y `mandatory`: el log muestra si RabbitMQ rechazó un mensaje o si no había cola de destino.
- RabbitMQ no forma parte de `/actuator/health`, para que una caída del broker no deje "unhealthy" a este servicio
  (y con él, al BFF).

## Ejecutar

```bash
# Local con H2 (necesita catalog corriendo; RabbitMQ local con infra/mq/compose.yml)
CATALOG_URL=http://localhost:8082 RABBITMQ_PASSWORD=<contraseña> ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Con Amazon RDS PostgreSQL: completar DB_URL, DB_USERNAME y DB_PASSWORD en .env (ignorado por git)
cp .env.example .env
```

## Pruebas

```bash
./mvnw test
```
Usan H2 en memoria, el catálogo simulado y el `RabbitTemplate` simulado (no necesitan broker).
`CatalogClientTest` verifica las rutas y cuerpos exactos del contrato; `NotificacionesTest`, qué se publica al
confirmar (exchange, routing key, envelope, propiedades AMQP), que nada se publica en otras transiciones y que
la reserva queda confirmada aunque RabbitMQ esté caído.
