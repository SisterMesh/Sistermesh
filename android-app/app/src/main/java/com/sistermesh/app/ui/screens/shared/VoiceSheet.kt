@file:OptIn(ExperimentalMaterial3Api::class)

package com.sistermesh.app.ui.screens.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sistermesh.app.ui.components.SmButton
import com.sistermesh.app.ui.theme.Canvas
import com.sistermesh.app.ui.theme.Coral
import com.sistermesh.app.ui.theme.Muted
import com.sistermesh.app.ui.theme.Teal
import com.sistermesh.app.voice.OfflineSpeechOutput
import com.sistermesh.app.voice.OfflineVoiceController
import java.util.Locale

private const val VOICE_PREFS = "sistermesh_offline_voice"
private val RecognitionLanguages = listOf(
    "en-NG" to "English (Nigeria)",
    "en-US" to "English (United States)"
)

@Composable
fun VoiceSheet(
    onTranscriptConfirmed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
    }
    var languageTag by remember {
        mutableStateOf(prefs.getString("stt_language", "en-NG") ?: "en-NG")
    }
    var languageMenu by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var permissionMessage by remember { mutableStateOf("") }
    val controller = remember(context) { OfflineVoiceController(context) }
    val capture by controller.state.collectAsState()
    val speechOutput = remember(context) { OfflineSpeechOutput(context) }
    var speechRate by remember {
        mutableFloatStateOf(prefs.getFloat("tts_rate", 1f).coerceIn(0.7f, 1.5f))
    }
    var microphoneGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(languageTag) {
        prefs.edit().putString("stt_language", languageTag).apply()
    }
    LaunchedEffect(capture.transcript) {
        if (capture.transcript != transcript) transcript = capture.transcript
    }
    DisposableEffect(controller, speechOutput) {
        onDispose {
            controller.close()
            speechOutput.close()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneGranted = granted
        permissionMessage = if (granted) "" else "Allow microphone access to use voice input."
        if (granted) controller.start(languageTag)
    }

    fun startOrRequestPermission() {
        permissionMessage = ""
        if (microphoneGranted) {
            controller.start(languageTag)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(onBack = onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Voice request", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "First setup needs internet. After that, voice works offline.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))

            Box {
                TextButton(onClick = { languageMenu = true }, enabled = !capture.listening && !capture.preparing) {
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

            if (capture.preparing) {
                val modelProgress = capture.progress
                if (modelProgress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Teal
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { modelProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Teal
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Surface(
                onClick = {
                    if (capture.listening || capture.preparing) controller.stop()
                    else startOrRequestPermission()
                },
                shape = CircleShape,
                color = if (capture.listening) Coral else Teal,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (capture.listening || capture.preparing) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        if (capture.listening || capture.preparing) "Stop recording" else "Start offline recording",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (capture.listening) "Listening · up to 2 minutes"
                else if (capture.preparing) "Setting up voice"
                else "Tap to speak",
                style = MaterialTheme.typography.labelMedium,
                color = if (capture.listening) Coral else Muted
            )
            val visibleStatus = permissionMessage.ifBlank { capture.status }
            if (visibleStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    visibleStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (capture.error || permissionMessage.isNotBlank()) Coral else Muted,
                    textAlign = TextAlign.Center
                )
            }
            if (capture.error) {
                TextButton(onClick = { startOrRequestPermission() }) {
                    Text("Retry", color = Teal)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Review and edit") },
                minLines = 3,
                maxLines = 5
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        val prompt = transcript.ifBlank {
                            "Describe the blood group, number of units, and facility."
                        }
                        speechOutput.speak(prompt)
                    }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Teal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read aloud", color = Teal)
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            }
            Slider(
                value = speechRate,
                onValueChange = {
                    speechRate = it
                    prefs.edit().putFloat("tts_rate", it).apply()
                },
                valueRange = 0.7f..1.5f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Speech rate ${"%.1f".format(Locale.US, speechRate)}×",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            SmButton(
                "Review request details",
                onClick = { if (transcript.isNotBlank()) onTranscriptConfirmed(transcript.trim()) },
                enabled = transcript.isNotBlank() && !capture.listening && !capture.preparing
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Speech is processed on this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
        }
    }
}
