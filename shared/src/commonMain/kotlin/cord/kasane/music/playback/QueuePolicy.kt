package cord.kasane.music.playback

import cord.kasane.music.model.Track

/** Input validation only. The active playback backend owns the authoritative timeline. */
object QueuePolicy {
    const val LIMIT = 2000
    fun nextInsertionIndex(currentIndex: Int, existingIndex: Int?, count: Int): Int {
        val target = currentIndex + if (existingIndex != null && existingIndex < currentIndex) 0 else 1
        return target.coerceIn(0, if (existingIndex == null) count else (count - 1).coerceAtLeast(0))
    }
    fun shouldPrefetchRadio(nearEnd: Boolean, initial: Boolean, collection: Boolean, repeat: Boolean, autoplay: Boolean, forced: Boolean): Boolean =
        !repeat && (autoplay || forced) && (nearEnd || (initial && !collection))
    fun radioAppend(existingIds: Set<String>, candidates: List<Track>, capacity: Int): List<Track> =
        prepare(candidates).filter { it.id !in existingIds }.take(capacity.coerceAtLeast(0))
    /**
     * A radio's first entry is usually the seed itself, sometimes under another video id (audio vs music video).
     * The user's selected track stays the anchor, so any entry that is the seed or the same recording is dropped.
     */
    fun dropSeedAliases(tracks: List<Track>, seed: Track?): List<Track> {
        if (seed == null) return tracks
        fun norm(v: String) = v.lowercase().replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun artist(v: String) = norm(v.substringBefore('•').substringBefore(','))
        return tracks.filterNot { it.id == seed.id || (norm(it.title) == norm(seed.title) && artist(it.artist) == artist(seed.artist) && norm(seed.title).isNotEmpty()) }
    }
    fun prepare(tracks: List<Track>): List<Track> =
        tracks.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(LIMIT)

    // ---- Playback contexts -------------------------------------------------------------------------------

    /** What starting playback of one track from search, home or a list looks like. */
    data class ContextStart(val items: List<Track>, val startIndex: Int, val shuffle: Boolean)

    /**
     * Selecting a track outside the current queue starts a NEW context: the track is the only entry and the
     * anchor of a fresh radio. Nothing from the previous queue survives, and shuffle is turned off so the
     * anchor really is first and the radio keeps its curated order.
     */
    fun newContext(track: Track): ContextStart = ContextStart(listOf(track), 0, shuffle = false)

    /** Choosing the track that is already playing resumes it; only an explicit "Start radio" rebuilds around it. */
    fun startsNewContext(currentId: String?, selectedId: String, forcedRadio: Boolean): Boolean = forcedRadio || currentId != selectedId

    /** Playing a list (playlist, album, saved songs) keeps the list's own order; shuffle only starts at a random entry. */
    fun collectionStart(tracks: List<Track>, shuffle: Boolean, pick: (Int) -> Int = { 0 }): ContextStart? {
        val prepared = prepare(tracks)
        if (prepared.isEmpty()) return null
        return ContextStart(prepared, if (shuffle) pick(prepared.size).coerceIn(0, prepared.lastIndex) else 0, shuffle)
    }

    /**
     * A radio page may only be appended while the context that asked for it is still in the queue. Without this,
     * a slow response for the previous track lands in the queue of the track the user just started.
     */
    fun acceptRadioPage(launchedContext: String?, contextsInQueue: Set<String?>): Boolean =
        launchedContext != null && launchedContext in contextsInQueue

    // ---- Queue presentation and editing ------------------------------------------------------------------

    /** `upNext` follows the player's real order (including shuffle); `played` is everything else, oldest first. */
    data class QueueSections(val currentIndex: Int, val upNext: List<Int>, val played: List<Int>)

    fun sections(count: Int, currentIndex: Int, upcomingOrder: List<Int>): QueueSections {
        if (count <= 0 || currentIndex !in 0 until count) return QueueSections(-1, emptyList(), emptyList())
        val upcoming = upcomingOrder.filter { it in 0 until count && it != currentIndex }.distinct()
        val played = (0 until count).filter { it != currentIndex && it !in upcoming.toSet() }
        return QueueSections(currentIndex, upcoming, played)
    }

    /** A drag from [from] to [to], validated; null when it would change nothing or leave the queue. */
    fun validMove(count: Int, from: Int, to: Int): Pair<Int, Int>? =
        if (from == to || from !in 0 until count || to !in 0 until count) null else from to to

    /** Index of the same item after `moveMediaItem(from, to)`. */
    fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        from > to && index in to until from -> index + 1
        else -> index
    }

    /** Index of the current item after `removeMediaItem(removed)`; -1 when the current item itself was removed. */
    fun indexAfterRemove(current: Int, removed: Int): Int = when {
        removed == current -> -1
        removed < current -> current - 1
        else -> current
    }
}
