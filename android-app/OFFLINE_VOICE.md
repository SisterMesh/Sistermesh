# Offline voice in SisterMesh

## Current implementation

- Speech input uses Android's `SpeechRecognizer.createOnDeviceSpeechRecognizer` only. The app never falls back to the generic recognizer, which Android documents may send audio to remote servers.
- On-device recognition requires Android 12 / API 31 or newer and an installed device recognition service. Android 13 / API 33 and newer exposes model support checks and model-download requests; Android manages the model files and their storage. On older supported app versions, speech input is unavailable rather than silently using an online recognizer.
- Recording requires runtime microphone permission. Partial and final transcripts remain in app UI state; SisterMesh does not persist or upload audio.
- A transcript is editable and must be explicitly accepted before it is used to prefill the request form. Deterministic extraction recognizes blood group, a stated quantity from one to six units, and a unique facility match. Unrecognized or ambiguous details remain blank. The existing form still requires the user to review and submit the request.
- Text-to-speech uses the Android TTS engine and exposes only installed English voices marked as not requiring a network connection. Voice and rate preferences are stored locally. TTS reads the transcript and a post-save confirmation. Users may need to install an offline voice through Android settings first.

## Model and runtime evaluation

| Option | Runtime / license | Model storage and memory | Assessment |
| --- | --- | --- | --- |
| Android on-device speech API (selected) | Android platform API; no extra app dependency | The OS/provider manages model size and memory; the app can query installed, pending, and supported locales on API 33+ | Smallest app integration and strict local-recognition path. Exact engine/model licensing and availability vary by device provider. |
| Whisper.cpp + Whisper Tiny | Whisper.cpp is MIT; the OpenAI Whisper Tiny model card lists Apache-2.0 | Approx. 75 MiB model file and 273 MB memory in whisper.cpp's reference table | Best app-managed candidate to prototype first if consistent model availability is required. Multilingual, but no SisterMesh device benchmark has been run. |
| Whisper.cpp + Whisper Base | Whisper.cpp is MIT; verify the specific distributed checkpoint and conversion artifacts before bundling | Approx. 142 MiB model file and 388 MB memory in whisper.cpp's reference table | Higher storage and memory cost than Tiny; no evidence yet that it improves request transcription enough on target devices to justify the cost. |
| sherpa-onnx | Apache-2.0 runtime; each pretrained model has its own license | Depends on the selected model and quantization | A viable open-source alternative, but no single English/Nigerian model and model-license combination was validated for this release. |

Whisper figures above are upstream reference figures, not SisterMesh measurements. Quantized variants can reduce storage and memory, but their exact performance and accuracy must be benchmarked on representative phones before selection.

## Language support and limitations

- **English (Nigeria), English (United States):** available as recognition locale choices where the device's on-device engine supports and has downloaded them. Neither locale has been tested with Nigerian speaker samples in this project, so neither is claimed as validated.
- **Nigerian Pidgin, Yoruba, Igbo, Hausa:** not currently validated or claimed as supported. Android's available offline locales vary by installed recognition provider.
- Offline TTS voices are likewise device/provider-dependent. The UI lists only installed English voices that report no network requirement.
- The project has no physical Android device attached to this environment. Latency, battery use, accuracy, model-download behavior, and real-world language quality remain unverified on hardware.

## References

- [Android SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android RecognitionSupport API](https://developer.android.com/reference/android/speech/RecognitionSupport)
- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
- [OpenAI Whisper Tiny model card](https://huggingface.co/openai/whisper-tiny)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
