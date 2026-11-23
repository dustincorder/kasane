package cord.kasane.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cord.kasane.music.connect.ConnectDeviceView
import cord.kasane.music.connect.ConnectPairingPrompt
import cord.kasane.music.connect.RemoteController

private fun deviceIcon(platform: String?) =
    if (platform == "android") Icons.Outlined.Smartphone else Icons.Outlined.Computer

/**
 * Spotify-Connect-style sheet: the device that is playing at the top (track and
 * volume), then every other online paired device. One tap moves playback there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(
    remote: RemoteController,
    localName: String,
    localTrack: String?,
    onAddDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val devices by remote.manager.devices.collectAsState()
    val states by remote.manager.remoteStates.collectAsState()
    val current by remote.target.collectAsState()
    val online = devices.filter { it.trusted && it.online && it.id != null && it.id in states }
    val currentDevice = current?.let { id -> online.firstOrNull { it.id == id } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("Connect", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val frame = current?.let(states::get)
            CurrentDeviceCard(
                name = currentDevice?.name ?: localName,
                local = currentDevice == null,
                platform = currentDevice?.platform ?: "android",
                nowPlaying = if (currentDevice == null) localTrack
                    else frame?.track?.let { t -> listOf(t.title, t.artist).filter(String::isNotBlank).joinToString(" — ") },
                remoteVolume = frame?.volume,
                onRemoteVolume = remote::setVolume,
            )
            Spacer(Modifier.height(8.dp))
            if (currentDevice != null) {
                DeviceListRow(localName, "This phone", "android", onClick = { remote.transferTo(null); onDismiss() })
            }
            online.filter { it.id != current }.forEach { d ->
                DeviceListRow(d.name, states[d.id]?.track?.title, d.platform,
                    onClick = { remote.transferTo(d.id); onDismiss() },
                    onDisconnect = { d.id?.let(remote::disconnect) },
                    onForget = { d.id?.let(remote::unpair) })
            }
            if (online.isEmpty()) {
                Text("No other devices are online. Open Kasane on another paired device on this network.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            TextButton(onClick = { onDismiss(); onAddDevice() }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add a device")
            }
        }
    }
}

@Composable
private fun CurrentDeviceCard(
    name: String, local: Boolean, platform: String?, nowPlaying: String?,
    remoteVolume: Float?, onRemoteVolume: (Float) -> Unit,
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val max = remember { audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var localVolume by remember { mutableFloatStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragged by remember(remoteVolume) { mutableStateOf<Float?>(null) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(nowPlaying ?: if (local) "This phone" else "Ready",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                Icon(deviceIcon(platform), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            val volume = if (local) localVolume else dragged ?: remoteVolume
            if (volume != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeDown, null, Modifier.size(20.dp))
                    Slider(value = volume, onValueChange = {
                        if (local) {
                            localVolume = it
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
                        } else dragged = it
                    }, onValueChangeFinished = { if (!local) dragged?.let(onRemoteVolume) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceListRow(
    name: String, subtitle: String?, platform: String?, onClick: () -> Unit,
    onDisconnect: (() -> Unit)? = null, onForget: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1) } },
        leadingContent = { Icon(deviceIcon(platform), null) },
        trailingContent = if (onDisconnect != null || onForget != null) { {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Device options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onDisconnect?.let { DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; it() }) }
                    onForget?.let { DropdownMenuItem(text = { Text("Forget device") }, onClick = { menu = false; confirmForget = true }) }
                }
            }
        } } else null,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (confirmForget && onForget != null) {
        AlertDialog(onDismissRequest = { confirmForget = false },
            title = { Text("Forget $name?") },
            text = { Text("It will have to be paired again with a code before it can control or play.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } })
    }
}

/**
 * Settings → Kasane Connect: on/off, paired devices (online or not) with
 * Forget, devices nearby that can be paired, and pairing by address.
 */
@Composable
fun ConnectDevicesContent(
    remote: RemoteController?,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    localName: String,
    modifier: Modifier = Modifier,
) {
    val devices by remote?.manager?.devices?.collectAsState() ?: remember { mutableStateOf(emptyList<ConnectDeviceView>()) }
    var manualAddr by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            ListItem(
                headlineContent = { Text("Kasane Connect") },
                supportingContent = { Text("Control and move playback between your devices on the same network. This device: $localName") },
                trailingContent = { Switch(checked = enabled, onCheckedChange = onEnabledChange) },
            )
        }
        if (remote != null && enabled) {
            val paired = devices.filter { it.trusted && it.id != null }
            val nearby = devices.filter { !it.trusted && it.online }
            item { SectionLabel("Paired devices") }
            if (paired.isEmpty()) item { HintText("No paired devices yet. Pair one below; each pair of devices is paired once.") }
            items(paired, key = { "p:" + it.id }) { d ->
                DeviceListRow(d.name, if (d.online) "Online" else "Offline", d.platform, onClick = {},
                    onForget = { d.id?.let(remote::unpair) })
            }
            item { SectionLabel("Available on this network") }
            if (nearby.isEmpty()) item { HintText("Open Kasane on the other device and keep it on the same Wi-Fi.") }
            items(nearby, key = { "n:" + (it.id ?: it.addr ?: it.name) }) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = d.lastError?.let { { Text(it) } },
                    leadingContent = { Icon(deviceIcon(d.platform), null) },
                    trailingContent = { FilledTonalButton(onClick = { remote.pair(d.id ?: d.addr ?: return@FilledTonalButton) }) { Text("Pair") } },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(value = manualAddr, onValueChange = { manualAddr = it },
                        label = { Text("Pair by address") }, placeholder = { Text("10.0.0.5") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { remote.pair(manualAddr.trim()); manualAddr = "" },
                        enabled = manualAddr.isNotBlank()) { Text("Pair") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDevicesScreen(
    remote: RemoteController?, enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    localName: String, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Kasane Connect") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        ConnectDevicesContent(remote, enabled, onEnabledChange, localName, Modifier.weight(1f))
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun HintText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

/** Numeric-comparison dialog for both incoming and outgoing pairing prompts. */
@Composable
fun PairingDialog(prompt: ConnectPairingPrompt, onRespond: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        icon = { Icon(Icons.Outlined.Devices, null) },
        title = { Text(if (prompt.incoming) "Pair with ${prompt.peerName}?" else "Confirm on ${prompt.peerName}") },
        text = {
            Column {
                Text("Check that this number matches on the other device.")
                Spacer(Modifier.height(12.dp))
                Text(prompt.code, style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button(onClick = { onRespond(true) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("Reject") } },
        shape = RoundedCornerShape(24.dp),
    )
}
