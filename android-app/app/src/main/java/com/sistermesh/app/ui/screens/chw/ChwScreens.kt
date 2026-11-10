package com.sistermesh.app.ui.screens.chw

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.*
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.screens.shared.VoiceSheet
import com.sistermesh.app.ui.theme.*

// S08
@Composable
fun ChwHomeScreen(vm: AppViewModel, nav: NavController) {
    val p = vm.profile ?: return
    var voice by remember { mutableStateOf(false) }
    val active = vm.requests.firstOrNull { it.status.isActive }

    Page(bottomBar = { TabBar("home", nav) }) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hello, ${p.name.substringBefore(' ')}.", style = MaterialTheme.typography.headlineLarge)
                Text(p.facility, style = MaterialTheme.typography.bodyLarge, color = Muted)
                Spacer(Modifier.height(8.dp))
                NetworkPill(vm.meshState)
            }
            IconBubble(Icons.Rounded.Mic, { voice = true }, "Voice assistant")
        }
        Spacer(Modifier.height(22.dp))

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Teal).padding(24.dp)) {
            Text("Need blood for a mother?", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Reach willing donors nearby in seconds, even offline.", style = MaterialTheme.typography.bodyLarge, color = Mint)
            Spacer(Modifier.height(22.dp))
            SmButton("Find a Sister", { nav.navigate("create_request") }, style = BtnStyle.Danger, icon = Icons.Rounded.WaterDrop, modifier = Modifier.heightIn(min = 64.dp))
        }

        if (active != null) {
            SectionTitle("Active request")
            SmCard(onClick = { nav.navigate(if (active.status == RequestStatus.SEARCHING) "broadcast/${active.id}" else "tracking/${active.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BloodBadge(active.bloodGroup)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(active.id, style = MaterialTheme.typography.titleMedium)
                        Text("${active.responses.size} ${if (active.responses.size == 1) "response" else "responses"}", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    }
                    Tag(active.status.label, active.status.tone(), active.status.icon())
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(vm.requests.size.toString(), "Requests", Modifier.weight(1f))
            StatCard(vm.requests.sumOf { it.responses.size }.toString(), "Responses", Modifier.weight(1f))
            StatCard(vm.requests.count { it.status == RequestStatus.COMPLETED }.toString(), "Completed", Modifier.weight(1f))
        }

        SectionTitle("Recent requests", action = "See all") { nav.navigate("history") }
        vm.requests.take(3).forEach {
            RequestRow(it) { nav.navigate(if (it.status == RequestStatus.SEARCHING) "broadcast/${it.id}" else "tracking/${it.id}") }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
    if (voice) VoiceSheet(
        role = Role.CHW,
        onTranscriptConfirmed = { transcript ->
            vm.stageVoiceRequestTranscript(transcript)
            voice = false
            nav.navigate("create_request")
        },
        onDismiss = { voice = false }
    )
}

// S09
@Composable
fun CreateRequestScreen(vm: AppViewModel, nav: NavController) {
    val p = vm.profile ?: return
    var group by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(Urgency.CRITICAL) }
    var units by remember { mutableIntStateOf(1) }
    var note by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }

    Page(footer = {
        SmButton("Submit for facility review", {
            tried = true
            if (group.isNotBlank()) {
                vm.createRequest(group, urgency, units, note.trim()) { id ->
                    vm.startDiscovery(id)
                    nav.navigate("broadcast/$id") { popUpTo("create_request") { inclusive = true } }
                }
            }
        }, style = BtnStyle.Danger, icon = Icons.Rounded.WaterDrop)
    }) {
        BackHeader("New blood request", { nav.popBackStack() })
        Spacer(Modifier.height(16.dp))
        Text("Blood group needed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        BloodGroupPicker(group, { group = it }, error = tried && group.isBlank())
        if (tried && group.isBlank()) Text("Select the blood group needed", style = MaterialTheme.typography.labelMedium, color = Coral, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(24.dp))
        Text("Facility", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        SmCard(tint = Fog, border = Fog) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalHospital, null, tint = Teal)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(p.facility, style = MaterialTheme.typography.titleMedium)
                    Text(p.facilityLocation, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Urgency", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Segmented(listOf(Urgency.CRITICAL to "Critical", Urgency.URGENT to "Urgent"), urgency) { urgency = it }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Units requested", style = MaterialTheme.typography.titleMedium)
                Text("Between 1 and 6", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Stepper(units, { units = it })
        }

        Spacer(Modifier.height(20.dp))
        SmField("Short note (optional)", note, { note = it.take(80) }, singleLine = false)
        Spacer(Modifier.height(8.dp))
        Notice(
            "This request stays private until facility approval. Nearby donor broadcasts include only the blood group, urgency, units and facility.",
            Icons.Rounded.Lock,
            Tone.Good
        )
        Spacer(Modifier.height(24.dp))
    }
}

// S10
@Composable
fun BroadcastScreen(vm: AppViewModel, nav: NavController, id: String) {
    val r = vm.request(id)
    LaunchedEffect(id) { vm.startDiscovery(id) }
    if (r == null) { Page { Spacer(Modifier.height(24.dp)); Text("This request is no longer available.") }; return }

    val deliveryState = vm.meshState.requestDeliveryStates[id]
    val deliveryText = when (r.status) {
        RequestStatus.PENDING_FACILITY_VERIFICATION ->
            "This request is private and will not be shared with donors until a verified facility worker approves it."
        RequestStatus.NEEDS_INFORMATION ->
            "The facility requested more information. Update the request before it can be reviewed again."
        RequestStatus.REJECTED ->
            "The facility did not approve this request. It has not been shared with donors."
        else -> vm.meshState.lastError ?: when (deliveryState) {
        "TRANSMITTED" -> "Nearby transport accepted the send. A peer receipt has not been confirmed yet."
        "STORED_BY_PEER" -> "A nearby peer confirmed it stored this message. That does not confirm delivery to the intended recipient."
        "RELAYED" -> "A nearby peer relayed this message. Destination delivery is not confirmed."
        "DELIVERED" -> "The intended device confirmed receipt."
        "EXPIRED" -> "This nearby message expired before delivery was confirmed."
        "FAILED", "REJECTED" -> "This nearby message could not be sent."
        else -> "The request is saved on this phone and will send when a trusted nearby connection is available."
        }
    }
    val (title, sub) = when (r.status) {
        RequestStatus.PENDING_FACILITY_VERIFICATION ->
            "Waiting for facility review" to "Donors cannot see this request until the facility approves it."
        RequestStatus.NEEDS_INFORMATION ->
            "More information needed" to "Review the facility's feedback and update the request."
        RequestStatus.REJECTED ->
            "Request not approved" to "The request remains private and is not available to donors."
        RequestStatus.CANCELLED ->
            "Request cancelled" to "No further devices will receive it."
        RequestStatus.EXPIRED ->
            "Request expired" to "This request is no longer available to donors."
        RequestStatus.FULFILLED, RequestStatus.COMPLETED ->
            "Request fulfilled" to "The required donations have been confirmed."
        else -> when {
            r.accepted > 0 -> "A sister said yes." to "Open the responses to coordinate."
            r.responses.isNotEmpty() -> "Waiting for a response." to "A nearby donor has responded."
            vm.meshState.connectedDeviceIds.isNotEmpty() -> "Connected to nearby phones." to "Waiting for a donor response."
            vm.meshState.scanning -> "Looking for sisters nearby." to "Searching for SisterMesh devices."
            else -> "Nearby connection paused." to vm.meshState.statusText
        }
    }
    val live = r.status in setOf(
        RequestStatus.OPEN,
        RequestStatus.PARTIALLY_FILLED,
        RequestStatus.SEARCHING,
        RequestStatus.WAITING
    )

    Page(footer = {
        if (r.responses.isNotEmpty() && r.status != RequestStatus.CANCELLED) {
            SmButton("View responses", { nav.navigate("tracking/$id") { popUpTo("broadcast/$id") { inclusive = true } } })
        }
        if (r.status.isActive) SmButton("Cancel request", { vm.cancelRequest(id) }, style = BtnStyle.Outline)
        else SmButton("Back to home", { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, style = BtnStyle.Outline)
    }) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("Request ${r.id}", Tone.Neutral)
            Spacer(Modifier.weight(1f))
            NetworkPill(vm.meshState)
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (live) {
                Pulse(Modifier.size(240.dp), Teal) { BloodBadge(r.bloodGroup, 92.dp, Teal, Color.White) }
            } else {
                Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    BloodBadge(r.bloodGroup, 92.dp, if (r.status == RequestStatus.CANCELLED) Fog else Teal, if (r.status == RequestStatus.CANCELLED) Muted else Color.White)
                }
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(r.facility, style = MaterialTheme.typography.labelMedium, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(vm.meshState.nearbyDeviceCount.toString(), "Nearby found", Modifier.weight(1f))
            StatCard(vm.meshState.connectedDeviceIds.size.toString(), "Connected", Modifier.weight(1f))
            StatCard(r.responses.count { it.status == ResponseStatus.ACCEPTED || it.status == ResponseStatus.DECLINED }.toString(), "Responses", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Notice(deliveryText, Icons.Rounded.Info, Tone.Neutral)
        Spacer(Modifier.height(24.dp))
    }
}

// S12
@Composable
fun TrackingScreen(vm: AppViewModel, nav: NavController, id: String) {
    val r = vm.request(id)
    Page(footer = {
        if (r != null && r.status.isActive) SmButton("Cancel request", { vm.cancelRequest(id) }, style = BtnStyle.Outline)
    }) {
        BackHeader("Request ${r?.id.orEmpty()}", { nav.popBackStack() })
        if (r == null) return@Page
        Spacer(Modifier.height(12.dp))
        SmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BloodBadge(r.bloodGroup, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${r.units} ${if (r.units == 1) "unit" else "units"} of ${r.bloodGroup}", style = MaterialTheme.typography.titleLarge)
                    Text(r.facility, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(r.status.label, r.status.tone(), r.status.icon())
                Tag(r.urgency.label, if (r.urgency == Urgency.CRITICAL) Tone.Danger else Tone.Warn, Icons.Rounded.Bolt)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(vm.meshState.nearbyDeviceCount.toString(), "Nearby", Modifier.weight(1f))
            StatCard(vm.meshState.connectedDeviceIds.size.toString(), "Connected", Modifier.weight(1f))
            StatCard(r.accepted.toString(), "Accepted", Modifier.weight(1f))
            StatCard(r.declined.toString(), "Declined", Modifier.weight(1f))
        }
        SectionTitle("Donor responses")
        if (r.responses.isEmpty()) {
            SmCard(tint = Fog, border = Fog) {
                Text("No responses yet.", style = MaterialTheme.typography.titleMedium)
                Text("Responses appear here as nearby sisters reply.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        r.responses.forEach { d ->
            SmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(d.ref)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sister #${d.ref}", style = MaterialTheme.typography.titleMedium)
                        Text("${d.area}. ${d.distance}", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    }
                    val (tone, icon) = when (d.status) {
                        ResponseStatus.ACCEPTED -> Tone.Good to Icons.Rounded.Check
                        ResponseStatus.CONFIRMED -> Tone.Good to Icons.Rounded.Verified
                        ResponseStatus.DECLINED, ResponseStatus.EXPIRED -> Tone.Neutral to Icons.Rounded.Close
                        else -> Tone.Warn to Icons.Rounded.Schedule
                    }
                    Tag(d.status.label, tone, icon)
                }
                if (d.status == ResponseStatus.ACCEPTED && r.status != RequestStatus.CANCELLED) {
                    Spacer(Modifier.height(14.dp))
                    SmButton("Coordinate with her", {
                        vm.clearMessages()
                        nav.navigate("coordinate/${r.id}/${d.ref}")
                    }, style = BtnStyle.Soft)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// S14
@Composable
fun DonationConfirmationScreen(vm: AppViewModel, nav: NavController, id: String, ref: String) {
    var attest by remember { mutableStateOf(false) }
    val stamp = remember { nowStamp() }
    Page(footer = {
        SmButton("Confirm donation", {
            vm.confirmDonation(id, ref) { receiptId ->
                nav.navigate("receipt/$receiptId") { popUpTo("home") }
            }
        }, enabled = attest, icon = Icons.Rounded.Check)
    }) {
        BackHeader("Confirm donation", { nav.popBackStack() })
        Spacer(Modifier.height(12.dp))
        Text("Record that the donation process is complete.", style = MaterialTheme.typography.bodyLarge, color = Muted)
        Spacer(Modifier.height(20.dp))
        SmCard {
            InfoRow("Request ID", id)
            HairLine()
            InfoRow("Donor reference", "Sister #$ref")
            HairLine()
            InfoRow("Status", "Donation completed")
            HairLine()
            InfoRow("Timestamp", stamp)
        }
        Spacer(Modifier.height(16.dp))
        CheckRow(attest, { attest = it }, "I attest, as an authorised healthcare worker, that this donor completed the donation process.")
        Spacer(Modifier.height(8.dp))
        Notice("Confirmation is your decision. It is never assumed from the donor accepting.")
        Spacer(Modifier.height(24.dp))
    }
}
