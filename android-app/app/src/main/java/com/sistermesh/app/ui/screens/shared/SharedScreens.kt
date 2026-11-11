package com.sistermesh.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.*
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.theme.*

// S13
@Composable
fun CoordinationScreen(vm: AppViewModel, nav: NavController, id: String, ref: String) {
    val inc = vm.incomingById(id)
    val replies = MockData.donorQuickReplies
    val group = inc?.bloodGroup.orEmpty()
    val facility = inc?.facility.orEmpty()

    Page(footer = {
        SmButton("Back to home", { nav.navigate("home") { popUpTo("home") { inclusive = true } } })
    }) {
        BackHeader("You're helping", { nav.popBackStack() })
        Spacer(Modifier.height(12.dp))
        SmCard(tint = MintSoft, border = Mint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Thank you for saying yes.", style = MaterialTheme.typography.titleMedium, color = Teal)
                    Text("Follow the donation location instructions.", style = MaterialTheme.typography.bodyMedium, color = Teal)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SmCard {
            InfoRow("Blood group", group)
            HairLine()
            InfoRow("Facility", facility)
            if (inc?.facilityLocation?.isNotBlank() == true) {
                HairLine()
                InfoRow("Facility location", inc.facilityLocation)
            }
        }
        Spacer(Modifier.height(14.dp))
        Notice("Acceptance does not guarantee medical eligibility or that a donation will happen. Screening decides.")

        SectionTitle("Messages")
        vm.messages.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                Box(
                    Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (m.mine) Teal else Fog).padding(horizontal = 14.dp, vertical = 10.dp)
                ) { Text(m.text, color = if (m.mine) Color.White else Ink, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        replies.forEach { text ->
            Surface(
                onClick = { vm.send(text, true) },
                shape = RoundedCornerShape(50), color = Paper,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(16.dp), tint = Teal)
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = Ink)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// S15
@Composable
fun ReceiptScreen(vm: AppViewModel, nav: NavController, id: String) {
    val r = vm.receipt(id)
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Page(footer = {
        SmButton("Done", { nav.navigate("home") { popUpTo("home") { inclusive = true } } })
    }) {
        BackHeader("Consent receipt", { nav.popBackStack() })
        if (r == null) { Text("Receipt not found."); return@Page }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Verified, null, tint = Teal, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Voluntary consent recorded", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Thank you for helping a sister.", style = MaterialTheme.typography.bodyLarge, color = Muted)
        }
        Spacer(Modifier.height(22.dp))
        SmCard {
            InfoRow("Receipt ID", r.id)
            HairLine()
            InfoRow("Request", r.requestId)
            HairLine()
            InfoRow("Donor ID", "Sister #${r.donorRef}")
            HairLine()
            InfoRow("Consent time", r.timestamp)
            HairLine()
            InfoRow("Status", r.status)
        }
        Spacer(Modifier.height(14.dp))
        SmCard(tint = Fog, border = Fog) {
            Text("Verification hash", style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(6.dp))
            Text(r.hash.chunked(32).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            TextButton({ clip.setText(AnnotatedString(r.hash)); copied = true }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp), tint = Teal)
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied" else "Copy hash", color = Teal, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Tag("Saved on this phone", Tone.Good, Icons.Rounded.Check)
        Spacer(Modifier.height(24.dp))
    }
}

// S16
@Composable
fun HistoryScreen(vm: AppViewModel, nav: NavController) {
    val p = vm.profile ?: return
    Page(bottomBar = { TabBar("history", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("History", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        run {
            SectionTitle("Consent receipts")
            if (vm.receipts.isEmpty()) Text("No receipts yet.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            vm.receipts.forEach { r ->
                SmCard(onClick = { nav.navigate("receipt/${r.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Verified, null, tint = Teal)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.id, style = MaterialTheme.typography.titleMedium)
                            Text(r.timestamp, style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            SectionTitle("Requests received")
            vm.incoming.forEach { r ->
                SmCard(onClick = if (r.decision == Decision.NONE) ({ nav.navigate("incoming/${r.id}") }) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BloodBadge(r.bloodGroup, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.facility, style = MaterialTheme.typography.titleMedium)
                            Text(r.receivedAt, style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                        when {
                            r.confirmed -> Tag("Donated", Tone.Good, Icons.Rounded.Verified)
                            r.decision == Decision.ACCEPTED -> Tag("Accepted", Tone.Good, Icons.Rounded.Check)
                            r.decision == Decision.DECLINED -> Tag("Declined", Tone.Neutral, Icons.Rounded.Close)
                            else -> Tag("Pending", Tone.Warn, Icons.Rounded.Schedule)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// S17
@Composable
fun ProfileScreen(vm: AppViewModel, nav: NavController) {
    val p = vm.profile ?: return
    var voice by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Page(bottomBar = { TabBar("profile", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("Profile", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.name, 64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(p.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Tag(if (p.useMode == UseMode.DONATE_BLOOD) "Donate blood" else "Find blood", Tone.Good, if (p.useMode == UseMode.DONATE_BLOOD) Icons.Rounded.Favorite else Icons.Rounded.Search)
            }
        }
        Spacer(Modifier.height(22.dp))
        SmCard {
            InfoRow("Blood group", p.bloodGroup.ifBlank { "Not set" })
            HairLine()
            InfoRow("Area", p.area.ifBlank { "Not set" })
            HairLine()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Available to donate", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
                SmSwitch(vm.available, { vm.updateAvailability(it) })
            }
        }

        SectionTitle("Offline synchronization")
        SyncStatusCard(vm, nav)

        SectionTitle("Preferences")
        SmCard {
            SettingRow(Icons.Rounded.Language, "Language", vm.language) {
                vm.language = Languages[(Languages.indexOf(vm.language) + 1) % Languages.size]
            }
            HairLine()
            SettingRow(Icons.Rounded.Mic, "Voice assistant", "Try it") { voice = true }
        }

        SectionTitle("Privacy")
        Notice(
            "Your contact details and patient information are not shared with donors.",
            Icons.Rounded.Lock, Tone.Good
        )

        Spacer(Modifier.height(24.dp))
        SmButton("Logout", { confirmLogout = true }, style = BtnStyle.Outline, icon = Icons.AutoMirrored.Rounded.Logout)
        Spacer(Modifier.height(28.dp))
    }

    if (voice) VoiceSheet(
        onTranscriptConfirmed = { transcript ->
            vm.stageVoiceRequestTranscript(transcript)
            voice = false
            nav.navigate("create_request")
        },
        onDismiss = { voice = false }
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        containerColor = Paper,
        title = { Text("Log out?") },
        text = { Text("You’ll be signed out. Requests and donation records on this phone will remain saved.") },
        confirmButton = {
            TextButton({
                confirmLogout = false
                vm.logout {
                    nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                }
            }) { Text("Logout", color = Coral) }
        },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
    }
}
