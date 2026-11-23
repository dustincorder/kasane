package cord.kasane.music.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.screens.QueueUiState

/** The queue screen is common code (:ui); Android projects its Media3 state into it and hosts it in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlaybackUiState,
    onPlayIndex: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        QueueContent(state, onPlayIndex, onMove, onRemove)
    }
}

@Composable
fun QueueContent(state: PlaybackUiState, onPlayIndex: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit) {
    cord.kasane.ui.screens.QueueContent(
        QueueUiState(state.queue, state.queueIndex, state.upcomingOrder, state.isPlaying, state.isLoading, state.shuffleEnabled),
        onPlayIndex, onMove, onRemove, Modifier.heightIn(max = 640.dp).navigationBarsPadding())
}

@Preview(name = "412", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun QueuePreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            val tracks = listOf("Intro", "Midnight City Lights", "Subway Melancholy with a very long title that has to truncate", "Synthetic Dawn", "Neon Drift", "Echoes").mapIndexed { i, t -> Track("track$i", t, "Artist $i", durationSeconds = 180.0 + i * 7) }
            QueueContent(PlaybackUiState(currentTrack = tracks[2], queue = tracks, queueIndex = 2, isPlaying = true, upcomingOrder = listOf(3, 4, 5)), {}, { _, _ -> }, {})
        }
    }
}
