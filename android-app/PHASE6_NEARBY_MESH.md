# SisterMesh Phase 6: nearby communication

## Transport and trust

SisterMesh uses Google Play services Nearby Connections with `P2P_CLUSTER`. Nearby Connections selects among available local radios and requires no internet for peer-to-peer exchange. It is not itself a multi-hop mesh: the app persists, deduplicates, and forwards approved messages between connected peers.

Every connection displays Nearby's authentication digits. Users must compare the digits on both phones and accept on each phone. After connection, the app verifies and pins the peer's public key. The device signing key is generated and held by Android Keystore. Events retain their original event ID, origin device ID, payload, and creation time; relays add a signed forwarding attestation rather than replacing the origin.

This is a local trust-on-first-use system, not a global identity authority. A relayed identity is vouched for by a directly trusted peer and should not be treated as independently verified by a server.

## Message and delivery rules

- Only `request.create`, `request.update`, `request.cancel`, `donor-response.accept`, `donor-response.decline`, and `donor.withdraw` are shared.
- Payload fields are validated against the Phase 4 contract. Patient names, notes, phone numbers, donor profiles, credentials, and location are not sent. A request may include its facility name; donor responses use a short device-derived pseudonym.
- Messages are capped at four hops and expire after 30 minutes. A persistent message-ID record prevents duplicate application; a relay never forwards back to the peer that supplied the message.
- The local queue, peer receipts, forwarding history, route metadata, and acknowledgements are stored in Room. Schema version 1 migrates to version 2 without dropping user data.
- The app distinguishes queued, transmitted, stored by a peer, relayed, delivered to a specifically targeted device, expired, and failed/rejected states. A Nearby send callback means only that the transport accepted the payload; it does not prove destination delivery.
- Nearby discovery and relay operate only while the app is foregrounded. Background workers and automatic backend synchronization are not part of Phase 6.

The Phase 4 backend requires an uploaded event's origin device to match the authenticated uploader. A relayed event therefore remains local and must not be uploaded under the relay device's identity. The backend contract has not been weakened.

## Physical-device verification procedure

Use two to four Android phones with Google Play services and the Phase 6 build. Keep SisterMesh open in the foreground on every phone. Grant the requested nearby permissions and enable Bluetooth; Nearby may also use Wi-Fi. Connection range and radio behavior depend on the phones and environment.

1. On two phones, start nearby discovery. When the security dialog appears, compare the digits in person and accept only if they match on both phones.
2. Create a request on the CHW phone. Confirm the donor phone receives only blood group, urgency, units, and facility name. Confirm no request note, patient information, phone number, or profile is shown as message data.
3. Accept, decline, and withdraw on the donor phone. Verify that the response is addressed to the request's origin device and that the CHW view shows the pseudonymous reference.
4. Repeat with three phones (A-B-C), then four (A-B-C-D). Keep adjacent relay phones connected and, where practical, place the endpoints outside each other's direct radio range. Create a request at A and send a response from the far endpoint. Confirm it is relayed toward A and that the original event ID and origin remain unchanged.
5. Disconnect an intermediate phone before sending, then reconnect it while the app is foregrounded. Verify queued messages retry, duplicate receptions do not create duplicate requests, and messages stop at expiry or the four-hop limit.
6. Repeat with Bluetooth disabled and with a nearby permission denied. Confirm the app reports that nearby service is unavailable or limited while local records remain accessible.

Do not infer end-to-end delivery from a connection or transport callback. Verify the final device's stored request or response and its acknowledgement. This environment has no physical Android phones; the above procedure is required to verify radio behavior, range, and actual device-to-device operation.

## Automated checks

Run from `android-app/`:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Automated tests cover 2-, 3-, and 4-device relay paths, origin-signature integrity, payload rejection, deduplication, loop and hop-limit checks, TTL expiry, Room persistence across reopen, acknowledgement routing, and the version 1 to version 2 migration.
