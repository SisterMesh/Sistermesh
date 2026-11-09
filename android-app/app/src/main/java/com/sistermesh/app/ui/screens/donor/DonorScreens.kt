package com.sistermesh.app.ui.screens.donor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.*
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.screens.shared.VoiceSheet
import com.sistermesh.app.ui.theme.*

// S07
@Composable
fun DonorHomeScreen(vm: AppViewModel, nav: NavController) {
    val p = vm.profile ?: return
    var voice by remember { mutableStateOf(false) }
    val pending = vm.incoming.filter { it.decision == Decision.NONE }
    val handled = vm.incoming.filter { it.decision != Decision.NONE }

    Page(bottomBar = { TabBar("home", nav) }) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hello, ${p.name.substringBefore(' ')}.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                NetworkPill(vm.meshState)
            }
            IconBubble(Icons.Rounded.Mic, { voice = true }, "Voice assistant")
        }
        Spacer(Modifier.height(22.dp))

        val on = vm.available
        SmCard(tint = if (on) Teal else Paper, border = if (on) Teal else Line) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (on) "Available" else "Not available",
                        style = MaterialTheme.typography.headlineMedium, color = if (on) Color.White else Ink
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (on) "Nearby clinics can send you requests." else "You won't receive requests until you switch back on.",
                        style = MaterialTheme.typography.bodyMedium, color = if (on) Mint else Muted
                    )
                }
                Spacer(Modifier.width(12.dp))
                SmSwitch(on, { vm.updateAvailability(it) }, onDark = false)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(vm.incoming.size.toString(), "Received", Modifier.weight(1f))
            StatCard(vm.incoming.count { it.decision == Decision.ACCEPTED }.toString(), "Accepted", Modifier.weight(1f))
            StatCard(vm.receipts.size.toString(), "Receipts", Modifier.weight(1f))
        }

        SectionTitle("Requests near you")
        when {
            !vm.available -> SmCard(tint = Fog, border = Fog) {
                Text("You're paused.", style = MaterialTheme.typography.titleMedium)
                Text("Switch availability on to see new requests.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            pending.isEmpty() -> SmCard(tint = MintSoft, border = Mint) {
                Text("You're all set.", style = MaterialTheme.typography.titleMedium, color = Teal)
                Text("We'll let you know when someone nearby needs your help.", style = MaterialTheme.typography.bodyMedium, color = Teal)
            }
            else -> pending.forEach {
                IncomingCard(it) { nav.navigate("incoming/${it.id}") }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (handled.isNotEmpty()) {
            SectionTitle("Recent activity", action = "See all") { nav.navigate("history") }
            handled.take(3).forEach { r ->
                val eventType = when (r.decision) {
                    Decision.ACCEPTED -> "donor-response.accept"
                    Decision.DECLINED -> "donor-response.decline"
                    Decision.WITHDRAWN -> "donor.withdraw"
                    else -> ""
                }
                val delivery = vm.meshState.messageDeliveryStates["$eventType:${r.id}"]
                SmCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BloodBadge(r.bloodGroup, 40.dp, Fog, Muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.facility, style = MaterialTheme.typography.titleMedium)
                            Text(r.receivedAt, style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                        when {
                            r.confirmed -> Tag("Donated", Tone.Good, Icons.Rounded.Verified)
                            r.decision == Decision.ACCEPTED -> Tag("You accepted", Tone.Good, Icons.Rounded.Check)
                            r.decision == Decision.WITHDRAWN -> Tag("Withdrawn", Tone.Neutral, Icons.Rounded.Close)
                            else -> Tag("You declined", Tone.Neutral, Icons.Rounded.Close)
                        }
                    }
                    if (delivery != null) {
                        Text(
                            when (delivery) {
                                "DELIVERED" -> "The intended device confirmed receipt."
                                "STORED_BY_PEER" -> "A nearby peer stored your response; destination delivery is not confirmed."
                                "RELAYED" -> "Your response was relayed; destination delivery is not confirmed."
                                "TRANSMITTED" -> "Nearby transport accepted the send; no peer receipt yet."
                                "EXPIRED" -> "The response expired before delivery was confirmed."
                                else -> "Your response is waiting for a nearby connection."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
    }
    if (voice) VoiceSheet(Role.INDIVIDUAL) { voice = false }
}

@Composable
private fun IncomingCard(r: IncomingRequest, onOpen: () -> Unit) {
    SmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BloodBadge(r.bloodGroup, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.facility, style = MaterialTheme.typography.titleMedium)
                Text(r.distance, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(r.urgency.label, if (r.urgency == Urgency.CRITICAL) Tone.Danger else Tone.Warn, Icons.Rounded.Bolt)
            Tag(r.receivedAt, Tone.Neutral, Icons.Rounded.Schedule)
        }
        Spacer(Modifier.height(16.dp))
        SmButton("View request", onOpen, style = BtnStyle.Soft)
    }
}

// S11
@Composable
fun IncomingRequestScreen(vm: AppViewModel, nav: NavController, id: String) {
    val r = vm.incomingById(id)
    Page(footer = {
        if (r != null && r.decision == Decision.NONE) {
            SmButton("I can help", {
                vm.respond(id, true) {
                    vm.clearMessages()
                    nav.navigate("coordinate/$id/-") { popUpTo("incoming/$id") { inclusive = true } }
                }
            }, icon = Icons.Rounded.Check)
            SmButton("Not available", {
                vm.respond(id, false) { nav.popBackStack() }
            }, style = BtnStyle.Outline)
        } else SmButton("Back to home", { nav.popBackStack() }, style = BtnStyle.Outline)
    }) {
        BackHeader("Request", { nav.popBackStack() })
        if (r == null) { Text("This request is no longer available."); return@Page }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BloodBadge(r.bloodGroup, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("A sister nearby needs your help.", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        SmCard {
            InfoRow("Blood group needed", r.bloodGroup)
            HairLine()
            InfoRow("Units", r.units.toString())
            HairLine()
            InfoRow("Facility", r.facility)
            HairLine()
            InfoRow("Distance", r.distance.removePrefix("About "))
            HairLine()
            InfoRow("Urgency", r.urgency.label)
            HairLine()
            InfoRow("Time left", r.expiresIn)
        }
        Spacer(Modifier.height(16.dp))
        Notice(
            "Your response shares only a short device pseudonym and your choice. Your name, phone number, and location stay private.",
            Icons.Rounded.Lock, Tone.Good
        )
        Spacer(Modifier.height(12.dp))
        Text("Both choices are equally fine. You're never obliged to help.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(24.dp))
    }
}
