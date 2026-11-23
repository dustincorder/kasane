package cord.kasane.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cord.kasane.music.connect.ConnectDeviceView
import cord.kasane.music.connect.ConnectPairingPrompt
import cord.kasane.music.connect.DeviceState

/** The icon is chosen by what the device announces about itself; an unknown device never looks like a desktop. */
fun deviceIcon(platform: String?): ImageVector = when (platform) {
    "android" -> Icons.Outlined.Smartphone
    "tablet" -> Icons.Outlined.TabletAndroid
    "linux", "windows", "macos" -> Icons.Outlined.Computer
    else -> Icons.Outlined.Devices
}

fun deviceStateLabel(device: ConnectDeviceView): String = when (device.state) {
    DeviceState.AVAILABLE -> "Available to pair"
    DeviceState.PAIRING -> "Pairing…"
    DeviceState.PAIRED -> "Paired · available"
    DeviceState.CONNECTED -> "Connected"
    DeviceState.PLAYING -> "Playing"
    DeviceState.PAUSED -> "Connected · not playing"
    DeviceState.OFFLINE -> "Offline"
}

/**
 * Pairing, as both devices show it. The receiving device asks once ("Pair with X?", the code, Confirm/Reject);
 * the initiating device only displays the same code and waits ("Confirm on X", Cancel).
 */
@Composable
fun PairingDialog(prompt: ConnectPairingPrompt, onConfirm: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Outlined.Devices, null) },
        title = { Text(if (prompt.incoming) "Pair with ${prompt.peerName}?" else "Confirm on ${prompt.peerName}") },
        text = {
            Column {
                Text(if (prompt.incoming) "Check that this code matches the one on ${prompt.peerName}, then confirm."
                    else "Waiting for ${prompt.peerName}. Confirm there if this code matches.")
                Spacer(Modifier.height(12.dp))
                Text(prompt.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { if (prompt.incoming) Button(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onReject) { Text(if (prompt.incoming) "Reject" else "Cancel") } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** One device in a list: icon, name and an honest state line. [trailing] holds the actions. */
@Composable
fun DeviceRow(device: ConnectDeviceView, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(deviceIcon(device.platform), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(device.lastError ?: deviceStateLabel(device), style = MaterialTheme.typography.bodySmall,
                color = if (device.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
