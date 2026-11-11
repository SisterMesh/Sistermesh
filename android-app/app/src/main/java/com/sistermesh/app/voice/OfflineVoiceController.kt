package com.sistermesh.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

data class VoiceCaptureState(
    val transcript: String = "",
    val status: String = "Tap the microphone to begin.",
    val listening: Boolean = false,
    val preparing: Boolean = false,
    val progress: Float? = null,
    val error: Boolean = false
)

/**
 * Uses Android's own offline recognizer when it can manage the language model.
 * Older Android versions use the downloaded Vosk model, which streams continuously
 * and does not stop at short silences.
 */
class OfflineVoiceController(
    context: Context,
    private val modelInstaller: OfflineVoiceModelInstaller = OfflineVoiceModelInstaller.get(context)
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accumulator = TranscriptAccumulator()
    private val _state = MutableStateFlow(VoiceCaptureState())
    val state: StateFlow<VoiceCaptureState> = _state

    private var nativeRecognizer: SpeechRecognizer? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var selectedLanguage = DEFAULT_LANGUAGE
    private var wantsListening = false
    private var consecutiveRestarts = 0
    private var modelInstallJob: kotlinx.coroutines.Job? = null
    private var startedAt = 0L
    private var sessionTimerStarted = false
    private var finalizing = false
    private var closed = false

    private val maximumDurationMs = 120_000L
    private val maximumConsecutiveRestarts = 12

    fun start(languageTag: String = DEFAULT_LANGUAGE) {
        if (closed || wantsListening || finalizing) return
        selectedLanguage = languageTag
        accumulator.clear()
        consecutiveRestarts = 0
        sessionTimerStarted = false
        wantsListening = true
        setState(
            transcript = "",
            status = "Checking the offline voice model…",
            listening = false,
            preparing = true,
            progress = null,
            error = false
        )
        handler.removeCallbacks(maximumDurationTask)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            checkSystemModel()
        } else {
            prepareVoskModel()
        }
    }

    fun stop() {
        if (!wantsListening) return
        wantsListening = false
        handler.removeCallbacks(maximumDurationTask)
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        modelInstallJob?.cancel()
        modelInstallJob = null

        val native = nativeRecognizer
        val service = speechService
        if (native != null) {
            finalizing = true
            setState(
                status = "Finishing transcript…",
                listening = false,
                preparing = true,
                progress = null,
                error = false
            )
            runCatching { native.stopListening() }
            handler.removeCallbacks(stopFinalizationTask)
            handler.postDelayed(stopFinalizationTask, STOP_FINALIZATION_MS)
        } else if (service != null) {
            finalizing = true
            setState(
                status = "Finishing transcript…",
                listening = false,
                preparing = true,
                progress = null,
                error = false
            )
            stopVoskCapture { showStoppedStatus() }
        } else {
            showStoppedStatus()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        wantsListening = false
        finalizing = false
        handler.removeCallbacksAndMessages(null)
        modelInstallJob?.cancel()
        scope.cancel()
        val service = speechService
        val recognizer = voskRecognizer
        val model = voskModel
        speechService = null
        voskRecognizer = null
        voskModel = null
        releaseSystemRecognizer()
        Thread({
            runCatching { service?.cancel() }
            runCatching { service?.shutdown() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }, "sistermesh-voice-release").start()
    }

    private fun checkSystemModel() {
        if (!wantsListening || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val recognizer = runCatching {
            nativeRecognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                .also { nativeRecognizer = it; it.setRecognitionListener(systemListener) }
        }.getOrElse {
            prepareVoskModel()
            return
        }
        val intent = recognitionIntent()
        setState(status = "Checking the offline voice model…", preparing = true, progress = null, error = false)
        runCatching {
            recognizer.checkRecognitionSupport(intent, appContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: android.speech.RecognitionSupport) {
                    if (!wantsListening) return
                    val installed = support.installedOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    when {
                        installed.any { sameLanguage(it, selectedLanguage) } -> beginSystemRecognition()
                        supported.any { sameLanguage(it, selectedLanguage) } -> requestSystemModelDownload()
                        else -> prepareVoskModel()
                    }
                }

                override fun onError(error: Int) {
                    if (wantsListening) prepareVoskModel()
                }
            })
        }.onFailure {
            prepareVoskModel()
        }
    }

    private fun requestSystemModelDownload() {
        if (!wantsListening || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val recognizer = nativeRecognizer ?: run {
            prepareVoskModel()
            return
        }
        val intent = recognitionIntent()
        setState(
            status = "Setting up offline speech. Internet is needed once.",
            preparing = true,
            progress = null,
            error = false
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                recognizer.triggerModelDownload(intent, appContext.mainExecutor, object : ModelDownloadListener {
                    override fun onScheduled() {
                        setState(status = "Preparing offline speech…", preparing = true)
                    }

                    override fun onProgress(completedPercent: Int) {
                        setState(
                            status = "Downloading offline speech model…",
                            preparing = true,
                            progress = completedPercent.coerceIn(0, 100) / 100f
                        )
                    }

                    override fun onSuccess() {
                        if (wantsListening) checkSystemModel()
                    }

                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) {
                            startSystemDownloadAndPoll()
                        } else {
                            fail(
                                when (error) {
                                    SpeechRecognizer.ERROR_NETWORK,
                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                        "Connect to the internet once, then tap retry."
                                    else -> "Could not prepare offline speech. Tap retry."
                                }
                            )
                        }
                    }
                })
            }.onFailure { fail("Could not start the offline speech download. Tap retry.") }
        } else {
            startSystemDownloadAndPoll()
        }
    }

    private fun startSystemDownloadAndPoll() {
        val recognizer = nativeRecognizer ?: run {
            prepareVoskModel()
            return
        }
        runCatching { recognizer.triggerModelDownload(recognitionIntent()) }
            .onFailure {
                fail("Could not start the offline speech download. Connect to the internet and retry.")
                return
            }
        setState(
            status = "Downloading offline speech. Internet is needed once.",
            preparing = true,
            progress = null,
            error = false
        )
        pollForSystemModel(0)
    }

    private fun pollForSystemModel(attempt: Int) {
        if (!wantsListening || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (attempt >= MAX_MODEL_POLL_ATTEMPTS) {
            fail("Model setup is taking too long. Check your connection and tap retry.")
            return
        }
        val recognizer = nativeRecognizer ?: return
        runCatching {
            recognizer.checkRecognitionSupport(recognitionIntent(), appContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: android.speech.RecognitionSupport) {
                    if (!wantsListening) return
                    if (support.installedOnDeviceLanguages.any { sameLanguage(it, selectedLanguage) }) {
                        beginSystemRecognition()
                    } else {
                        handler.postDelayed({ pollForSystemModel(attempt + 1) }, MODEL_POLL_INTERVAL_MS)
                    }
                }

                override fun onError(error: Int) {
                    if (wantsListening) handler.postDelayed({ pollForSystemModel(attempt + 1) }, MODEL_POLL_INTERVAL_MS)
                }
            })
        }.onFailure {
            handler.postDelayed({ pollForSystemModel(attempt + 1) }, MODEL_POLL_INTERVAL_MS)
        }
    }

    private fun prepareVoskModel() {
        if (!wantsListening) return
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        handler.removeCallbacks(maximumDurationTask)
        sessionTimerStarted = false
        releaseSystemRecognizer()
        setState(
            status = "Setting up the English (US) fallback model. Internet is needed once.",
            preparing = true,
            progress = null,
            error = false
        )
        modelInstaller.ensureInstalled()
        modelInstallJob?.cancel()
        modelInstallJob = scope.launch {
            modelInstaller.state.collect { installState ->
                when (installState) {
                    OfflineModelState.NotInstalled -> Unit
                    is OfflineModelState.Downloading -> setState(
                        status = if (installState.installing) "Installing offline speech…" else
                            "Downloading the English (US) model · about 39 MiB…",
                        preparing = true,
                        progress = installState.progress
                    )
                    OfflineModelState.Ready -> {
                        modelInstallJob?.cancel()
                        modelInstallJob = null
                        if (wantsListening) beginVoskRecognition()
                    }
                    is OfflineModelState.Failed -> {
                        modelInstallJob?.cancel()
                        modelInstallJob = null
                        fail(installState.message)
                    }
                }
            }
        }
        // Re-read after starting the collector so an already-installed model starts immediately.
        if (modelInstaller.state.value == OfflineModelState.Ready) {
            modelInstallJob?.cancel()
            modelInstallJob = null
            beginVoskRecognition()
        }
    }

    private fun beginVoskRecognition() {
        if (!wantsListening) return
        setState(status = "Starting offline voice…", preparing = true, progress = null, error = false)
        scope.launch {
            try {
                val existingModel = voskModel
                val loaded = withContext(Dispatchers.IO) {
                    val model = existingModel ?: Model(modelInstaller.modelPath)
                    try {
                        val recognizer = Recognizer(model, SAMPLE_RATE)
                        Triple(model, recognizer, SpeechService(recognizer, SAMPLE_RATE))
                    } catch (error: Exception) {
                        if (existingModel == null) model.close()
                        throw error
                    }
                }
                if (!wantsListening || closed) {
                    withContext(Dispatchers.IO) {
                        loaded.third.cancel()
                        loaded.third.shutdown()
                        loaded.second.close()
                        if (existingModel == null) loaded.first.close()
                    }
                    return@launch
                }
                voskModel = loaded.first
                voskRecognizer = loaded.second
                speechService = loaded.third
                loaded.third.startListening(voskListener)
                startSessionTimer()
                setState(
                    status = "Listening · speak naturally, then tap stop.",
                    listening = true,
                    preparing = false,
                    progress = null,
                    error = false
                )
            } catch (error: Exception) {
                fail("Could not start offline voice. Try again.")
            } catch (error: OutOfMemoryError) {
                fail("This phone does not have enough memory to start offline voice.")
            }
        }
    }

    private fun beginSystemRecognition() {
        if (!wantsListening) return
        consecutiveRestarts = 0
        startSessionTimer()
        startSystemSegment()
    }

    private fun startSystemSegment() {
        if (!wantsListening || closed) return
        val recognizer = nativeRecognizer ?: run {
            prepareVoskModel()
            return
        }
        setState(
            status = "Listening · speak naturally, then tap stop.",
            listening = true,
            preparing = false,
            progress = null,
            error = false
        )
        runCatching { recognizer.startListening(recognitionIntent()) }
            .onFailure { scheduleSystemRestart("Listening is restarting…", RETRY_DELAY_MS) }
    }

    private val systemListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            if (wantsListening) setState(status = "Listening · speak naturally, then tap stop.", listening = true)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (wantsListening) setState(status = "Pause detected · keep speaking when ready.", listening = true)
        }

        override fun onError(error: Int) {
            if (!wantsListening) {
                finalizing = false
                handler.removeCallbacks(stopFinalizationTask)
                showStoppedStatus()
                return
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            ) {
                scheduleSystemRestart("Still listening…", retryDelay())
            } else {
                fail(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access, then retry."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            prepareVoskModel()
                            return
                        }
                        else -> "Offline recognition stopped. Tap the microphone to retry."
                    }
                )
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (recognized.isNotBlank()) appendFinal(recognized)
            if (wantsListening) {
                consecutiveRestarts = 0
                scheduleSystemRestart("Listening · speak naturally, then tap stop.", SEGMENT_RESTART_DELAY_MS)
            } else {
                finalizing = false
                handler.removeCallbacks(stopFinalizationTask)
                showStoppedStatus()
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                setState(transcript = accumulator.withPartial(partial))
            }
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            val partial = parseVoskText(hypothesis)
            if (partial.isNotBlank()) handler.post {
                if (wantsListening) setState(transcript = accumulator.withPartial(partial))
            }
        }

        override fun onResult(hypothesis: String) {
            val final = parseVoskText(hypothesis)
            if (final.isNotBlank()) handler.post {
                if (wantsListening) {
                    consecutiveRestarts = 0
                    appendFinal(final)
                }
            }
        }

        override fun onFinalResult(hypothesis: String) {
            val final = parseVoskText(hypothesis)
            handler.post {
                if (final.isNotBlank()) appendFinal(final)
                if (!wantsListening && !_state.value.error && !closed) {
                    finalizing = false
                    handler.removeCallbacks(stopFinalizationTask)
                    showStoppedStatus()
                }
            }
        }

        override fun onError(exception: Exception) {
            handler.post {
                if (wantsListening) fail("Offline recognition stopped. Tap the microphone to retry.")
            }
        }

        override fun onTimeout() {
            handler.post {
                if (wantsListening) scheduleVoskRestart()
                else showStoppedStatus()
            }
        }
    }

    private fun scheduleVoskRestart() {
        if (!wantsListening || consecutiveRestarts >= MAX_RESTARTS) {
            fail("Listening paused. Tap the microphone to continue.")
            return
        }
        consecutiveRestarts += 1
        stopVoskCapture {
            handler.postDelayed({
                if (wantsListening) beginVoskRecognition()
            }, SEGMENT_RESTART_DELAY_MS)
        }
    }

    private fun scheduleSystemRestart(status: String, delayMs: Long) {
        if (!wantsListening) {
            showStoppedStatus()
            return
        }
        if (consecutiveRestarts >= maximumConsecutiveRestarts) {
            fail("Listening stopped after repeated recognition errors. Tap the microphone to try again.")
            return
        }
        consecutiveRestarts += 1
        setState(status = status, listening = true, preparing = false, progress = null, error = false)
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        handler.postAtTime({
            if (wantsListening) startSystemSegment()
        }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun retryDelay(): Long = minOf(
        SEGMENT_RESTART_DELAY_MS * (1L shl consecutiveRestarts.coerceAtMost(3)),
        MAX_RETRY_DELAY_MS
    )

    private val maximumDurationTask = Runnable {
        if (wantsListening && android.os.SystemClock.elapsedRealtime() - startedAt >= maximumDurationMs) {
            wantsListening = false
            finalizing = true
            handler.removeCallbacksAndMessages(RESTART_TOKEN)
            runCatching { nativeRecognizer?.stopListening() }
            if (nativeRecognizer != null) {
                setState(status = "Finishing transcript…", listening = false, preparing = true)
                handler.removeCallbacks(stopFinalizationTask)
                handler.postDelayed(stopFinalizationTask, STOP_FINALIZATION_MS)
            }
            stopVoskCapture { showStoppedStatus("Two-minute recording limit reached.") }
        }
    }

    private val stopFinalizationTask = Runnable {
        if (finalizing) {
            finalizing = false
            showStoppedStatus()
        }
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun appendFinal(text: String) {
        setState(transcript = accumulator.appendFinal(text))
    }

    private fun showStoppedStatus(message: String? = null) {
        finalizing = false
        handler.removeCallbacks(stopFinalizationTask)
        setState(
            status = message ?: if (accumulator.text.isBlank()) "No speech captured. Tap the microphone to try again."
                else "Transcript ready to review.",
            transcript = accumulator.text,
            listening = false,
            preparing = false,
            progress = null,
            error = false
        )
    }

    private fun fail(message: String) {
        wantsListening = false
        finalizing = false
        handler.removeCallbacks(maximumDurationTask)
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        modelInstallJob?.cancel()
        modelInstallJob = null
        runCatching { nativeRecognizer?.cancel() }
        stopVoskCapture()
        setState(
            status = message,
            transcript = accumulator.text,
            listening = false,
            preparing = false,
            progress = null,
            error = true
        )
    }

    private fun releaseSystemRecognizer() {
        runCatching { nativeRecognizer?.cancel() }
        runCatching { nativeRecognizer?.destroy() }
        nativeRecognizer = null
    }

    private fun startSessionTimer() {
        if (sessionTimerStarted) return
        sessionTimerStarted = true
        startedAt = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(maximumDurationTask)
        handler.postDelayed(maximumDurationTask, maximumDurationMs)
    }

    private fun stopVoskCapture(onStopped: (() -> Unit)? = null) {
        val service = speechService
        val recognizer = voskRecognizer
        speechService = null
        voskRecognizer = null
        if (service == null && recognizer == null) {
            onStopped?.invoke()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { service?.stop() }
                runCatching { service?.shutdown() }
                runCatching { recognizer?.close() }
            }
            delay(STOP_FINALIZATION_MS)
            onStopped?.invoke()
        }
    }

    private fun setState(
        transcript: String = _state.value.transcript,
        status: String = _state.value.status,
        listening: Boolean = _state.value.listening,
        preparing: Boolean = _state.value.preparing,
        progress: Float? = _state.value.progress,
        error: Boolean = _state.value.error
    ) {
        _state.value = VoiceCaptureState(transcript, status, listening, preparing, progress, error)
    }

    private fun sameLanguage(installedTag: String, requestedTag: String): Boolean {
        val installed = Locale.forLanguageTag(installedTag)
        val requested = Locale.forLanguageTag(requestedTag)
        return installedTag.equals(requestedTag, ignoreCase = true) ||
            (installed.language == requested.language && installed.country.isBlank())
    }

    private fun parseVoskText(hypothesis: String): String =
        runCatching { JSONObject(hypothesis).optString("text").trim() }.getOrDefault("")

    private companion object {
        const val DEFAULT_LANGUAGE = "en-NG"
        const val SAMPLE_RATE = 16_000.0f
        const val MAX_MODEL_POLL_ATTEMPTS = 180
        const val MODEL_POLL_INTERVAL_MS = 1_000L
        const val RETRY_DELAY_MS = 1_000L
        const val SEGMENT_RESTART_DELAY_MS = 350L
        const val MAX_RETRY_DELAY_MS = 4_000L
        const val STOP_FINALIZATION_MS = 1_500L
        const val MAX_RESTARTS = 12
        val RESTART_TOKEN = Any()
    }
}
