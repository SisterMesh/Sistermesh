# SisterMesh (Android, Kotlin + Jetpack Compose)

SisterMesh's Kotlin + Jetpack Compose Android app. Room is the local source of
truth for profiles, requests, responses, donation confirmations, activity,
device identity, sync checkpoints, conflicts, and the durable event queue.
Explicit account linking associates eligible local events with a backend
account; WorkManager retries synchronization when network access is available.
Nearby Connections supports foreground Bluetooth/Wi-Fi Direct exchange, and
voice entry uses Android's on-device recognition where the device provides it.

## Open and run

1. Open this folder in Android Studio (Ladybug or newer, JDK 17).
2. Let Gradle sync.
3. Run on an emulator or device (minSdk 26).

The project uses JDK 17, Gradle 8.9, and Android Gradle Plugin 8.6.1.
Run JVM and Room migration tests with
`./gradlew :app:testDebugUnitTest`; build the debug APK with
`./gradlew :app:assembleDebug`.
Set `SISTERMESH_API_BASE_URL` to the reachable backend base ending in `/api/`
when building outside Replit. In Replit, the build falls back to
`REPLIT_DEV_DOMAIN`.

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
  sync/                           authentication, API, sync engine, WorkManager
  communication/                  Nearby Connections and signed mesh messages
  voice/                          offline-only text-to-speech support
  ui/theme/                       Color, Type, Theme
  ui/components/Components.kt     buttons, cards, tags, inputs, tab bar, logo, pulse
  ui/screens/onboarding/          S01 to S06
  ui/screens/donor/               S07, S11
  ui/screens/chw/                 S08, S09, S10, S12, S14
  ui/screens/shared/              S13, S15, S16, S17, voice sheet
```

## Demonstration

See [the hackathon demo guide](../docs/hackathon-demo-guide.md) for the
requester, verified-worker, donor, Bluetooth, reconnect/sync, and voice sequence.
The explicitly labelled facility-confirmation demo action is local simulation;
it does not confirm a real donation or replace the health-worker confirmation
workflow.

## Offline behavior and sync boundary

- Room schema version 5 uses explicit, non-destructive migrations; v4-to-v5 is
  covered by a migration test. Local records and unresolved edits are retained
  when an account is linked or synchronization fails.
- New actions create durable, idempotent events. Synchronization requires a
  signed-in account explicitly linked to the local profile and device. Network
  constraints and retries are managed by WorkManager; conflicts are shown
  without silently overwriting local values.
- Requests stay private until facility approval. The mesh allow-list includes
  only public request fields, and only locally approved open requests can be
  relayed. Beneficiary details, phone numbers, and personal notes are excluded.
- Nearby connections pause when the app leaves the foreground. Both phones
  must show matching authentication digits and the user must approve the
  connection.

## Voice

- The request form and home/profile voice entry points use Android's on-device speech recognition on supported Android 12+ devices. Audio never falls back to a remote recognizer.
- Transcripts are editable and explicitly confirmed before conservative field prefill; the existing request form still requires a separate final submit.
- Text-to-speech uses installed offline English voices only. The OS/provider manages recognition and speech models.
- Device and language availability limitations, model comparisons, licensing notes, and unverified hardware checks are documented in `OFFLINE_VOICE.md`.

Bluetooth transfer, offline speech recognition, TTS availability, and real
account workflows still require testing on physical devices with configured
demo accounts. The debug build and JVM/Room tests do not establish hardware
behavior.
