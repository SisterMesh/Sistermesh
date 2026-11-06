# SisterMesh (Android, Kotlin + Jetpack Compose)

SisterMesh's existing 17-screen Kotlin + Jetpack Compose Android app. Room now stores the local profile,
requests, donor responses, donation confirmations, request activity, device identity, sync checkpoint,
and a durable Phase 4-compatible event queue. Existing screens and demo flows remain in place.

This phase is offline persistence only. It does not connect to the backend, start automatic sync, use
WorkManager, or add Bluetooth/Nearby Connections.

## Open and run
1. Open this folder in Android Studio (Ladybug or newer, JDK 17).
2. Let Gradle sync.
3. Run on an emulator or device (minSdk 26).

The project uses JDK 17, Gradle 8.9, and Android Gradle Plugin 8.6.1.
Run JVM/Room tests with `./gradlew :app:testDebugUnitTest`.

## Structure
```
app/src/main/java/com/sistermesh/app/
  MainActivity.kt
  navigation/AppNav.kt            route graph
  data/Models.kt                  domain models + status enums
  data/MockData.kt                sample requests, receipts, quick replies
  data/AppViewModel.kt            Compose state collected from the Room repository
  data/SisterMeshRepository.kt    Room-backed reads, writes, and offline event creation
  data/local/                     Room entities, DAOs, mappings, and schema
  ui/theme/                       Color, Type, Theme
  ui/components/Components.kt     buttons, cards, tags, inputs, tab bar, logo, pulse
  ui/screens/onboarding/          S01 to S06
  ui/screens/donor/               S07, S11
  ui/screens/chw/                 S08, S09, S10, S12, S14
  ui/screens/shared/              S13, S15, S16, S17, voice sheet
```

## Demo script (single device)
Donor: register, enable permissions, open the incoming request, tap "I can help", send a quick message,
then "Demo: facility confirms my donation" to see the consent receipt.
CHW: reset the profile, register as a healthcare worker, tap Find a Sister, pick a blood group, broadcast.
The discovery screen simulates devices found, delivery, an acceptance and a decline.
Open responses, coordinate, record the donation and view the receipt (real SHA-256 hash).

## Offline behavior and sync boundary
- Local profile, availability, language, requests, responses, confirmations, and activity survive app restarts.
- Mock records are seeded once. Resetting the demo profile removes those sample records but preserves queued events.
- Supported user actions create durable v1 event records with UUIDs, device identity, timestamps, payloads,
  status, retry metadata, and server-result fields. Notes stay local and are not included in sync payloads.
- The device identifier uses a Room UUID type converter; enum-like states are stored as explicit contract strings.
- Actions that need a server request ID or verified donor profile remain `BLOCKED` with a reason; failed or
  blocked events are not deleted.
- The exported Room schema is version 1. There was no previous Room database in the app to migrate; no
  destructive migration fallback is configured. Future schema versions must include explicit migrations
  and migration tests.

## Not implemented in this phase
- Backend or automatic synchronization; the event queue is local only.
- Nearby Connections in place of `startDiscovery`
- Real runtime permissions in PermissionsScreen
- Offline STT/TTS in VoiceSheet
- Polygon Amoy anchoring for Receipt.hash
