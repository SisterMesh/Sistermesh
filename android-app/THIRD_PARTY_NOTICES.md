# Third-party notices

## Vosk Android speech recognition

The Android voice fallback uses Vosk Android 0.3.75, distributed under the Apache License 2.0:
https://www.apache.org/licenses/LICENSE-2.0

## Vosk English offline model

The first-use fallback model is `vosk-model-small-en-us-0.15`, published by Alpha Cephei and
distributed under the Apache License 2.0. The compressed model is approximately 39.3 MiB.
https://alphacephei.com/vosk/models

The model is downloaded from Alpha Cephei over HTTPS, verified against its pinned SHA-256
digest, and stored in the app's private files directory. It is not bundled into the APK.

## Java Native Access (JNA)

Vosk Android depends on JNA. JNA is available under the Apache License 2.0 or LGPL 2.1-or-later:
https://github.com/java-native-access/jna/blob/master/LICENSE
