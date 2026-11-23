package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.connect.DesktopRemote
import cord.kasane.music.connect.ConnectDeviceView

private fun deviceIcon(platform: String?) = if (platform == "android") Icons.Outlined.Smartphone else Icons.Outlined.Computer

/** Player-bar device button; highlighted while another device plays. Opens the Connect popover. */
@Composable
fun DeviceButton(app: DesktopApp, onAddDevice: () -> Unit) {
    val connect = app.connect ?: return
    val remote = connect.remote
    val target by remote.target.collectAsState()
    val devices by connect.manager.devices.collectAsState()
    var open by remember { mutableStateOf(false) }
    val name = target?.let { id -> devices.firstOrNull { it.id == id }?.name }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Devices, if (name != null) "Playing on $name" else "Connect to a device",
                tint = if (name != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConnectPanel(app, remote, onAddDevice = { open = false; onAddDevice() }, onPicked = { open = false })
        }
    }
}

/** Spotify-Connect-style list: the playing device with volume, then every other online paired device. */
@Composable
private fun ConnectPanel(app: DesktopApp, remote: DesktopRemote, onAddDevice: () -> Unit, onPicked: () -> Unit) {
    val devices by remote.manager.devices.collectAsState()
    val states by remote.manager.remoteStates.collectAsState()
    val target by remote.target.collectAsState()
    val busy by remote.busy.collectAsState()
    val error by remote.error.collectAsState()
    val local by app.player.state.collectAsState()
    val online = devices.filter { it.trusted && it.id != null && it.id in states }
    val current = target?.let { id -> online.firstOrNull { it.id == id } }
    Column(Modifier.width(340.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current?.name ?: "${remote.manager.localDeviceName} (this computer)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val line = if (current != null) states[current.id]?.track?.let { "${it.title} — ${it.artist}" } else local.current?.let { "${it.title} — ${it.artist}" }
                        Text(line ?: "Ready", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(deviceIcon(current?.platform ?: "linux"), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                var dragged by remember(target) { mutableStateOf<Float?>(null) }
                val volume = dragged ?: if (current != null) states[current.id]?.volume ?: 1f else local.volume
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeDown, null, Modifier.size(18.dp))
                    Slider(volume, { dragged = it; if (current == null) app.player.setVolume(it) },
                        onValueChangeFinished = { if (current != null) dragged?.let(remote::setVolume); dragged = null }, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (current != null) DeviceRow("${remote.manager.localDeviceName} (this computer)", null, "linux", busy == "") { remote.transferTo(null); onPicked() }
        online.filter { it.id != target }.forEach { d ->
            DeviceRow(d.name, states[d.id]?.track?.title, d.platform, busy == d.id,
                onDisconnect = { d.id?.let(remote.manager::disconnect) },
                onForget = { app.scope.launchCatching { d.id?.let { remote.manager.unpair(it) } } }) { remote.transferTo(d.id); onPicked() }
        }
        if (online.isEmpty()) Text("No other devices are online. Open Kasane on a paired phone, tablet or computer on this network.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onAddDevice) { Text("Add a device…") }
    }
}

@Composable
private fun DeviceRow(name: String, subtitle: String?, platform: String?, busy: Boolean,
    onDisconnect: (() -> Unit)? = null, onForget: (() -> Unit)? = null, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !busy, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(deviceIcon(platform), null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            val line = if (busy) "Moving playback…" else subtitle
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDisconnect != null || onForget != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Device options") }
            DropdownMenu(menu, { menu = false }) {
                onDisconnect?.let { DropdownMenuItem({ Text("Disconnect") }, { menu = false; it() }) }
                onForget?.let { DropdownMenuItem({ Text("Forget device") }, { menu = false; it() }) }
            }
        }
    }
}

/** Settings → Kasane Connect: on/off, paired devices, devices to pair, pairing by address. */
@Composable
fun ConnectSettings(app: DesktopApp) {
    val connect = app.connect
    val prefs by app.settings.prefs.collectAsState()
    if (connect == null) {
        Text("Kasane Connect could not start: ${app.connectError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val devices by connect.manager.devices.collectAsState()
    var address by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Control and move playback between your devices on the same network", style = MaterialTheme.typography.titleSmall)
                Text("This device: ${connect.identity.deviceName} · port ${connect.manager.port}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(prefs.connectEnabled, { app.setConnectEnabled(it) })
        }
        if (!prefs.connectEnabled) return@Column
        val paired = devices.filter { it.trusted && it.id != null }
        val nearby = devices.filter { !it.trusted && it.online }
        Text("Paired devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (paired.isEmpty()) Text("None yet. Each pair of devices is paired once, with a code shown on both screens.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        paired.forEach { d -> PairedRow(d) { app.scope.launchCatching { d.id?.let { connect.manager.unpair(it) } } } }
        Text("Available on this network", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (nearby.isEmpty()) Text("Open Kasane on the other device and keep it on the same network.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        nearby.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(deviceIcon(d.platform), null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium)
                    d.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                FilledTonalButton(onClick = { pair(app, d.id ?: d.addr ?: return@FilledTonalButton) { pairError = it } }) { Text("Pair") }
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(address, { address = it }, label = { Text("Pair by address") }, placeholder = { Text("10.0.0.5") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { pair(app, address.trim()) { pairError = it }; address = "" }, enabled = address.isNotBlank()) { Text("Pair") }
        }
        pairError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun PairedRow(d: ConnectDeviceView, onForget: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(deviceIcon(d.platform), null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium)
            Text(if (d.online) "Online" else "Offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { confirm = true }) { Text("Forget") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Forget ${d.name}?") },
        text = { Text("It will have to be paired again with a code before it can control or play.") },
        confirmButton = { TextButton(onClick = { confirm = false; onForget() }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

private fun pair(app: DesktopApp, target: String, onError: (String?) -> Unit) {
    val connect = app.connect ?: return
    onError(null)
    app.scope.launchCatching(onError) { connect.manager.pair(target) }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(onError: (String?) -> Unit = {}, block: suspend () -> Unit) =
    launch { runCatching { block() }.onFailure { onError(it.message ?: "Failed.") } }

/** Numeric comparison for incoming and outgoing pairing, shown over any screen. */
@Composable
fun PairingPrompts(app: DesktopApp) {
    val connect = app.connect ?: return
    val prompts by connect.manager.prompts.collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    AlertDialog(onDismissRequest = {},
        icon = { Icon(Icons.Outlined.Devices, null) },
        title = { Text(if (prompt.incoming) "Pair with ${prompt.peerName}?" else "Confirm on ${prompt.peerName}") },
        text = {
            Column {
                Text("Check that this number matches on the other device.")
                Spacer(Modifier.height(12.dp))
                Text(prompt.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button(onClick = { connect.manager.confirmPairing(prompt.promptId, true) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { connect.manager.confirmPairing(prompt.promptId, false) }) { Text("Reject") } })
}

