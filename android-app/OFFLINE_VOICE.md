# Offline voice in SisterMesh

## Current implementation

- On Android 13 / API 33 and newer, SisterMesh checks the platform on-device recognizer and requests its offline language model automatically when the user first records. Android manages the model files. Android 14 / API 34 and newer can report download progress; API 33 uses an indeterminate indicator while SisterMesh checks for installation.
- If the platform recognizer or selected offline language is unavailable, and on Android 8 / API 26–32, SisterMesh downloads the Vosk small English model on first use. The 39.3 MiB archive is downloaded over HTTPS, resumed after an interrupted transfer, checked against a pinned SHA-256 digest, safely unpacked, and stored in app-private files. It is not included in the APK.
- First-time setup needs internet. Later recognition uses the installed offline model. The app does not use Android's generic network recognizer as a fallback.
- The Vosk small model is about 70.9 MB unpacked and upstream estimates about 300 MB runtime memory for small models. SisterMesh checks free storage before downloading and reports low-memory failures rather than silently switching to online speech.
- Recording requires runtime microphone permission. The platform recognizer is restarted after short pauses; recognized segments are retained and deduplicated. The fallback streams continuously. Both paths stop when the user taps stop or after two minutes.
- Partial and final transcripts remain in app UI state; SisterMesh does not persist or upload audio.
- A transcript is editable and must be explicitly accepted before it is used to prefill the request form. Deterministic extraction recognizes blood group, a stated quantity from one to six units, and a unique facility match. Unrecognized or ambiguous details remain blank. The existing form still requires the user to review and submit the request.
- Text-to-speech uses an installed offline English voice when available and reads a post-save confirmation. Availability depends on the phone's TTS provider.

## Model and runtime evaluation

| Option | Runtime / license | Model storage and memory | Assessment |
| --- | --- | --- | --- |
| Android on-device speech API | Android platform API; no additional app model dependency | Android/provider manages model size and memory; download and language support vary by device | Preferred on API 33+ when the chosen locale is available. |
| Vosk Android + small US English model | Apache-2.0 runtime and model; JNA dependency offers Apache-2.0 or LGPL-2.1-or-later | 39.3 MiB compressed, 70.9 MB unpacked, about 300 MB runtime memory upstream estimate | Used only when the platform cannot install the selected offline model or on older Android versions. The model is small and listed for Android/Raspberry Pi. |
| Whisper.cpp + Whisper Tiny | Whisper.cpp is MIT; the OpenAI Whisper Tiny model card lists Apache-2.0 | Approx. 75 MiB model file and 273 MB memory in whisper.cpp's reference table | Not selected: no SisterMesh device or Nigerian-accent benchmark was available to justify replacing the supported platform engine. |
| sherpa-onnx | Apache-2.0 runtime; each pretrained model has its own license | Depends on the selected model and quantization | Not selected: no single English/Nigerian model and model-license combination was validated for this release. |

Whisper figures above are upstream reference figures, not SisterMesh measurements. Quantized variants can reduce storage and memory, but their exact performance and accuracy must be benchmarked on representative phones before selection.

## Language support and limitations

- **English (Nigeria), English (United States):** used with the platform engine when supported. The Vosk fallback is a US English model and may have different accuracy for Nigerian accents. No Nigerian speaker benchmark has been run, so recognition quality is not claimed as validated.
- **Nigerian Pidgin, Yoruba, Igbo, Hausa:** not currently validated or claimed as supported. Android's available offline locales vary by installed recognition provider.
- Offline TTS voices are likewise device/provider-dependent. The UI lists only installed English voices that report no network requirement.
- The project has no physical Android device attached to this environment. Latency, battery use, audio capture, platform model-download behavior, Vosk inference, and real-world language quality remain unverified on hardware.

## References

- [Android SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android RecognitionSupport API](https://developer.android.com/reference/android/speech/RecognitionSupport)
- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Vosk Android API](https://alphacephei.com/vosk/android)
- [Vosk model catalog and size/runtime notes](https://alphacephei.com/vosk/models)
- [Vosk Android third-party notices](THIRD_PARTY_NOTICES.md)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
- [OpenAI Whisper Tiny model card](https://huggingface.co/openai/whisper-tiny)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
