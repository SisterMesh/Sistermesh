package com.sistermesh.app.ui.screens.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sistermesh.app.data.*
import com.sistermesh.app.communication.NearbyPermissions
import com.sistermesh.app.SisterMeshApplication
import com.sistermesh.app.ui.components.*
import com.sistermesh.app.ui.theme.*
import kotlinx.coroutines.delay

// S01
@Composable
fun SplashScreen(isReady: Boolean = true, onDone: () -> Unit) {
    LaunchedEffect(isReady) {
        if (isReady) {
            delay(1800)
            onDone()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Teal).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Logo(88.dp, onDark = true)
        Spacer(Modifier.height(24.dp))
        Text("SisterMesh", style = MaterialTheme.typography.displayLarge, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text(
            "Women helping women. Even without internet.",
            style = MaterialTheme.typography.bodyLarge, color = Mint, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(48.dp))
        LinearProgressIndicator(
            modifier = Modifier.width(96.dp).clip(RoundedCornerShape(50)),
            color = Mint, trackColor = Color.White.copy(alpha = 0.15f)
        )
    }
}

// S02
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Page(footer = { SmButton("Get started", onStart) }) {
        Spacer(Modifier.height(28.dp))
        Logo(52.dp)
        Spacer(Modifier.height(40.dp))
        Text("Together, we can save lives.", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Find blood or offer to donate, even when the internet is down.", style = MaterialTheme.typography.bodyLarge, color = Muted)
        Spacer(Modifier.height(36.dp))
        Feature(Icons.Rounded.WifiOff, "Works offline", "Nearby phone sharing.")
        Feature(Icons.Rounded.Favorite, "You decide", "Helping is always voluntary.")
        Feature(Icons.Rounded.Lock, "Private by design", "Personal contact details stay private.")
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

// S03
@Composable
fun UseModeScreen(vm: AppViewModel, onBack: () -> Unit, onContinue: (UseMode) -> Unit) {
    val mode = vm.pendingUseMode
    Page(footer = { SmButton("Continue", { mode?.let(onContinue) }, enabled = mode != null) }) {
        BackHeader("", onBack)
        Spacer(Modifier.height(12.dp))
        Text("How will you use SisterMesh?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(28.dp))
        RoleCard(Icons.Rounded.Search, "I want to find blood", "Create a request for yourself or someone else.", mode == UseMode.FIND_BLOOD) { vm.pendingUseMode = UseMode.FIND_BLOOD }
        Spacer(Modifier.height(14.dp))
        RoleCard(Icons.Rounded.Favorite, "I want to donate blood", "See nearby requests and choose when to help.", mode == UseMode.DONATE_BLOOD) { vm.pendingUseMode = UseMode.DONATE_BLOOD }
    }
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    SmCard(onClick = onClick, tint = if (selected) MintSoft else Paper, border = if (selected) Teal else Line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (selected) Teal else Fog), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) Color.White else Muted)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Teal, unselectedColor = Line))
        }
    }
}

// S04
@Composable
fun DonorRegistrationScreen(vm: AppViewModel, mode: UseMode, onBack: () -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var wantsToDonate by remember(mode) { mutableStateOf(mode == UseMode.DONATE_BLOOD) }
    var consent by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    val ageNum = age.toIntOrNull()
    val ageError = when {
        !wantsToDonate -> null
        age.isBlank() -> "Enter your age"
        ageNum == null || ageNum < 18 -> "You must be 18 or older to register"
        else -> null
    }
    val valid = name.isNotBlank() &&
        (!wantsToDonate || (ageError == null && group.isNotBlank() && consent))

    Page(footer = {
        SmButton("Register", {
            tried = true
            if (valid) {
                vm.register(Profile(
                    role = Role.INDIVIDUAL,
                    name = name.trim(),
                    useMode = mode,
                    bloodGroup = if (wantsToDonate) group else "",
                    area = area.trim(),
                    phone = phone.trim()
                )) {
                    onDone()
                }
            }
        })
    }) {
        BackHeader(if (mode == UseMode.FIND_BLOOD) "Find blood" else "Donate blood", onBack)
        Spacer(Modifier.height(20.dp))
        SmField("Name or display name", name, { name = it }, error = if (tried && name.isBlank()) "Enter a name" else null)
        Spacer(Modifier.height(4.dp))
        if (mode == UseMode.FIND_BLOOD) {
            CheckRow(wantsToDonate, { wantsToDonate = it; consent = false }, "I also want to volunteer as a blood donor.")
        }
        if (wantsToDonate) {
            Spacer(Modifier.height(8.dp))
            SmField("Age", age, { age = it.filter(Char::isDigit).take(2) }, keyboard = KeyboardType.Number, error = if (tried) ageError else null)
            Spacer(Modifier.height(4.dp))
            Text("Blood group", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            BloodGroupPicker(group, { group = it }, error = tried && group.isBlank())
            if (tried && group.isBlank()) Text("Select your blood group", style = MaterialTheme.typography.labelMedium, color = Coral, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            CheckRow(consent, { consent = it }, "I agree to join the voluntary donor network. I can say no to any request.", error = tried && !consent)
            if (tried && !consent) Text("Consent is required to join the donor network", style = MaterialTheme.typography.labelMedium, color = Coral)
        }
        Spacer(Modifier.height(8.dp))
        SmField("Community or area (optional)", area, { area = it })
        Spacer(Modifier.height(4.dp))
        SmField("Phone number (optional)", phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, keyboard = KeyboardType.Phone)
        if (wantsToDonate) {
            Spacer(Modifier.height(12.dp))
            Notice("A health professional assesses donation eligibility.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// S06
private data class Perm(val title: String, val icon: ImageVector)

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SisterMeshApplication
    var missing by remember { mutableStateOf(NearbyPermissions.missing(context)) }
    var permissionAttempted by remember { mutableStateOf(false) }
    val bluetoothEnabled = remember {
        mutableStateOf(runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false))
    }
    val nearbyState by app.nearbyManager.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refresh() {
        missing = NearbyPermissions.missing(context)
        bluetoothEnabled.value = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
        app.nearbyManager.refreshEnvironment()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
    }
    val all = missing.isEmpty()
    val bluetoothPermissionNeeded = Build.VERSION.SDK_INT >= 31 &&
        Manifest.permission.BLUETOOTH_CONNECT in missing
    val bluetoothStatus = when {
        bluetoothPermissionNeeded -> "Permission needed"
        bluetoothEnabled.value -> "On"
        else -> "Off"
    }
    Page(footer = {
        SmButton(if (all) "Continue" else "Enable nearby access", {
            if (all) onDone() else {
                permissionAttempted = true
                permissionLauncher.launch(missing.toTypedArray())
            }
        })
        if (all) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { app.nearbyManager.retry() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Retry nearby connection") }
        } else {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Continue without nearby access")
            }
            if (permissionAttempted) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open app settings") }
            }
        }
    }) {
        Spacer(Modifier.height(24.dp))
        SmCard {
            Text("Nearby connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(nearbyState.statusText, color = Muted)
            nearbyState.lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Coral, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        val rows = buildList {
            add(Perm("Bluetooth", Icons.Rounded.Bluetooth) to bluetoothStatus)
            add(
                Perm("Wi-Fi", Icons.Rounded.Wifi) to if (nearbyState.wifiEnabled) "On" else "Off"
            )
            add(
                Perm("Nearby device access", Icons.Rounded.Wifi) to if (all) "Allowed" else "Needed"
            )
            if (Build.VERSION.SDK_INT < 33) {
                add(
                    Perm("Location permission", Icons.Rounded.LocationOn) to
                        if (Manifest.permission.ACCESS_FINE_LOCATION !in missing) "Allowed" else "Needed"
                )
            } else {
                add(
                    Perm("Location", Icons.Rounded.LocationOn) to "Not needed"
                )
            }
        }
        rows.forEach { (p, status) ->
            SmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mint), contentAlignment = Alignment.Center) {
                        Icon(p.icon, null, tint = Teal, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Tag(
                        status,
                        if (status == "Allowed" || status == "On" || status == "Not needed") Tone.Good else Tone.Neutral,
                        if (status == "Allowed" || status == "On" || status == "Not needed") Icons.Rounded.Check else Icons.Rounded.Close
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!bluetoothEnabled.value && !bluetoothPermissionNeeded) {
            TextButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Bluetooth settings") }
        }
        if (!nearbyState.wifiEnabled) {
            TextButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Wi-Fi settings") }
        }
    }
}
