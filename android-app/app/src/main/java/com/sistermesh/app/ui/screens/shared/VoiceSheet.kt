@file:OptIn(ExperimentalMaterial3Api::class)

package com.sistermesh.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sistermesh.app.data.Role
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** UI-only stand-in for the offline speech-to-text / text-to-speech demo (FR-08). */
@Composable
fun VoiceSheet(role: Role, onDismiss: () -> Unit) {
    var state by remember { mutableIntStateOf(0) } // 0 idle, 1 listening, 2 recognised
    val scope = rememberCoroutineScope()
    val heard = if (role == Role.CHW) "Find a sister with O negative blood" else "Mark me as available"
    val reply = if (role == Role.CHW) "Okay. Looking for O negative sisters nearby." else "Done. You are now available."

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Voice assistant", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                when (state) { 0 -> "Tap the mic and say what you need."; 1 -> "Listening…"; else -> "Here is what I heard." },
                style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            val mic: @Composable () -> Unit = {
                Surface(
                    onClick = { if (state != 1) { state = 1; scope.launch { delay(2200); state = 2 } } },
                    shape = CircleShape, color = if (state == 1) Coral else Teal, modifier = Modifier.size(84.dp)
                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Start listening", tint = Color.White, modifier = Modifier.size(34.dp)) } }
            }
            if (state == 1) Pulse(Modifier.size(180.dp), Coral) { mic() } else Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) { mic() }
            if (state == 2) {
                Spacer(Modifier.height(8.dp))
                SmCard {
                    Text("You said", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Text("“$heard”", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(14.dp))
                    HairLine()
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeUp, null, tint = Teal)
                        Spacer(Modifier.width(10.dp))
                        Text(reply, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                SmButton("Done", onDismiss)
            }
            Spacer(Modifier.height(8.dp))
            Text("Offline speech runs on your phone. Prototype preview only.", style = MaterialTheme.typography.labelMedium, color = Muted, textAlign = TextAlign.Center)
        }
    }
}
