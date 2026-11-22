package cord.kasane.music.ui.components

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

/**
 * Devices sheet: local device, discovered/paired peers, manual address entry,
 * and transfer/control actions. Remote playback state is only a projection;
 * commands go through [remote].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    remote: RemoteController,
    localName: String,
    onDismiss: () -> Unit,
) {
    val devices by remote.manager.devices.collectAsState()
    val target by remote.target.collectAsState()
    var manualAddr by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            ListItem(
                headlineContent = { Text(localName, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("This phone") },
                leadingContent = { Icon(Icons.Outlined.Smartphone, null) },
                trailingContent = {
                    if (target == null) Text("Controlling", color = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 0.dp,
            )
            HorizontalDivider()

            LazyColumn {
                items(devices, key = { it.id ?: it.addr ?: it.name }) { d ->
                    DeviceRow(d, isTarget = target != null && target == d.id,
                        onControl = { if (target == d.id) remote.control(null) else d.id?.let(remote::control) },
                        onTransferHere = { d.id?.let { remote.playHere(it) } },
                        onTransferThere = { d.id?.let { remote.transferTo(it) } },
                        onPair = { remote.pair(d.id ?: d.addr ?: return@DeviceRow) },
                        onRemove = { d.id?.let(remote::unpair) })
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = manualAddr, onValueChange = { manualAddr = it },
                    label = { Text("Add by address") },
                    placeholder = { Text("10.0.0.5:47650") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { remote.pair(manualAddr.trim()) },
                    enabled = manualAddr.isNotBlank()) { Text("Pair") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceRow(
    d: ConnectDeviceView, isTarget: Boolean,
    onControl: () -> Unit, onTransferHere: () -> Unit, onTransferThere: () -> Unit,
    onPair: () -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ListItem(
            headlineContent = { Text(d.name, fontWeight = if (isTarget) FontWeight.SemiBold else FontWeight.Normal) },
            supportingContent = {
                val status = if (d.online) d.connection else "offline"
                Text(listOfNotNull(d.platform, status, d.lastError).joinToString(" · "))
            },
            leadingContent = {
                Icon(
                    if (d.online) Icons.Outlined.Link else Icons.Outlined.Circle, null,
                    tint = if (d.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingContent = {
                if (isTarget) Text("Controlling", color = MaterialTheme.colorScheme.primary)
            },
            tonalElevation = 0.dp,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!d.trusted) {
                TextButton(onClick = onPair) { Text("Pair") }
            }
            if (d.trusted) {
                TextButton(onClick = onControl) { Text(if (isTarget) "This phone" else "Control") }
                TextButton(onClick = onTransferHere) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Text("Play here")
                }
                TextButton(onClick = onTransferThere) {
                    Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(16.dp)); Text("To device")
                }
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

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
