package cord.kasane.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import cord.kasane.music.connect.ConnectDeviceView
import cord.kasane.music.connect.DeviceState
import cord.kasane.ui.components.DeviceRow
import cord.kasane.ui.theme.bodySmallMuted

/** What the Connect settings screen needs from a platform's ConnectManager. */
interface ConnectDevicesActions {
    val localName: String
    fun pair(target: String)
    fun forget(deviceId: String)
}

/**
 * Settings → Kasane Connect, identical on Android and desktop: on/off, paired devices (online or not, with their
 * real state) and devices nearby that can be paired. Discovery never lists this device or a stale record.
 */
@Composable
fun ConnectDevicesContent(
    devices: List<ConnectDeviceView>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    actions: ConnectDevicesActions,
    modifier: Modifier = Modifier,
) {
    var manual by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<ConnectDeviceView?>(null) }
    val paired = devices.filter { it.trusted && it.id != null }
    val nearby = devices.filter { !it.trusted && it.online }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Kasane Connect", style = MaterialTheme.typography.titleMedium)
                    Text("Control and move playback between your devices on the same network. This device: ${actions.localName}",
                        style = MaterialTheme.typography.bodySmallMuted)
                }
                Switch(enabled, onEnabledChange)
            }
        }
        if (enabled) {
            item { Header("Paired devices") }
            if (paired.isEmpty()) item { Hint("None yet. Each pair of devices is paired once; a code appears on both screens and is confirmed on the receiving device.") }
            items(paired, key = { "p:" + it.id }) { d ->
                DeviceRow(d, trailing = { TextButton(onClick = { forgetting = d }) { Text("Forget") } })
            }
            item { Header("Available on this network") }
            if (nearby.isEmpty()) item { Hint("Open Kasane on the other device and keep it on the same network.") }
            items(nearby, key = { "n:" + (it.id ?: it.addr ?: it.name) }) { d ->
                DeviceRow(d, trailing = {
                    FilledTonalButton(onClick = { actions.pair(d.id ?: d.addr ?: return@FilledTonalButton) },
                        enabled = d.state == DeviceState.AVAILABLE) { Text(if (d.state == DeviceState.PAIRING) "Pairing…" else "Pair") }
                })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manual, { manual = it }, label = { Text("Pair by address") }, placeholder = { Text("10.0.0.5") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { actions.pair(manual.trim()); manual = "" }, enabled = manual.isNotBlank()) { Text("Pair") }
                }
            }
        }
    }
    forgetting?.let { d ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget ${d.name}?") },
            text = { Text("It will have to be paired again with a code before it can control or play.") },
            confirmButton = { TextButton(onClick = { forgetting = null; d.id?.let(actions::forget) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } })
    }
}

@Composable private fun Header(text: String) = Text(text, style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

