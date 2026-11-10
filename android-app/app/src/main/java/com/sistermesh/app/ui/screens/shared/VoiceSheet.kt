@file:OptIn(ExperimentalMaterial3Api::class)

package com.sistermesh.app.ui.screens.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupportCallback
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sistermesh.app.data.Role
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.theme.*
import java.util.Locale

private const val VOICE_PREFS = "sistermesh_offline_voice"
private val RecognitionLanguages = listOf(
    "en-NG" to "English (Nigeria)",
    "en-US" to "English (United States)"
)

@Composable
fun VoiceSheet(
    role: Role,
    onTranscriptConfirmed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
    }
    var languageTag by remember { mutableStateOf(prefs.getString("stt_language", "en-NG") ?: "en-NG") }
    var transcript by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("Checking on-device speech support…") }
    var downloadProgress by remember { mutableIntStateOf(-1) }
    var languageMenu by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var onDeviceAvailable by remember { mutableStateOf(false) }
    val recognizerState = remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var offlineVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoiceName by remember {
        mutableStateOf(prefs.getString("tts_voice", null))
    }
    var speechRate by remember {
        mutableFloatStateOf(prefs.getFloat("tts_rate", 1f).coerceIn(0.7f, 1.5f))
    }

    val hasMicrophonePermission = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    var microphoneGranted by remember { mutableStateOf(hasMicrophonePermission) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneGranted = granted
        status = if (granted) {
            "Microphone ready. Tap the microphone to start."
        } else {
            "Microphone permission is required for voice input."
        }
    }

    onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    DisposableEffect(context, onDeviceAvailable) {
        val recognizer = if (onDeviceAvailable) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else null
        recognizerState.value = recognizer
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                status = "Listening on this device…"
            }
            override fun onBeginningOfSpeech() {
                listening = true
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
                status = "Transcribing on this device…"
            }
            override fun onError(error: Int) {
                listening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Allow microphone access, then try again."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "That offline language model is not installed. Prepare it while online."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        "This device's offline recognizer does not support that language."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech was recognized. Try again in a quieter place."
                    else -> "On-device recognition failed (code $error). Try again."
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (recognized.isNotBlank()) {
                    transcript = recognized
                    status = "Review and edit the transcript before continuing."
                } else {
                    status = "No speech was recognized. Try again."
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) transcript = partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizerState.value = null
        }
    }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            val current = tts
            if (result == TextToSpeech.SUCCESS && current != null) {
                val installedOfflineVoices = current.voices
                    ?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                    ?.sortedBy { it.name }
                    .orEmpty()
                offlineVoices = installedOfflineVoices
                val preferred = installedOfflineVoices.firstOrNull { it.name == selectedVoiceName }
                    ?: installedOfflineVoices.firstOrNull()
                if (preferred != null) {
                    current.voice = preferred
                    selectedVoiceName = preferred.name
                    prefs.edit().putString("tts_voice", preferred.name).apply()
                    current.setSpeechRate(speechRate)
                    ttsReady = true
                } else {
                    ttsReady = false
                }
            } else {
                ttsReady = false
            }
        }
        tts = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            tts = null
            ttsReady = false
        }
    }

    fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun refreshModelStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !onDeviceAvailable) {
            modelStatus = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                "On-device voice input requires Android 12 or newer."
            } else {
                "No on-device speech recognizer is available on this device."
            }
            return
        }

        val checker = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull()
        if (checker == null) {
            modelStatus = "Could not open the on-device speech service."
            return
        }
        modelStatus = "Checking ${languageTag} model availability…"
        checker.checkRecognitionSupport(
            recognitionIntent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: android.speech.RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages
                    val pending = support.pendingOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    modelStatus = when {
                        installed.any { it.equals(languageTag, ignoreCase = true) } ->
                            "Offline model ready for $languageTag."
                        pending.any { it.equals(languageTag, ignoreCase = true) } ->
                            "Offline model download is queued for $languageTag."
                        supported.any { it.equals(languageTag, ignoreCase = true) } ->
                            "Offline model available to download for $languageTag."
                        support.onlineLanguages.any { it.equals(languageTag, ignoreCase = true) } ->
                            "Only online recognition is listed for $languageTag. SisterMesh will not use it."
                        else -> "No on-device model is listed for $languageTag."
                    }
                    checker.destroy()
                }

                override fun onError(error: Int) {
                    modelStatus = "Could not check offline model status (code $error)."
                    checker.destroy()
                }
            }
        )
    }

    LaunchedEffect(languageTag, onDeviceAvailable) {
        prefs.edit().putString("stt_language", languageTag).apply()
        refreshModelStatus()
    }

    fun requestModelDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !onDeviceAvailable) return
        val downloader = recognizerState.value ?: runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull()
        if (downloader == null) {
            modelStatus = "Could not open the on-device speech service."
            return
        }
        val intent = recognitionIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            downloader.triggerModelDownload(
                intent,
                context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onScheduled() {
                        modelStatus = "Offline model download is queued."
                    }
                    override fun onProgress(completedPercent: Int) {
                        downloadProgress = completedPercent
                        modelStatus = "Downloading offline model: $completedPercent%."
                    }
                    override fun onSuccess() {
                        downloadProgress = 100
                        modelStatus = "Offline model ready."
                    }
                    override fun onError(error: Int) {
                        downloadProgress = -1
                        modelStatus = "Offline model download failed (code $error)."
                    }
                }
            )
        } else {
            downloader.triggerModelDownload(intent)
            modelStatus = "Download requested. Refresh status after it completes."
        }
    }

    val startListening = {
        if (!onDeviceAvailable) {
            status = "Offline voice input is not available on this device."
        } else if (!microphoneGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val recognizer = recognizerState.value
            if (recognizer == null) {
                status = "Could not open the on-device speech service."
            } else {
                transcript = ""
                status = "Starting on-device recognition…"
                runCatching { recognizer.startListening(recognitionIntent()) }
                    .onFailure { status = "Could not start recording: ${it.localizedMessage ?: "try again"}" }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Offline voice request", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                if (role == Role.CHW) "Say the blood group, number of units, and facility."
                else "Describe the blood request you want to create.",
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speech language", modifier = Modifier.weight(1f), color = Muted)
                Box {
                    TextButton(onClick = { languageMenu = true }) {
                        Text(RecognitionLanguages.firstOrNull { it.first == languageTag }?.second ?: languageTag)
                    }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        RecognitionLanguages.forEach { (tag, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { languageTag = tag; languageMenu = false }
                            )
                        }
                    }
                }
            }

            SmCard {
                Text(modelStatus, style = MaterialTheme.typography.bodyMedium)
                if (downloadProgress >= 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Teal
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && onDeviceAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { requestModelDownload() }) { Text("Prepare offline model") }
                        TextButton(onClick = { refreshModelStatus() }) { Text("Refresh") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Surface(
                onClick = { if (listening) recognizerState.value?.stopListening() else startListening() },
                enabled = !listening || microphoneGranted,
                shape = CircleShape,
                color = if (listening) Coral else Teal,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        if (listening) "Stop recording" else "Start offline recording",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (listening) "Recording on this device" else "Tap to record",
                style = MaterialTheme.typography.labelMedium,
                color = if (listening) Coral else Muted
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Review and edit the transcript") },
                minLines = 3,
                maxLines = 5
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeUp, null, tint = Teal)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { voiceMenu = true }, enabled = offlineVoices.isNotEmpty()) {
                        Text(
                            offlineVoices.firstOrNull { it.name == selectedVoiceName }?.name
                                ?: if (ttsReady) "Offline voice" else "No offline English voice"
                        )
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        offlineVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text("${voice.locale.displayName} · ${voice.name}") },
                                onClick = {
                                    selectedVoiceName = voice.name
                                    prefs.edit().putString("tts_voice", voice.name).apply()
                                    tts?.voice = voice
                                    ttsReady = true
                                    voiceMenu = false
                                }
                            )
                        }
                    }
                }
                Text("${"%.1f".format(Locale.US, speechRate)}×", color = Muted)
            }
            Slider(
                value = speechRate,
                onValueChange = {
                    speechRate = it
                    tts?.setSpeechRate(it)
                },
                onValueChangeFinished = { prefs.edit().putFloat("tts_rate", speechRate).apply() },
                valueRange = 0.7f..1.5f,
                enabled = ttsReady
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        val text = transcript.ifBlank {
                            "Describe the blood group, number of units, and facility."
                        }
                        val engine = tts
                        val selected = offlineVoices.firstOrNull { it.name == selectedVoiceName }
                        if (engine != null && selected != null && !selected.isNetworkConnectionRequired) {
                            engine.voice = selected
                            engine.setSpeechRate(speechRate)
                            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sistermesh-offline-prompt")
                        }
                    },
                    enabled = ttsReady
                ) { Text("Speak") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            SmButton(
                "Review request details",
                onClick = { if (transcript.isNotBlank()) onTranscriptConfirmed(transcript.trim()) },
                enabled = transcript.isNotBlank()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Audio is handled by Android's on-device recognizer. It is not sent by SisterMesh.",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                textAlign = TextAlign.Center
            )
        }
    }
}
