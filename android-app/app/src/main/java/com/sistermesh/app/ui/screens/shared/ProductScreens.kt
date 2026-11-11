package com.sistermesh.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.AppViewModel
import com.sistermesh.app.data.BloodRequest
import com.sistermesh.app.data.Decision
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.data.Role
import com.sistermesh.app.data.Urgency
import com.sistermesh.app.data.UseMode
import com.sistermesh.app.ui.components.BackHeader
import com.sistermesh.app.ui.components.BloodGroupPicker
import com.sistermesh.app.ui.components.FacilityMapPicker
import com.sistermesh.app.ui.components.CheckRow
import com.sistermesh.app.ui.components.NetworkPill
import com.sistermesh.app.ui.components.Notice
import com.sistermesh.app.ui.components.Page
import com.sistermesh.app.ui.components.SectionTitle
import com.sistermesh.app.ui.components.SmButton
import com.sistermesh.app.ui.components.SmCard
import com.sistermesh.app.ui.components.SmField
import com.sistermesh.app.ui.components.TabBar
import com.sistermesh.app.ui.components.Tag
import com.sistermesh.app.ui.components.Tone
import com.sistermesh.app.ui.components.icon
import com.sistermesh.app.ui.components.tone
import com.sistermesh.app.ui.theme.Coral
import com.sistermesh.app.ui.theme.Ink
import com.sistermesh.app.ui.theme.Muted
import com.sistermesh.app.ui.theme.Mint
import com.sistermesh.app.ui.theme.MintSoft
import com.sistermesh.app.ui.theme.Teal
import com.sistermesh.app.voice.OfflineSpeechOutput
import com.sistermesh.app.voice.RequestVoiceParser

@Composable
fun UnifiedHomeScreen(vm: AppViewModel, nav: NavController) {
    val donorMode = vm.profile?.useMode == UseMode.DONATE_BLOOD
    Page(bottomBar = { TabBar("home", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text(if (donorMode) "Donate blood" else "Find blood", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Text(
            if (donorMode) "See nearby requests and choose whether you can help." else "Create a request for yourself or someone else.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        Spacer(Modifier.height(16.dp))
        NetworkPill(vm.meshState)
        if (!vm.isOnline) {
            Spacer(Modifier.height(10.dp))
            Notice("Offline mode is on. Requests are saved on this phone and shared with nearby donors.")
        }
        if (!donorMode) {
            SectionTitle("Need blood?")
            SmCard {
                Text("Create a blood request", style = MaterialTheme.typography.titleLarge)
                Text("Add the donation facility and location so donors know where to go.", color = Muted)
                Spacer(Modifier.height(12.dp))
                SmButton("Request blood", { nav.navigate("create_request") }, icon = Icons.Rounded.LocalHospital)
            }
        } else {
            SectionTitle("Ready to help?")
            SmCard {
                Text("Find blood requests", style = MaterialTheme.typography.titleLarge)
                Text("Review the blood group, units, and donation location before responding.", color = Muted)
                Spacer(Modifier.height(12.dp))
                SmButton("Browse requests", { nav.navigate("find_blood") }, icon = Icons.Rounded.Favorite)
            }
        }
        if (donorMode) {
            SectionTitle("Your activity", action = "View all", onAction = { nav.navigate("activity") })
            Text("${vm.incoming.size} requests · ${vm.incoming.count { it.decision != Decision.NONE }} responses", color = Muted)
        } else {
            SectionTitle("Want to donate too?")
            SmCard {
                Text("You can volunteer as a donor at any time.", color = Muted)
                Spacer(Modifier.height(10.dp))
                SmButton("Donate blood", { nav.navigate("donate") }, style = com.sistermesh.app.ui.components.BtnStyle.Soft, icon = Icons.Rounded.Favorite)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun FindBloodScreen(vm: AppViewModel, nav: NavController) {
    val requests = vm.incoming.filter {
        it.status == RequestStatus.OPEN || it.status == RequestStatus.PARTIALLY_FILLED
    }
    Page(bottomBar = { TabBar("find_blood", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("Find blood", style = MaterialTheme.typography.headlineLarge)
        Text("Review the facility and location before choosing to help.", color = Muted)
        Spacer(Modifier.height(12.dp))
        NetworkPill(vm.meshState)
        if (vm.profile?.bloodGroup.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Notice("Add your blood group and consent on the Donate tab to respond.")
            Spacer(Modifier.height(10.dp))
            SmButton("Set up donor profile", { nav.navigate("donate") })
        } else {
            SectionTitle("${requests.size} open requests")
            if (requests.isEmpty()) {
                SmCard {
                    Text("No matching requests yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Check again when nearby requests arrive.", color = Muted)
                }
            } else {
                requests.forEach { request ->
                    SmCard(onClick = { nav.navigate("incoming/${request.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(request.bloodGroup, style = MaterialTheme.typography.headlineSmall, color = Teal)
                            Spacer(Modifier.weight(1f))
                            Tag(request.status.label, request.status.tone(), request.status.icon())
                        }
                        Text("${request.urgency.name.lowercase().replaceFirstChar(Char::uppercase)} · ${request.units} unit${if (request.units == 1) "" else "s"}", color = Muted)
                        Spacer(Modifier.height(4.dp))
                        Text(request.facility, style = MaterialTheme.typography.bodyMedium)
                        if (request.facilityLocation.isNotBlank()) Text(request.facilityLocation, color = Muted)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DonateScreen(vm: AppViewModel, nav: NavController) {
    val profile = vm.profile
    var group by remember(profile?.bloodGroup) { mutableStateOf(profile?.bloodGroup.orEmpty()) }
    var area by remember(profile?.area) { mutableStateOf(profile?.area.orEmpty()) }
    var consent by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val joined = !profile?.bloodGroup.isNullOrBlank()
    val active = vm.incoming.filter {
        it.status == RequestStatus.OPEN || it.status == RequestStatus.PARTIALLY_FILLED
    }
    Page(bottomBar = { TabBar("donate", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("Donate", style = MaterialTheme.typography.headlineLarge)
        Text("Donating is optional. You decide which requests to answer.", color = Muted)
        Spacer(Modifier.height(18.dp))
        if (!joined) {
            SmCard {
                Text("Join the voluntary donor network", style = MaterialTheme.typography.titleLarge)
                Text("Your blood group is shared only to find compatible requests. Donors never see patient name or contact details.", color = Muted)
                Spacer(Modifier.height(18.dp))
                Text("Blood group", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BloodGroupPicker(group, { group = it }, error = tried && group.isBlank())
                Spacer(Modifier.height(12.dp))
                SmField("Community or area (optional)", area, { area = it })
                Spacer(Modifier.height(12.dp))
                CheckRow(consent, { consent = it }, "I agree to join the voluntary donor network.")
                if (tried && !consent) Text("Consent is required.", color = Coral)
                Spacer(Modifier.height(12.dp))
                SmButton("Join donor network", {
                    tried = true
                    if (group.isNotBlank() && consent) vm.registerAsDonor(group, area) { nav.navigate("find_blood") }
                }, enabled = group.isNotBlank() && consent)
            }
        } else {
            SmCard {
                Text("Your donor profile", style = MaterialTheme.typography.titleLarge)
                Text("Blood group ${profile?.bloodGroup}", style = MaterialTheme.typography.bodyLarge)
                if (profile?.area?.isNotBlank() == true) Text(profile.area, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Available to respond", style = MaterialTheme.typography.titleMedium)
                        Text("You can change this at any time.", color = Muted)
                    }
                    Switch(checked = vm.available, onCheckedChange = vm::updateAvailability)
                }
            }
            SectionTitle("Approved requests", action = "Find all", onAction = { nav.navigate("find_blood") })
            if (active.isEmpty()) {
                SmCard { Text("No matching approved requests right now.", color = Muted) }
            } else {
                active.take(3).forEach { request ->
                    SmCard(onClick = { nav.navigate("incoming/${request.id}") }) {
                        Text("${request.bloodGroup} · ${request.urgency.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${request.units} unit${if (request.units == 1) "" else "s"} · ${request.facility}", color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ActivityScreen(vm: AppViewModel, nav: NavController) {
    val outgoing = vm.requests
    val donations = vm.incoming.filter { it.decision != Decision.NONE || it.confirmed }
    Page(bottomBar = { TabBar("activity", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("My activity", style = MaterialTheme.typography.headlineLarge)
        Text("Requests and donation responses stay available on this device.", color = Muted)
        SectionTitle("My requests")
        if (outgoing.isEmpty()) {
            SmCard { Text("No requests yet.", color = Muted) }
        } else outgoing.forEach { request ->
            SmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(request.bloodGroup, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Tag(request.status.label, request.status.tone(), request.status.icon())
                }
                Text("${request.units} unit${if (request.units == 1) "" else "s"} · ${request.facility}", color = Muted)
                request.reviewReason?.let { Text(it, color = Coral) }
                if (request.status == RequestStatus.NEEDS_INFORMATION) {
                    Spacer(Modifier.height(10.dp))
                    SmButton("Update request", { nav.navigate("edit_request/${request.id}") }, style = com.sistermesh.app.ui.components.BtnStyle.Soft)
                }
                if (request.status.isActive) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.cancelRequest(request.id) }) { Text("Cancel request", color = Coral) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        SectionTitle("My donation responses")
        if (donations.isEmpty()) {
            SmCard { Text("No donation responses yet.", color = Muted) }
        } else donations.forEach { request ->
                SmCard(onClick = { nav.navigate("incoming/${request.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(request.bloodGroup, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Tag(
                            if (request.confirmed) "Donation confirmed" else request.decision.name.lowercase().replaceFirstChar(Char::uppercase),
                            if (request.confirmed) Tone.Good else Tone.Neutral
                        )
                    }
                    Text(request.facility, color = Muted)
                }
                Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun EditRequestSubmissionScreen(vm: AppViewModel, nav: NavController, requestId: String) {
    val request = vm.request(requestId)
    var group by remember(requestId) { mutableStateOf(request?.bloodGroup.orEmpty()) }
    var urgency by remember(requestId) { mutableStateOf(request?.urgency ?: Urgency.URGENT) }
    var units by remember(requestId) { mutableStateOf(request?.units ?: 1) }
    var isForSelf by remember(requestId) { mutableStateOf(request?.isForSelf ?: true) }
    var name by remember(requestId) { mutableStateOf(request?.beneficiaryName.orEmpty()) }
    var relationship by remember(requestId) { mutableStateOf(request?.beneficiaryRelationship.orEmpty()) }
    var information by remember(requestId) { mutableStateOf(request?.additionalInfo.orEmpty()) }
    var tried by remember(requestId) { mutableStateOf(false) }
    val valid = group.isNotBlank() && (isForSelf || name.isNotBlank())

    Page(footer = {
        SmButton(
            "Save request changes",
            {
                tried = true
                if (request != null && valid) {
                    vm.updateSubmission(request, group, urgency, units, isForSelf, name.takeIf { !isForSelf },
                        relationship.takeIf { !isForSelf && it.isNotBlank() }, information.takeIf(String::isNotBlank))
                    nav.navigate("activity") { popUpTo("activity") { inclusive = true } }
                }
            },
            enabled = request?.serverUpdatedAt != null && valid
        )
    }) {
        BackHeader("Update request", { nav.popBackStack() })
        if (request == null) {
            Notice("This request is not available on this device.")
        } else {
            request.reviewReason?.let {
                Notice("Previous note: $it")
                Spacer(Modifier.height(12.dp))
            }
            Text("Update the request details.", color = Muted)
            SectionTitle("Blood needed")
            BloodGroupPicker(group, { group = it }, error = tried && group.isBlank())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Urgency.URGENT, Urgency.CRITICAL).forEach { option ->
                    TextButton(onClick = { urgency = option }) {
                        Tag(option.name, if (urgency == option) Tone.Brand else Tone.Neutral)
                    }
                }
            }
            SectionTitle("Number of units")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (units > 1) units-- }) { Text("−") }
                Text("$units")
                TextButton(onClick = { if (units < 6) units++ }) { Text("+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { isForSelf = true }) { Tag("Myself", if (isForSelf) Tone.Brand else Tone.Neutral) }
                TextButton(onClick = { isForSelf = false }) { Tag("Someone else", if (!isForSelf) Tone.Brand else Tone.Neutral) }
            }
            if (!isForSelf) {
                SmField("Patient or beneficiary name", name, { name = it })
                if (tried && name.isBlank()) Text("Enter the beneficiary name.", color = Coral)
                Spacer(Modifier.height(8.dp))
                SmField("Relationship (optional)", relationship, { relationship = it })
            }
            Spacer(Modifier.height(8.dp))
            SmField("Additional information (optional)", information, { information = it })
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun UnifiedCreateRequestScreen(vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val speechOutput = remember(context) { OfflineSpeechOutput(context) }
    DisposableEffect(speechOutput) {
        onDispose { speechOutput.close() }
    }
    var group by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(Urgency.URGENT) }
    var units by remember { mutableStateOf(1) }
    var isForSelf by remember { mutableStateOf(true) }
    var beneficiaryName by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var information by remember { mutableStateOf("") }
    var facilityName by remember { mutableStateOf("") }
    var facilityLocation by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val coordinatesEntered = latitude.isNotBlank() || longitude.isNotBlank()
    val coordinatesValid = !coordinatesEntered ||
        (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0)
    val valid = group.isNotBlank() && facilityName.isNotBlank() &&
        facilityLocation.isNotBlank() && coordinatesValid &&
        (isForSelf || beneficiaryName.isNotBlank())

    fun applyVoiceTranscript(transcript: String) {
        voiceTranscript = transcript
        val extracted = RequestVoiceParser.parse(transcript, emptyList())
        extracted.bloodGroup?.let { group = it }
        extracted.units?.let { units = it }
    }

    LaunchedEffect(vm.pendingVoiceRequestTranscript) {
        vm.consumeVoiceRequestTranscript()?.let(::applyVoiceTranscript)
    }

    Page(footer = {
        SmButton(
            "Create blood request",
            {
                tried = true
                if (valid) {
                    vm.createRequest(
                        group,
                        urgency,
                        units,
                        facilityName.trim(),
                        facilityLocation.trim(),
                        lat,
                        lon,
                        isForSelf,
                        beneficiaryName.takeIf { !isForSelf },
                        relationship.takeIf { !isForSelf && it.isNotBlank() },
                        information.takeIf(String::isNotBlank)
                    ) {
                        speechOutput.speak("Your blood request has been created.")
                        nav.navigate("activity")
                    }
                }
            },
            enabled = valid
        )
    }) {
        BackHeader("New blood request", { nav.popBackStack() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dictate request details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { voiceSheet = true }) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Use voice")
            }
        }
        if (voiceTranscript.isNotBlank()) {
            SmCard(tint = MintSoft, border = Mint) {
                Text("Transcript reviewed", style = MaterialTheme.typography.labelMedium, color = Teal)
                Spacer(Modifier.height(4.dp))
                Text(voiceTranscript, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Check the fields below. Missing or ambiguous details were not filled automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            "Enter the place where donors should go. Your request is available without facility approval.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        if (!vm.isOnline) {
            Spacer(Modifier.height(10.dp))
            Notice("Saved on this phone. It can be shared with nearby SisterMesh phones over Bluetooth.")
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle("Donation location")
        SmField("Facility name", facilityName, { facilityName = it })
        if (tried && facilityName.isBlank()) Text("Enter the facility name.", color = Coral)
        Spacer(Modifier.height(8.dp))
        SmField("Facility address or location", facilityLocation, { facilityLocation = it })
        if (tried && facilityLocation.isBlank()) Text("Enter where donors should go.", color = Coral)
        Spacer(Modifier.height(10.dp))
        FacilityMapPicker(
            latitude = lat,
            longitude = lon,
            onPinSelected = { pickedLatitude, pickedLongitude ->
                latitude = "%.6f".format(java.util.Locale.US, pickedLatitude)
                longitude = "%.6f".format(java.util.Locale.US, pickedLongitude)
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmField("Latitude (optional)", latitude, { latitude = it }, keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            SmField("Longitude (optional)", longitude, { longitude = it }, keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        }
        if (tried && !coordinatesValid) Text("Enter a valid latitude and longitude pair.", color = Coral)

        SectionTitle("Blood needed")
        BloodGroupPicker(group, { group = it }, error = tried && group.isBlank())
        if (tried && group.isBlank()) Text("Select a blood group.", color = Coral)
        SectionTitle("Urgency")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Urgency.URGENT, Urgency.CRITICAL).forEach { option ->
                TextButton(onClick = { urgency = option }) {
                    Tag(option.name, if (urgency == option) Tone.Brand else Tone.Neutral)
                }
            }
        }
        SectionTitle("Number of units")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (units > 1) units-- }) { Text("−") }
            Text(units.toString(), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (units < 6) units++ }) { Text("+") }
        }
        SectionTitle("Who needs blood?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { isForSelf = true }) { Tag("Myself", if (isForSelf) Tone.Brand else Tone.Neutral) }
            TextButton(onClick = { isForSelf = false }) { Tag("Someone else", if (!isForSelf) Tone.Brand else Tone.Neutral) }
        }
        if (!isForSelf) {
            SmField("Patient or beneficiary name", beneficiaryName, { beneficiaryName = it })
            if (tried && beneficiaryName.isBlank()) Text("Enter the beneficiary name.", color = Coral)
            Spacer(Modifier.height(8.dp))
            SmField("Relationship (optional)", relationship, { relationship = it })
        }
        Spacer(Modifier.height(8.dp))
        SmField("Private note (optional)", information, { information = it })
        Spacer(Modifier.height(12.dp))
        Notice("Donors see the blood group, urgency, units, and donation destination. Patient names and private notes stay on the requester’s phone.")
        Spacer(Modifier.height(24.dp))
    }

    if (voiceSheet) {
        VoiceSheet(
            onTranscriptConfirmed = { transcript ->
                applyVoiceTranscript(transcript)
                voiceSheet = false
            },
            onDismiss = { voiceSheet = false }
        )
    }
}
