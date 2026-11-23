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
import cord.kasane.ui.components.deviceIcon
import cord.kasane.ui.screens.ConnectDevicesActions
import cord.kasane.ui.screens.ConnectDevicesContent
import cord.kasane.music.connect.ConnectDeviceView


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
                    Text("${(volume * 100).toInt()} %", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).width(44.dp))
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

/** Settings → Kasane Connect: the shared screen, wired to this computer's ConnectManager. */
@Composable
fun ConnectSettings(app: DesktopApp) {
    val connect = app.connect
    val prefs by app.settings.prefs.collectAsState()
    if (connect == null) {
        Text("Kasane Connect could not start: ${app.connectError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val devices by connect.manager.devices.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val actions = remember(connect) {
        object : ConnectDevicesActions {
            override val localName = connect.identity.deviceName
            override fun pair(target: String) { error = null; app.scope.launchCatching({ error = it }) { connect.manager.pair(target) } }
            override fun forget(deviceId: String) { app.scope.launchCatching { connect.manager.unpair(deviceId) } }
        }
    }
    Column(Modifier.widthIn(max = 640.dp)) {
        // The list scrolls with the settings page, so it gets a fixed viewport instead of an unbounded height.
        ConnectDevicesContent(devices, prefs.connectEnabled, { app.setConnectEnabled(it) }, actions, Modifier.height(480.dp))
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(onError: (String?) -> Unit = {}, block: suspend () -> Unit) =
    launch { runCatching { block() }.onFailure { onError(it.message ?: "Failed.") } }

/** Pairing over any screen: the receiver confirms once, the initiator only shows the code. */
@Composable
fun PairingPrompts(app: DesktopApp) {
    val connect = app.connect ?: return
    val prompts by connect.manager.prompts.collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    cord.kasane.ui.components.PairingDialog(prompt,
        onConfirm = { connect.manager.confirmPairing(prompt.promptId, true) },
        onReject = { connect.manager.confirmPairing(prompt.promptId, false) })
}
