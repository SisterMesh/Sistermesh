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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.AppViewModel
import com.sistermesh.app.data.BloodRequest
import com.sistermesh.app.data.Decision
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.data.Role
import com.sistermesh.app.data.Urgency
import com.sistermesh.app.ui.components.BackHeader
import com.sistermesh.app.ui.components.BloodGroupPicker
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
import com.sistermesh.app.ui.theme.Teal

@Composable
fun UnifiedHomeScreen(vm: AppViewModel, nav: NavController) {
    val isWorker = vm.profile?.role == Role.CHW
    val pending = vm.facilityRequests.count {
        it.status == RequestStatus.PENDING_FACILITY_VERIFICATION ||
            it.status == RequestStatus.NEEDS_INFORMATION
    }
    Page(bottomBar = { TabBar("home", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text(if (isWorker) "Facility coordination" else "SisterMesh", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Text(
            if (isWorker) "Review requests from your assigned facility." else "Request blood or volunteer to donate, with one account.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        Spacer(Modifier.height(16.dp))
        NetworkPill(vm.meshState)
        if (!vm.isOnline) {
            Spacer(Modifier.height(10.dp))
            Notice("Offline mode is on. New requests stay private until a facility verifies them.")
        }

        if (isWorker) {
            SectionTitle("Facility requests")
            SmCard {
                Text("$pending waiting for review", style = MaterialTheme.typography.titleLarge, color = Teal)
                Text("Only requests assigned to your verified facility appear in this queue.", color = Muted)
                Spacer(Modifier.height(14.dp))
                SmButton("Open review queue", { nav.navigate("facility_requests") })
            }
            SectionTitle("Create a facility request")
            SmCard {
                Text("Submit a request for facility verification.", color = Muted)
                Spacer(Modifier.height(12.dp))
                SmButton("Create request", { nav.navigate("create_request") }, icon = Icons.Rounded.LocalHospital)
            }
        } else {
            SectionTitle("What do you need today?")
            SmCard {
                Text("Request blood", style = MaterialTheme.typography.titleLarge)
                Text("Choose a facility. Its staff will review your request before donors can see it.", color = Muted)
                Spacer(Modifier.height(12.dp))
                SmButton("Start a request", { nav.navigate("create_request") }, icon = Icons.Rounded.LocalHospital)
            }
            Spacer(Modifier.height(12.dp))
            SmCard {
                Text("Looking to help?", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (vm.profile?.bloodGroup.isNullOrBlank()) "Join the donor network when you are ready."
                    else "See approved requests and choose when you can help.",
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
                SmButton(
                    if (vm.profile?.bloodGroup.isNullOrBlank()) "Explore donating" else "Find blood requests",
                    { nav.navigate(if (vm.profile?.bloodGroup.isNullOrBlank()) "donate" else "find_blood") },
                    style = com.sistermesh.app.ui.components.BtnStyle.Soft,
                    icon = Icons.Rounded.Favorite
                )
            }
            SectionTitle("Your activity", action = "View all", onAction = { nav.navigate("activity") })
            Text("${vm.requests.size} requests · ${vm.incoming.count { it.decision != Decision.NONE }} donation responses", color = Muted)
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
        Text("Only facility-approved requests are shown here.", color = Muted)
        Spacer(Modifier.height(12.dp))
        NetworkPill(vm.meshState)
        if (vm.profile?.role == Role.CHW) {
            Spacer(Modifier.height(14.dp))
            Notice("Donor participation uses an individual account. This verified health-worker account remains separate.")
        } else if (vm.profile?.bloodGroup.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Notice("Join the donor network in Donate to respond to requests.")
            Spacer(Modifier.height(10.dp))
            SmButton("Set up donor profile", { nav.navigate("donate") })
        } else {
            SectionTitle("${requests.size} open requests")
            if (requests.isEmpty()) {
                SmCard {
                    Text("No matching approved requests yet.", style = MaterialTheme.typography.titleMedium)
                    Text("You can check again later. Pending facility reviews are never shown in this list.", color = Muted)
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
        if (profile?.role == Role.CHW) {
            SmCard {
                Text("Donor accounts are separate", style = MaterialTheme.typography.titleLarge)
                Text("This verified health-worker account is for facility coordination. Use an individual account to volunteer as a donor.", color = Muted)
            }
        } else if (!joined) {
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
    val isWorker = vm.profile?.role == Role.CHW
    val outgoing = if (isWorker) vm.facilityRequests else vm.requests
    val donations = vm.incoming.filter { it.decision != Decision.NONE || it.confirmed }
    Page(bottomBar = { TabBar("activity", nav) }) {
        Spacer(Modifier.height(18.dp))
        Text("My activity", style = MaterialTheme.typography.headlineLarge)
        Text("Requests and donation responses stay available on this device.", color = Muted)
        SectionTitle(if (isWorker) "Facility request history" else "My requests")
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
                if (!isWorker && request.status == RequestStatus.NEEDS_INFORMATION) {
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
        if (!isWorker) {
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
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun FacilityReviewScreen(vm: AppViewModel, nav: NavController) {
    val pending = vm.facilityRequests.filter {
        it.status == RequestStatus.PENDING_FACILITY_VERIFICATION || it.status == RequestStatus.NEEDS_INFORMATION
    }
    Page(bottomBar = { TabBar("home", nav) }) {
        BackHeader("Facility review", { nav.popBackStack() })
        Text("Requests assigned to your verified facility", color = Muted)
        if (vm.profile?.facilityId.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Notice("A verified facility assignment is required to access this queue.")
        }
        SectionTitle("${pending.size} awaiting review")
        if (pending.isEmpty()) {
            SmCard { Text("No requests awaiting review.", color = Muted) }
        } else pending.forEach { request ->
            SmCard(onClick = { nav.navigate("review_request/${request.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(request.bloodGroup, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Tag(request.status.label, request.status.tone(), request.status.icon())
                }
                Text("${request.urgency.name} · ${request.units} unit${if (request.units == 1) "" else "s"}", color = Muted)
                Text(if (request.isForSelf) "For requester" else "For ${request.beneficiaryName ?: "another person"}", color = Muted)
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RequestReviewScreen(vm: AppViewModel, nav: NavController, requestId: String) {
    val request = vm.request(requestId)
    var reason by remember(requestId) { mutableStateOf("") }
    var tried by remember(requestId) { mutableStateOf(false) }
    Page(footer = {
        SmButton("Approve request", {
            request?.serverUpdatedAt?.let { vm.reviewRequest(requestId, "APPROVE", null, it) }
            nav.popBackStack()
        }, enabled = request?.serverUpdatedAt != null)
        SmButton("Request more information", {
            tried = true
            if (reason.isNotBlank()) {
                request?.serverUpdatedAt?.let { vm.reviewRequest(requestId, "REQUEST_INFORMATION", reason, it) }
                nav.popBackStack()
            }
        }, style = com.sistermesh.app.ui.components.BtnStyle.Outline, enabled = request?.serverUpdatedAt != null && reason.isNotBlank())
        SmButton("Reject request", {
            tried = true
            if (reason.isNotBlank()) {
                request?.serverUpdatedAt?.let { vm.reviewRequest(requestId, "REJECT", reason, it) }
                nav.popBackStack()
            }
        }, style = com.sistermesh.app.ui.components.BtnStyle.Danger, enabled = request?.serverUpdatedAt != null && reason.isNotBlank())
    }) {
        BackHeader("Review request", { nav.popBackStack() })
        if (request == null) {
            Notice("This request is not available on this device.")
        } else {
            Tag(request.status.label, request.status.tone(), request.status.icon())
            SectionTitle("Request details")
            Text("${request.bloodGroup} · ${request.urgency.name} · ${request.units} unit${if (request.units == 1) "" else "s"}")
            Text(request.facility, color = Muted)
            SectionTitle("Beneficiary")
            Text(if (request.isForSelf) "The requester is the patient." else request.beneficiaryName ?: "Name not provided")
            request.beneficiaryRelationship?.let { Text(it, color = Muted) }
            request.additionalInfo?.takeIf(String::isNotBlank)?.let {
                SectionTitle("Additional information")
                Text(it)
            }
            request.reviewReason?.let {
                SectionTitle("Previous review note")
                Text(it, color = Coral)
            }
            Spacer(Modifier.height(12.dp))
            SmField("Reason for requesting information or rejecting", reason, { reason = it })
            if (tried && reason.isBlank()) Text("Enter a reason.", color = Coral)
            Spacer(Modifier.height(12.dp))
            Notice("Beneficiary details are private to this facility and the requester. Donors do not receive them.")
        }
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
            "Resubmit for facility review",
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
                Notice("Facility asked for an update: $it")
                Spacer(Modifier.height(12.dp))
            }
            Text("Update the request details and send it back for verification.", color = Muted)
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
            SmField("Additional information for the facility", information, { information = it })
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun UnifiedCreateRequestScreen(vm: AppViewModel, nav: NavController) {
    val profile = vm.profile
    val isWorker = profile?.role == Role.CHW
    var group by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(Urgency.URGENT) }
    var units by remember { mutableStateOf(1) }
    var isForSelf by remember { mutableStateOf(!isWorker) }
    var beneficiaryName by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var information by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var selectedFacilityId by remember { mutableStateOf(profile?.facilityId.orEmpty()) }
    val selectedFacility = vm.facilities.firstOrNull { it.id == selectedFacilityId }
    val facilityReady = if (isWorker) !profile?.facilityId.isNullOrBlank() else selectedFacility != null
    val valid = group.isNotBlank() && facilityReady && (isForSelf || beneficiaryName.isNotBlank())

    Page(footer = {
        SmButton(
            "Submit for facility verification",
            {
                tried = true
                if (valid) {
                    vm.createRequest(
                        group,
                        urgency,
                        units,
                        selectedFacilityId,
                        isForSelf,
                        beneficiaryName.takeIf { !isForSelf },
                        relationship.takeIf { !isForSelf && it.isNotBlank() },
                        information.takeIf(String::isNotBlank)
                    ) { nav.navigate("activity") }
                }
            },
            enabled = valid
        )
    }) {
        BackHeader("New blood request", { nav.popBackStack() })
        Text(
            "The selected facility reviews your request before it appears to donors.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        if (!vm.isOnline) {
            Spacer(Modifier.height(10.dp))
            Notice("This request will stay on your phone and will not be shared over Bluetooth or shown to donors before verification.")
        }
        Spacer(Modifier.height(18.dp))
        if (!isWorker) {
            Text("Health facility", style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedFacility?.label ?: "Choose an active facility", modifier = Modifier.weight(1f), color = Ink)
                    androidx.compose.material3.Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    vm.facilities.forEach { facility ->
                        DropdownMenuItem(
                            text = { Text(facility.label) },
                            onClick = { selectedFacilityId = facility.id; expanded = false }
                        )
                    }
                }
            }
            if (vm.facilities.isEmpty()) {
                Text(
                    vm.facilityCatalogError ?: "Connect to the internet once to cache the active facility list.",
                    color = Coral,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Text("Assigned facility", style = MaterialTheme.typography.titleMedium)
            Text(profile?.facility?.ifBlank { "Facility assignment pending" }.orEmpty(), color = Muted)
        }

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
        if (!isWorker) {
            SectionTitle("Who needs blood?")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { isForSelf = true }) { Tag("Myself", if (isForSelf) Tone.Brand else Tone.Neutral) }
                TextButton(onClick = { isForSelf = false }) { Tag("Someone else", if (!isForSelf) Tone.Brand else Tone.Neutral) }
            }
        }
        if (!isForSelf) {
            SmField("Patient or beneficiary name", beneficiaryName, { beneficiaryName = it })
            if (tried && beneficiaryName.isBlank()) Text("Enter the beneficiary name.", color = Coral)
            Spacer(Modifier.height(8.dp))
            SmField("Relationship (optional)", relationship, { relationship = it })
        }
        Spacer(Modifier.height(8.dp))
        SmField("Additional information for the facility (optional)", information, { information = it })
        Spacer(Modifier.height(12.dp))
        Notice("Donors see only the blood group, urgency, number of units, and approved facility. Names and notes stay private.")
        Spacer(Modifier.height(24.dp))
    }
}
