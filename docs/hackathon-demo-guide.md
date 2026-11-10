# SisterMesh hackathon demo guide

This sequence uses synthetic accounts and data. Do not enter real patient
identifiers or use the production database for a demonstration.

## Before the demo

### Backend and database

1. In Replit, start the configured **SisterMesh Backend** workflow. It connects
   `DATABASE_URL` to `SISTERMESH_DEV_DATABASE_URL`; the verified Neon target is
   the `sistermesh-dev` project's `development` branch. Do not change the
   production `DATABASE_URL`.
2. Generate the Prisma client if needed:

   ```sh
   npm run prisma:generate
   ```

3. Check `GET /api/health` and, if useful, open `/api/docs`. The development
   workflow uses port 5000.
4. Do not run a database reset or migration as part of the demo.

### Accounts, facility, and devices

- Have an active facility created and approved through an authorized
  administrative operation. There are no admin registration or admin screens.
- Have an active health-worker account verified and assigned to that facility
  by an authorized administrator. A health worker who registers in the app is
  not verified automatically.
- Prepare two individual accounts: one requester and one donor. Donors are
  individuals with a complete donor profile, compatible blood group, and
  availability turned on. Use distinct demo phone numbers.
- For the full role sequence, use three devices if available (requester,
  health worker, donor). Role switching on fewer devices is possible but takes
  longer. Bluetooth requires at least two physical Android devices.
- Sign into each account online once. Load the active facility list before the
  offline scenario. Link local records only after reviewing the account-link
  confirmation.
- The Android debug build uses `SISTERMESH_API_BASE_URL` when set; it must end
  in `/api/`. In Replit, the build can use `REPLIT_DEV_DOMAIN` automatically.
  A phone needs a reachable backend address; an emulator or a real device is
  needed for hardware checks.

## Demo sequence

### 1. Individual creates a request

1. Sign in as the requester and open the request form.
2. Create a synthetic request for yourself. Optionally show a request on behalf
   of another person using a fictitious name and relationship.
3. Select the previously loaded active facility and submit.
4. Show that the request is saved locally and remains **Waiting for facility
   review**. It is private and is not yet visible to donors or eligible for
   mesh publication.

### 2. Verified health worker reviews it

1. Sign in as the pre-verified health worker assigned to the selected facility.
2. Open the facility's pending requests and approve the synthetic request.
   Rejection and requests for more information are also supported; update and
   resubmit when demonstrating that path.
3. Sync the requester device. Confirm the status changes to **Open to donors**.
   Only now may public request details be shared with donors.

### 3. Donor responds

1. Sign in as the donor and make sure the donor profile is complete, available,
   and compatible with the request.
2. Open eligible requests and accept the approved request.
3. Explain that accepting is only a response; it does not mean a donation
   happened. A donor sees public coordination details, not beneficiary names,
   phone numbers, or private notes.

### 4. Health worker records a completed donation

1. Return to the verified health-worker account and open the facility request's
   responses.
2. Record a confirmation only after the demonstration's clearly identified
   synthetic donation scenario. The backend accepts confirmations only from an
   authorized health worker for the request's facility.
3. Show the updated request/confirmation history. Do not present the demo
   record as a real clinical event. The donor-screen action labelled
   **“Demo: facility confirms my donation”** is local simulation, not the real
   confirmation workflow.

### 5. Offline request and reconnect

1. While online, keep the account signed in and linked, and ensure the facility
   list is already loaded.
2. Disable internet access but leave the app installed. Create a synthetic
   request and verify it is saved in Room with a pending facility-review state.
3. Close and reopen the app to demonstrate local persistence.
4. Restore internet access and use **Sync now** if available. WorkManager also
   retries with network access; its periodic interval is not immediate.
5. Verify the queued event is processed and the request remains private until
   facility review. No production data is involved.

### 6. Bluetooth exchange without internet

1. Before disconnecting, sync an approved, synthetic request to the sending
   device. Keep Bluetooth and Wi-Fi enabled because Nearby Connections may use
   both; disable internet/cellular access rather than turning off the radios.
2. On at least two physical devices, grant Nearby permissions and keep SisterMesh
   in the foreground. Nearby discovery runs while the app is foregrounded.
3. When the connection prompt appears, compare the authentication digits shown
   on both phones. Accept only when they match; otherwise reject.
4. Show the request's nearby delivery state. A local send, peer storage, relay,
   and destination delivery are distinct states; do not claim destination
   delivery unless the receiving device confirms it.
5. Only approved open requests can be relayed. Mesh payloads allow-list public
   request fields and exclude beneficiary details, phone numbers, and personal
   notes.

### 7. Offline voice entry

1. Use Android 12 or newer with an available on-device recognition service.
   On Android 13 or newer, check or request the selected language model while
   online. Install an offline English TTS voice in Android settings if none is
   listed in the app.
2. Prepare the model/voice before the offline demo, then disable internet.
3. Dictate a short synthetic request. Edit the transcript and explicitly
   confirm it to prefill the form; then review and submit the request separately.
4. If the device does not list an installed on-device model or offline voice,
   use typed entry rather than switching to a network recognizer.
5. English (Nigeria) and English (United States) are locale choices, not
   language-quality claims. Nigerian English samples and Pidgin, Yoruba, Igbo,
   and Hausa have not been validated in this project.

## Verification limits

Backend unit, API, and development-database integration tests, Prisma
validation, Android JVM/Room tests, and debug APK packaging can be run in this
workspace. They do not verify real account onboarding, Bluetooth radio
behavior, offline speech recognition, latency, battery use, or language quality
on a physical phone. Those demonstrations still require the prepared accounts
and Android devices described above.
