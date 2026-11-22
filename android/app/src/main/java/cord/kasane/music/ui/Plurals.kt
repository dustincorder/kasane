package cord.kasane.music.ui

/** "1 song", "2 songs": regular English plurals for counts shown in metadata. */
fun plural(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"
