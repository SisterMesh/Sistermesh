package com.sistermesh.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sistermesh.app.data.AppViewModel
import com.sistermesh.app.data.Role
import com.sistermesh.app.data.local.SyncConflictEntity
import com.sistermesh.app.data.local.SyncEventEntity
import com.sistermesh.app.sync.AccountState
import com.sistermesh.app.ui.components.BackHeader
import com.sistermesh.app.ui.components.BtnStyle
import com.sistermesh.app.ui.components.InfoRow
import com.sistermesh.app.ui.components.Page
import com.sistermesh.app.ui.components.SectionTitle
import com.sistermesh.app.ui.components.SmButton
import com.sistermesh.app.ui.components.SmCard
import com.sistermesh.app.ui.components.Tag
import com.sistermesh.app.ui.components.Tone
import com.sistermesh.app.ui.theme.Coral
import com.sistermesh.app.ui.theme.Muted
import com.sistermesh.app.ui.theme.Teal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun logoutToStartup(vm: AppViewModel, nav: NavController) {
    vm.logout {
        nav.navigate("splash") {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

@Composable
fun SyncStatusCard(vm: AppViewModel, nav: NavController) {
    val currentUser = when (val state = vm.accountState) {
        is AccountState.SignedIn -> state.user
        is AccountState.RoleMismatch -> state.user
        else -> null
    }
    SmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Backend sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(
                if (vm.isOnline) "Online" else "Offline",
                if (vm.isOnline) Tone.Good else Tone.Warn,
                if (vm.isOnline) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                currentUser == null && vm.accountState == AccountState.SavedOffline -> "Account saved. Sync resumes when you're online."
                currentUser == null -> "Connect an account to sync across devices."
                vm.accountState is AccountState.RoleMismatch -> "This account does not match the local profile."
                !vm.isAccountLinked -> "Link this phone's records to sync them."
                else -> "Signed in. Local work remains available offline."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        Spacer(Modifier.height(12.dp))
        InfoRow("Waiting to sync", vm.pendingSyncCount.toString())
        InfoRow("Needs attention", vm.failedSyncCount.toString())
        InfoRow("Conflicts", vm.conflictCount.toString())
        InfoRow("Last successful sync", vm.deviceInfo?.lastSyncAt?.let(::formatSyncTime) ?: "Not yet")
        val status = vm.syncState
        if (status != null && status.status != "IDLE" && status.status != "SUCCESS") {
            Spacer(Modifier.height(8.dp))
            Text(syncStatusLabel(status.status), style = MaterialTheme.typography.labelMedium, color = Teal)
            status.lastError?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            currentUser == null -> SmButton("Connect account", { nav.navigate("sync_account") }, icon = Icons.Rounded.Sync)
            vm.accountState is AccountState.RoleMismatch -> SmButton("Review account role", { nav.navigate("sync_account") }, style = BtnStyle.Outline)
            !vm.isAccountLinked -> SmButton("Link this phone", { nav.navigate("sync_account") }, icon = Icons.Rounded.Sync)
            else -> SmButton("Sync now", vm::syncNow, enabled = vm.syncState?.status != "RUNNING", icon = Icons.Rounded.Sync)
        }
        if (currentUser != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { nav.navigate("sync_account") }) {
                Text("Account settings", color = Teal)
            }
        }
        if (vm.conflictCount > 0 || vm.failedSyncCount > 0) {
            TextButton(onClick = { nav.navigate("sync_review") }) {
                Text("Review sync issues", color = Coral)
            }
        }
        vm.accountMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Teal)
        }
    }
}

@Composable
fun SyncAccountScreen(vm: AppViewModel, nav: NavController) {
    val profile = vm.profile
    var createAccount by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(profile?.phone.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var confirmLink by remember { mutableStateOf(false) }
    val currentUser = when (val state = vm.accountState) {
        is AccountState.SignedIn -> state.user
        is AccountState.RoleMismatch -> state.user
        else -> null
    }
    val roleMismatch = vm.accountState is AccountState.RoleMismatch
    val validPhone = phone.matches(Regex("^\\+[1-9]\\d{7,14}$"))
    val passwordBytes = password.toByteArray(StandardCharsets.UTF_8).size
    val validPassword = password.length >= 12 && passwordBytes <= 72

    Page {
        BackHeader("Account and sync", { nav.popBackStack() })
        Spacer(Modifier.height(12.dp))
        if (profile == null) {
            SmCard {
                Text("Create a Find Blood or Donate Blood profile first. The app remains usable offline.", color = Muted)
            }
        } else if (currentUser != null) {
            SmCard {
                Tag(
                    if (roleMismatch) "Role mismatch" else "Connected",
                    if (roleMismatch) Tone.Warn else Tone.Good
                )
                Spacer(Modifier.height(10.dp))
                Text(currentUser.displayName, style = MaterialTheme.typography.titleLarge)
                Text("${currentUser.phoneNumber} · ${currentUser.role.replace('_', ' ').lowercase()}", color = Muted)
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        roleMismatch -> "Log out and start again with an individual account."
                        vm.isAccountLinked -> "This phone is linked to this account. Events belonging to other accounts remain blocked."
                        else -> "Linking will associate unowned local events from this phone with this account. Events tied to another account will remain blocked."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
                if (roleMismatch) {
                    SmButton("Logout", { logoutToStartup(vm, nav) }, style = BtnStyle.Outline, enabled = !vm.accountBusy)
                } else if (vm.isAccountLinked) {
                    Tag("Local profile linked", Tone.Good)
                    Spacer(Modifier.height(8.dp))
                    SmButton("Sync now", vm::syncNow, enabled = !vm.accountBusy, icon = Icons.Rounded.Sync)
                    Spacer(Modifier.height(8.dp))
                    SmButton("Link unassigned local records", { confirmLink = true }, style = BtnStyle.Outline, enabled = !vm.accountBusy)
                    Spacer(Modifier.height(8.dp))
                    SmButton("Logout", { logoutToStartup(vm, nav) }, style = BtnStyle.Outline, enabled = !vm.accountBusy)
                } else {
                    SmButton("Link local records", { confirmLink = true }, enabled = !vm.accountBusy)
                    Spacer(Modifier.height(8.dp))
                    SmButton("Logout", { logoutToStartup(vm, nav) }, style = BtnStyle.Outline, enabled = !vm.accountBusy)
                }
            }
        } else {
            Text("Use a SisterMesh account", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Local requests and nearby sharing continue without an account. Account sync is optional.",
                color = Muted
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { createAccount = false; vm.clearAccountFeedback() }) {
                    Text("Sign in", color = if (!createAccount) Teal else Muted)
                }
                TextButton(onClick = { createAccount = true; vm.clearAccountFeedback() }) {
                    Text("Create account", color = if (createAccount) Teal else Muted)
                }
            }
            SmCard {
                Text(
                    if (createAccount) "Account type: Individual"
                    else "Sign in with the account matching this phone's local profile.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Phone number") },
                    placeholder = { Text("+2348012345678") },
                    supportingText = { Text("Use international format, for example +234…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("At least 12 characters.") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                SmButton(
                    if (createAccount) "Create account" else "Sign in",
                    onClick = {
                        if (createAccount) vm.registerAccount(phone, password)
                        else vm.loginAccount(phone, password)
                    },
                    enabled = !vm.accountBusy && validPhone && validPassword
                )
            }
            if (!validPhone && phone.isNotBlank()) {
                Text("Enter a valid international phone number.", color = Coral, style = MaterialTheme.typography.bodySmall)
            }
            if (password.isNotEmpty() && !validPassword) {
                Text("Password must be 12–72 UTF-8 bytes.", color = Coral, style = MaterialTheme.typography.bodySmall)
            }
        }

        vm.accountError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Coral, style = MaterialTheme.typography.bodyMedium)
        }
        vm.accountMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Teal, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmLink) {
        AlertDialog(
            onDismissRequest = { confirmLink = false },
            title = { Text("Link this phone's local records?") },
            text = {
                Text(
                    "Unowned queued events created on this phone will be associated with ${currentUser?.displayName}. Events already tied to another account are not transferred. No local data is deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLink = false
                    vm.linkLocalAccount()
                }) { Text("Link records", color = Teal) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLink = false }) { Text("Cancel", color = Muted) }
            }
        )
    }
}

@Composable
fun SyncReviewScreen(vm: AppViewModel, nav: NavController) {
    Page {
        BackHeader("Sync issues", { nav.popBackStack() })
        Spacer(Modifier.height(12.dp))
        SmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Rounded.Warning, null, tint = Coral)
                Spacer(Modifier.width(8.dp))
                Text("Local data is never overwritten automatically.", style = MaterialTheme.typography.titleMedium)
            }
        }
        SectionTitle("Conflicts (${vm.conflicts.size})")
        if (vm.conflicts.isEmpty()) {
            SmCard { Text("No conflicts have been reported.", color = Muted) }
        } else {
            vm.conflicts.take(100).forEach { conflict -> ConflictCard(conflict) }
        }
        SectionTitle("Events needing attention (${vm.eventsForReview.size})")
        if (vm.eventsForReview.isEmpty()) {
            SmCard { Text("No local events need attention.", color = Muted) }
        } else {
            vm.eventsForReview.take(100).forEach { event -> FailedEventCard(event) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConflictCard(conflict: SyncConflictEntity) {
    SmCard {
        Text(conflict.eventType?.let(::humanEventLabel) ?: "Sync issue", style = MaterialTheme.typography.titleMedium)
        Text(conflict.explanation, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(formatSyncTime(conflict.occurredAt), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FailedEventCard(event: SyncEventEntity) {
    SmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(humanEventLabel(event.eventType), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(
                humanSyncStatus(event.status),
                if (event.status == "CONFLICT" || event.status == "REJECTED") Tone.Danger else Tone.Warn
            )
        }
        event.serverExplanation?.takeIf(String::isNotBlank)?.let {
            Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        event.lastAttemptedAt?.let { Text(formatSyncTime(it), color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun humanEventLabel(eventType: String): String = when (eventType) {
    "request.create" -> "Blood request"
    "request.update" -> "Request update"
    "request.cancel" -> "Request cancellation"
    "donor-response.accept" -> "Donation response"
    "donor-response.decline" -> "Donation response"
    "donor.withdraw" -> "Withdrawn response"
    else -> "Sync issue"
}

private fun humanSyncStatus(status: String): String = when (status.uppercase()) {
    "CONFLICT" -> "Needs review"
    "REJECTED" -> "Not accepted"
    "FAILED" -> "Couldn't sync"
    else -> "Waiting"
}

private fun syncStatusLabel(status: String): String = when (status) {
    "RUNNING" -> "Syncing"
    "RETRYING" -> "Waiting to retry"
    "NEEDS_SIGN_IN" -> "Sign-in required"
    "ACCOUNT_LINK_REQUIRED" -> "Link this phone to an account"
    "ACCOUNT_MISMATCH" -> "Account does not match"
    "PROFILE_INCOMPLETE" -> "Complete account profile"
    "DEVICE_REVOKED" -> "This phone was revoked"
    "FAILED" -> "Sync could not be completed"
    else -> status.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

private fun formatSyncTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)
