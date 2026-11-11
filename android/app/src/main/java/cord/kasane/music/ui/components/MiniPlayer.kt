package cord.kasane.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.theme.*

@Composable
fun MiniPlayer(
    uiState: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack

    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (track == null) return@AnimatedVisibility

        Surface(
            color = KasaneWineSubtle,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                val progress = if (uiState.durationMs > 0) {
                    (uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = AccentRose,
                    trackColor = Gray850
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Artwork
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Gray850),
                        contentAlignment = Alignment.Center
                    ) {
                        TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize())
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title & Artist
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = track.title,
                            style = KasaneTypography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = track.artist,
                            style = KasaneTypography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Transport controls (min 48x48dp touch targets)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(KasaneCrimson),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    color = White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                    tint = White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onNext,
                        enabled = uiState.canSkipNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = if (uiState.canSkipNext) Gray200 else Gray700,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "MiniPlayer Playing", showBackground = true, backgroundColor = 0xFF0C0D10)
@Composable
fun MiniPlayerPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        MiniPlayer(
            uiState = PlaybackUiState(
                currentTrack = Track(
                    id = "preview-1",
                    title = "Nice Boys",
                    artist = "TEMPOREX",
                    album = "Care",
                    durationSeconds = 181.0
                ),
                isPlaying = true,
                isLoading = false,
                currentPositionMs = 64000,
                durationMs = 181000
            ),
            onTogglePlay = {},
            onNext = {},
            onPrev = {},
            onOpenNowPlaying = {}
        )
    }
}
