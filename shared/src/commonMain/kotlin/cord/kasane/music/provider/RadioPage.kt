package cord.kasane.music.provider

import cord.kasane.music.model.Track

data class RadioPage(val tracks: List<Track>, val continuation: String?)
