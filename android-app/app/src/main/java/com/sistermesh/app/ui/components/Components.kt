@file:OptIn(ExperimentalMaterial3Api::class)

package com.sistermesh.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sistermesh.app.data.BloodRequest
import com.sistermesh.app.data.BloodGroups
import com.sistermesh.app.data.RequestStatus
import com.sistermesh.app.communication.MeshNetworkUiState
import com.sistermesh.app.communication.NearbyConnectionStatus
import com.sistermesh.app.ui.theme.*

// ───────────────────────── Layout ─────────────────────────

/** Standard screen: scrollable body, optional pinned footer (CTA) or tab bar. */
@Composable
fun Page(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    bottomBar: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxSize().background(Canvas).statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 20.dp),
            content = content
        )
        when {
            bottomBar != null -> bottomBar()
            footer != null -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = footer
            )
            else -> Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, onBack, "Back")
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun IconBubble(icon: ImageVector, onClick: () -> Unit, description: String? = null, tint: Color = Ink, bg: Color = Paper) {
    Surface(
        onClick = onClick, shape = CircleShape, color = bg,
        border = BorderStroke(1.dp, Line), modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(22.dp), tint = tint) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = MaterialTheme.typography.labelMedium, color = Teal, modifier = Modifier.clickable(onClick = onAction))
    }
}

// ───────────────────────── Surfaces ─────────────────────────

@Composable
fun SmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color = Paper,
    border: Color = Line,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = tint, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(18.dp), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = tint, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(18.dp), content = content)
        }
    }
}

enum class BtnStyle { Primary, Danger, Outline, Soft }

@Composable
fun SmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BtnStyle = BtnStyle.Primary,
    icon: ImageVector? = null
) {
    val container = when (style) {
        BtnStyle.Primary -> Teal
        BtnStyle.Danger -> Coral
        BtnStyle.Outline -> Paper
        BtnStyle.Soft -> Mint
    }
    val content = when (style) {
        BtnStyle.Primary, BtnStyle.Danger -> Color.White
        BtnStyle.Outline -> Ink
        BtnStyle.Soft -> Teal
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = Line, disabledContentColor = Muted
        ),
        border = if (style == BtnStyle.Outline) BorderStroke(1.dp, Line) else null,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ───────────────────────── Status ─────────────────────────

enum class Tone { Neutral, Good, Warn, Danger, Brand }

/** Status chip. Always carries text (and usually an icon) so status never relies on colour alone. */
@Composable
fun Tag(text: String, tone: Tone = Tone.Neutral, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> Pair(Fog, Muted)
        Tone.Good -> Pair(Mint, Teal)
        Tone.Warn -> Pair(AmberSoft, Amber)
        Tone.Danger -> Pair(CoralSoft, Coral)
        Tone.Brand -> Pair(Teal, Color.White)
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

fun RequestStatus.tone() = when (this) {
    RequestStatus.SEARCHING, RequestStatus.WAITING,
    RequestStatus.PENDING, RequestStatus.NEEDS_INFORMATION -> Tone.Warn
    RequestStatus.OPEN, RequestStatus.PARTIALLY_FILLED,
    RequestStatus.ACCEPTED, RequestStatus.COMPLETED, RequestStatus.FULFILLED -> Tone.Good
    RequestStatus.REJECTED -> Tone.Danger
    RequestStatus.CANCELLED, RequestStatus.EXPIRED -> Tone.Neutral
}

fun RequestStatus.icon() = when (this) {
    RequestStatus.SEARCHING, RequestStatus.WAITING,
    RequestStatus.PENDING, RequestStatus.NEEDS_INFORMATION -> Icons.Rounded.Schedule
    RequestStatus.OPEN, RequestStatus.PARTIALLY_FILLED -> Icons.Rounded.Favorite
    RequestStatus.ACCEPTED, RequestStatus.COMPLETED, RequestStatus.FULFILLED -> Icons.Rounded.Check
    RequestStatus.REJECTED, RequestStatus.CANCELLED, RequestStatus.EXPIRED -> Icons.Rounded.Close
}

@Composable
fun NetworkPill(state: MeshNetworkUiState? = null, modifier: Modifier = Modifier) {
    val label = when (state?.connectionStatus) {
        NearbyConnectionStatus.CONNECTED -> "${state.connectedDeviceIds.size} connected"
        NearbyConnectionStatus.CONNECTING -> if (state.nearbyDeviceCount > 0) {
            "${state.nearbyDeviceCount} nearby found"
        } else {
            "Searching nearby"
        }
        NearbyConnectionStatus.UNAVAILABLE -> "Nearby unavailable"
        NearbyConnectionStatus.PAUSED -> "Nearby paused"
        NearbyConnectionStatus.NOT_CONFIGURED, null -> if (state?.permissionsGranted == false) {
            "Nearby setup needed"
        } else {
            "Nearby not configured"
        }
    }
    val tone = when {
        state?.connectionStatus == NearbyConnectionStatus.CONNECTED -> Tone.Good
        state?.connectionStatus == NearbyConnectionStatus.CONNECTING -> Tone.Warn
        else -> Tone.Neutral
    }
    val icon = if (
        state?.connectionStatus == NearbyConnectionStatus.CONNECTING ||
        state?.connectionStatus == NearbyConnectionStatus.CONNECTED
    ) Icons.Rounded.Wifi else Icons.Rounded.WifiOff
    Tag(label, tone, icon, modifier)
}

@Composable
fun BloodBadge(group: String, size: Dp = 48.dp, tint: Color = CoralSoft, fg: Color = Coral) {
    Box(Modifier.size(size).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        Text(group, color = fg, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
    }
}

@Composable
fun Avatar(label: String, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        Text(label.take(2).uppercase(), color = Teal, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Paper, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(0.9f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1.4f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun HairLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

@Composable
fun Notice(text: String, icon: ImageVector = Icons.Rounded.Info, tone: Tone = Tone.Warn, modifier: Modifier = Modifier) {
    val (bg, fg) = if (tone == Tone.Warn) Pair(AmberSoft, Amber) else Pair(MintSoft, Teal)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

@Composable
fun RequestRow(r: BloodRequest, onClick: () -> Unit) {
    SmCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BloodBadge(r.bloodGroup)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${r.units} ${if (r.units == 1) "unit" else "units"} of ${r.bloodGroup}", style = MaterialTheme.typography.titleMedium)
                Text(r.createdAt, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Tag(r.status.label, r.status.tone(), r.status.icon())
        }
    }
}

// ───────────────────────── Inputs ─────────────────────────

@Composable
fun SmField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Teal, unfocusedBorderColor = Line,
            focusedContainerColor = Paper, unfocusedContainerColor = Paper,
            focusedLabelColor = Teal, cursorColor = Teal,
            errorBorderColor = Coral, errorLabelColor = Coral, errorSupportingTextColor = Coral
        )
    )
}

@Composable
fun BloodGroupPicker(selected: String, onSelect: (String) -> Unit, error: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BloodGroups.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { g ->
                    val on = g == selected
                    Surface(
                        onClick = { onSelect(g) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) Teal else Paper,
                        border = BorderStroke(1.dp, if (on) Teal else if (error) Coral else Line)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(g, style = MaterialTheme.typography.titleMedium, color = if (on) Color.White else Ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fog).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Surface(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (on) Paper else Color.Transparent,
                border = if (on) BorderStroke(1.dp, Line) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (on) Ink else Muted)
                }
            }
        }
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 6) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(Icons.Rounded.Remove, { if (value > min) onChange(value - 1) }, "Fewer units")
        Text(
            value.toString(), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconBubble(Icons.Rounded.Add, { if (value < max) onChange(value + 1) }, "More units")
    }
}

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, error: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Teal, uncheckedColor = if (error) Coral else Muted)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun SmSwitch(checked: Boolean, onChange: (Boolean) -> Unit, onDark: Boolean = false) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Teal, checkedTrackColor = Mint, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Muted, uncheckedTrackColor = if (onDark) Color.White else Line, uncheckedBorderColor = Color.Transparent
        )
    )
}

// ───────────────────────── Brand & motion ─────────────────────────

@Composable
fun Logo(size: Dp = 72.dp, onDark: Boolean = false) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(if (onDark) Color.White.copy(alpha = 0.12f) else Teal),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.6f)) {
            val w = this.size.width
            val a = Offset(w * 0.2f, w * 0.78f)
            val b = Offset(w * 0.8f, w * 0.78f)
            val c = Offset(w * 0.5f, w * 0.2f)
            val stroke = w * 0.06f
            drawLine(Mint, a, b, stroke)
            drawLine(Mint, a, c, stroke)
            drawLine(Mint, b, c, stroke)
            drawCircle(Mint, w * 0.11f, a)
            drawCircle(Mint, w * 0.11f, b)
            drawCircle(Coral, w * 0.15f, c)
        }
    }
}

/** Expanding rings behind [content]; used for discovery and voice listening. */
@Composable
fun Pulse(modifier: Modifier = Modifier, color: Color = Teal, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0..2) {
                val f = (p + i / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - f) * 0.22f), radius = this.size.minDimension / 2f * (0.34f + 0.66f * f))
            }
        }
        content()
    }
}

// ───────────────────────── Tab bar ─────────────────────────

@Composable
fun TabBar(current: String, nav: NavController) {
    val items = listOf(
        Triple("home", "Home", Icons.Rounded.Home),
        Triple("find_blood", "Find blood", Icons.Rounded.Search),
        Triple("donate", "Donate", Icons.Rounded.Favorite),
        Triple("activity", "My activity", Icons.Rounded.History),
        Triple("profile", "Profile", Icons.Rounded.Person)
    )
    Surface(color = Paper, border = BorderStroke(1.dp, Line)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { (route, label, icon) ->
                val on = route == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .clickable { if (!on) nav.navigate(route) { popUpTo("home"); launchSingleTop = true } }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) Mint else Color.Transparent).padding(horizontal = 12.dp, vertical = 4.dp)
                    ) { Icon(icon, label, Modifier.size(20.dp), tint = if (on) Teal else Muted) }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Teal else Muted, maxLines = 1)
                }
            }
        }
    }
}
