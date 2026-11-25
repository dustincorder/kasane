package cord.kasane.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
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
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Surface(
                color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onEnabledChange(!enabled) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Use Kasane Connect",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Control and move playback on the same network · This device: ${actions.localName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }
        }
        if (enabled) {
            item { Header("Paired devices") }
            if (paired.isEmpty()) item { Hint("None yet. Each pair of devices is paired once with a verification code.") }
            items(paired, key = { "p:" + it.id }) { d ->
                DeviceRow(d, modifier = Modifier.padding(horizontal = 4.dp), trailing = { TextButton(onClick = { forgetting = d }) { Text("Forget") } })
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { Header("Available on this network") }
            if (nearby.isEmpty()) item { Hint("Open Kasane on the other device and keep it on the same network.") }
            items(nearby, key = { "n:" + (it.id ?: it.addr ?: it.name) }) { d ->
                DeviceRow(d, modifier = Modifier.padding(horizontal = 4.dp), trailing = {
                    FilledTonalButton(onClick = { actions.pair(d.id ?: d.addr ?: return@FilledTonalButton) },
                        enabled = d.state == DeviceState.AVAILABLE) { Text(if (d.state == DeviceState.PAIRING) "Pairing…" else "Pair") }
                })
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { Header("Pair manually") }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter another device's IP address if multicast discovery is blocked on your network.", style = MaterialTheme.typography.bodySmallMuted)
                    Row(
                        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            placeholder = { Text("10.0.0.5") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = { actions.pair(manual.trim()); manual = "" },
                            enabled = manual.isNotBlank(),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Pair") }
                    }
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

@Composable private fun Header(text: String) = Text(text, style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

