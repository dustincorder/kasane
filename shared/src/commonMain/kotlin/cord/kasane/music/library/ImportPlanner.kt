package cord.kasane.music.library

enum class Resolution { MATCHED_BY_ID, MATCHED_BY_METADATA, NEEDS_REVIEW, UNRESOLVED, DUPLICATE }

data class PlannedTrack(val source: ImportedTrack, val resolution: Resolution, val match: MatchResult?)

data class PlannedPlaylist(val requestedName: String, val finalName: String, val renamed: Boolean, val tracks: List<PlannedTrack>)

data class ImportPlan(
    val format: String,
    val playlists: List<PlannedPlaylist>,
    val favorites: List<PlannedTrack>,
    val history: List<Pair<PlannedTrack, Long>>,
    val warnings: List<String>,
) {
    private fun all() = playlists.flatMap { it.tracks } + favorites + history.map { it.first }
    fun count(resolution: Resolution) = all().count { it.resolution == resolution }
    val importable: Int get() = all().count { it.resolution in setOf(Resolution.MATCHED_BY_ID, Resolution.MATCHED_BY_METADATA) }
    val unresolved: List<ImportedTrack> get() = all().filter { it.resolution == Resolution.UNRESOLVED }.map { it.source }
    val needsReview: List<PlannedTrack> get() = all().filter { it.resolution == Resolution.NEEDS_REVIEW }
}

/**
 * Turns parsed data into a reviewable plan. Planning is pure and never writes anything; applying a plan is a
 * separate, user-confirmed step. Existing data is never overwritten: clashing playlist names get a suffix and
 * tracks already present are reported as duplicates instead of being added again.
 */
object ImportPlanner {
    fun plan(
        bundle: ImportBundle,
        candidates: List<MatchCandidate>,
        existingPlaylistNames: Set<String> = emptySet(),
        existingFavoriteIds: Set<String> = emptySet(),
    ): ImportPlan {
        fun resolve(track: ImportedTrack): PlannedTrack {
            val match = TrackMatcher.best(track, candidates)
            return when {
                match == null -> PlannedTrack(track, if (track.videoId != null) Resolution.MATCHED_BY_ID else Resolution.UNRESOLVED,
                    track.videoId?.let { MatchResult(MatchCandidate(it, track.title, track.artist, track.durationSeconds ?: 0.0), 1.0, MatchKind.ID) })
                match.kind == MatchKind.ID -> PlannedTrack(track, Resolution.MATCHED_BY_ID, match)
                match.needsReview -> PlannedTrack(track, Resolution.NEEDS_REVIEW, match)
                else -> PlannedTrack(track, Resolution.MATCHED_BY_METADATA, match)
            }
        }
        fun dedupe(items: List<PlannedTrack>, seen: MutableSet<String> = mutableSetOf()) = items.map { p ->
            val id = p.match?.candidate?.videoId
            if (id != null && p.resolution != Resolution.NEEDS_REVIEW && !seen.add(id)) p.copy(resolution = Resolution.DUPLICATE) else p
        }
        val taken = existingPlaylistNames.map { it.lowercase() }.toMutableSet()
        val playlists = bundle.playlists.map { p ->
            var name = p.name.ifBlank { "Imported playlist" }; var n = 1
            val requested = name
            while (name.lowercase() in taken) name = "$requested (imported${if (++n > 2) " $n" else ""})"
            taken += name.lowercase()
            PlannedPlaylist(requested, name, name != requested, dedupe(p.tracks.map(::resolve)))
        }
        val favorites = dedupe(bundle.favorites.map(::resolve), existingFavoriteIds.toMutableSet())
        val history = bundle.history.map { resolve(it.track) to it.playedAtMillis }
        return ImportPlan(bundle.format, playlists, favorites, history, bundle.warnings)
    }

    /** Human-readable result report shown after an import and kept with the user's records. */
    fun report(plan: ImportPlan): String = buildString {
        appendLine("Import report (${plan.format})")
        appendLine("Imported: ${plan.importable}")
        appendLine("Duplicates skipped: ${plan.count(Resolution.DUPLICATE)}")
        appendLine("Needs review: ${plan.count(Resolution.NEEDS_REVIEW)}")
        appendLine("Unresolved: ${plan.count(Resolution.UNRESOLVED)}")
        plan.playlists.filter { it.renamed }.forEach { appendLine("Playlist \"${it.requestedName}\" already exists; imported as \"${it.finalName}\".") }
        plan.unresolved.forEach { appendLine("  - ${listOf(it.artist, it.title).filter(String::isNotBlank).joinToString(" - ")}") }
        plan.warnings.forEach { appendLine("Note: $it") }
    }
}
