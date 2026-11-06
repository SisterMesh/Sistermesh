# SisterMesh Offline Sync API Contract

Version 1 of the backend sync event contract. This document describes the server API only; it does not assume that an Android Room schema or Bluetooth mesh implementation exists.

## Authentication and device registration

All routes require the existing JWT bearer access token. The server derives account ID and role from that token and current database state. Do not send a `userId`, `role`, or `facilityId` in an event.

1. Register a stable client-generated UUID:

   `POST /api/sync/devices`

   ```json
   {
     "clientDeviceId": "795f4700-f6fb-4824-8cfe-017527077f58",
     "name": "My Android phone"
   }
   ```

   Keep `clientDeviceId` stable and store the returned server `deviceId`. Registering the same active device again returns its existing record. A revoked device ID cannot be reactivated; register a new ID after an account owner has secured the device.

2. List owned devices with `GET /api/sync/devices`.
3. Revoke an owned device with `DELETE /api/sync/devices/{deviceId}`. Revocation is idempotent and prevents subsequent upload and download requests.

The optional `publicKey` registration field is informational; it is not proof of possession and does not replace JWT authentication.

## Event envelope, schema version 1

Every batch event has this envelope:

```json
{
  "eventId": "53ad2d1d-fd51-4b11-b429-76c808a3ae2f",
  "eventType": "request.create",
  "entityId": null,
  "originDeviceId": "795f4700-f6fb-4824-8cfe-017527077f58",
  "localCreatedAt": "2026-11-06T12:30:00.000Z",
  "payloadSchemaVersion": 1,
  "correlationId": "abfcdcb7-3b31-48d2-bcb8-b29be3b8564a",
  "payload": {
    "requestedBloodGroup": "O_NEGATIVE",
    "urgency": "CRITICAL",
    "units": 2,
    "expiresAt": "2026-11-06T16:30:00.000Z"
  }
}
```

`eventId` is a client-generated UUID and must remain unchanged across retries. `originDeviceId` must equal the stable `clientDeviceId` registered for the authenticated upload device. `localCreatedAt` is retained for audit only; server ordering and conflict checks do not depend on device clocks. `entityId` is `null` for a new request and the server UUID for other operations. The create result returns the server entity ID.

Only these event types are supported:

| Event type | `entityId` | Payload |
|---|---|---|
| `request.create` | `null` | `requestedBloodGroup`, `urgency`, `units`, optional `expiresAt` |
| `request.update` | Request UUID | Request fields to change plus required `expectedUpdatedAt` from the last downloaded server snapshot |
| `donor-response.accept` | Request UUID | Empty object |
| `donor-response.decline` | Request UUID | Empty object |
| `donor.withdraw` | Request UUID | Empty object |
| `request.cancel` | Request UUID | Empty object |
| `donation.confirm` | Request UUID | `donorProfileId`, `receiptHash`, optional `consentedAt` |

The backend rejects undeclared envelope or payload fields. This also prevents clients from assigning another user, role, or facility. Existing Phase 3 role, verification, facility, lifecycle, and idempotency checks apply to each operation.

## Batch upload

`POST /api/sync/devices/{deviceId}/batches`

```json
{
  "clientBatchId": "633b770a-2ec9-4677-8749-3672d255f195",
  "events": [
    {
      "eventId": "53ad2d1d-fd51-4b11-b429-76c808a3ae2f",
      "eventType": "request.create",
      "entityId": null,
      "originDeviceId": "795f4700-f6fb-4824-8cfe-017527077f58",
      "localCreatedAt": "2026-11-06T12:30:00.000Z",
      "payloadSchemaVersion": 1,
      "payload": {
        "requestedBloodGroup": "O_NEGATIVE",
        "urgency": "CRITICAL",
        "units": 2
      }
    }
  ]
}
```

Use the same `clientBatchId` and event IDs for retries. The same batch ID with different content returns HTTP 409. Batches contain 1–100 events, up to 1 MiB total; each event is limited to 64 KiB. Each item is validated and processed independently, so one invalid event does not block valid siblings.

Example result:

```json
{
  "batchId": "ebd7d59d-2c1c-4daa-9e83-04372e20a680",
  "clientBatchId": "633b770a-2ec9-4677-8749-3672d255f195",
  "status": "PARTIALLY_COMPLETED",
  "receivedCount": 2,
  "processedCount": 1,
  "rejectedCount": 1,
  "events": [
    {
      "eventId": "53ad2d1d-fd51-4b11-b429-76c808a3ae2f",
      "status": "PROCESSED",
      "entityId": "3ab6c6dd-0d0a-43dc-a5b7-b840b6226670"
    },
    {
      "eventId": "cf4186a5-409b-48af-b967-d98163a6455b",
      "status": "CONFLICT",
      "errorCode": "REQUEST_CLOSED",
      "explanation": "This emergency request is no longer accepting responses."
    }
  ]
}
```

Event result statuses:

- `RECEIVED`: another request is processing the event or a temporary server failure requires retry.
- `PROCESSED`: the operation succeeded or its idempotent result already existed.
- `REJECTED`: invalid payload, unsupported schema/event, or authorization/business-rule rejection.
- `CONFLICT`: the operation conflicts with current server state, including closed requests, stale request updates, duplicate actions, or event-ID reuse.

`PENDING` is not returned; processing is synchronous. Processing results and event payloads are retained. Conflicts are not silently overwritten or resolved using last-write-wins. Inspect retained conflicts with `GET /api/sync/devices/{deviceId}/conflicts`; resolve them through an authorised user workflow, then submit a new event only when the user has decided how to proceed.

Common error codes include `INVALID_EVENT`, `INVALID_PAYLOAD`, `UNSUPPORTED_EVENT_TYPE`, `UNSUPPORTED_SCHEMA_VERSION`, `UNTRUSTED_FIELDS`, `DEVICE_ORIGIN_MISMATCH`, `REQUEST_EXPIRED`, `REQUEST_CLOSED`, `VERSION_CONFLICT`, `DUPLICATE_OPERATION`, `ENTITY_NOT_FOUND`, `NOT_AUTHORIZED`, `EVENT_ID_REUSE`, and `RETRYABLE_PROCESSING_ERROR`.

## Incremental download and checkpoints

The server maintains a per-device checkpoint. Request activity cursors are server-generated and allocated under a serialized database counter, so committed changes cannot appear later with an earlier cursor. Each page is bounded to a high-water cursor captured before its query, preventing concurrent writes from being skipped when the checkpoint advances.

1. Read `GET /api/sync/devices/{deviceId}/changes?limit=50` (limit 1–100; default 50).
2. Persist the entire `changes` page locally.
3. Only after local persistence succeeds, acknowledge exactly the returned `nextCursor`:

   `POST /api/sync/devices/{deviceId}/checkpoint`

   ```json
   { "cursor": "123" }
   ```

4. Request the next page. If interrupted before acknowledgement, request the page again; the server continues from the saved checkpoint. The acknowledgement cannot skip forward to a cursor the server did not issue.

Example page:

```json
{
  "checkpoint": "120",
  "nextCursor": "123",
  "hasMore": true,
  "changes": [
    {
      "cursor": "121",
      "eventType": "DONOR_ACCEPTED",
      "fromStatus": null,
      "toStatus": null,
      "occurredAt": "2026-11-06T12:31:00.000Z",
      "request": {
        "id": "3ab6c6dd-0d0a-43dc-a5b7-b840b6226670",
        "requestedBloodGroup": "O_NEGATIVE",
        "urgency": "CRITICAL",
        "units": 2,
        "status": "OPEN",
        "expiresAt": null,
        "facility": {
          "id": "5acc0951-5064-4aa3-88ef-a3c2ce14a5e2",
          "name": "Example Facility",
          "locality": "Central",
          "state": "Example State"
        }
      }
    }
  ]
}
```

The activity cursor, not the client timestamp, orders changes. Health workers receive only activity for their verified active facility. Donors receive only compatible request snapshots and requests for which they have a response; the feed does not include another donor's identity, contact details, or response payload.

## Conflict and relay limitations

`request.update` requires `expectedUpdatedAt`; a stale update is retained as a conflict rather than overwriting newer server state. Request acceptance, cancellation, expiry, withdrawal, and donation confirmation continue to use Phase 3 transaction and lifecycle checks. Event IDs are globally unique; reusing one with different content or ownership records the incoming conflict separately.

For security, this API currently accepts only events whose `originDeviceId` matches the registered device making the JWT-authenticated upload. A Bluetooth-relayed event originating on another device is retained as rejected and is not applied under the uploader's identity. Supporting authenticated mesh relay requires a future trust/consent contract; the backend does not impersonate the originating user.

Room storage, Android repositories and queues, Bluetooth transport, mesh relay authentication, and conflict-resolution UI are not implemented in this phase.
