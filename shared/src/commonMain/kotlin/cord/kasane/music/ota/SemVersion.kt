package cord.kasane.music.ota

/**
 * Semver-like version ordering for release tags: `v0.2.1-alpha` > `0.2.0-alpha`,
 * a release outranks its own pre-releases (`1.0` > `1.0-rc.1`), numeric pre-release
 * tokens compare numerically and rank below alphanumeric ones (semver §11).
 */
class SemVersion(val core: IntArray, val prerelease: List<String>) : Comparable<SemVersion> {

    override fun compareTo(other: SemVersion): Int {
        for (i in 0 until maxOf(core.size, other.core.size)) {
            val a = core.getOrElse(i) { 0 }; val b = other.core.getOrElse(i) { 0 }
            if (a != b) return if (a < b) -1 else 1
        }
        if (prerelease.isEmpty() != other.prerelease.isEmpty())
            return if (prerelease.isEmpty()) 1 else -1
        for (i in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease.getOrNull(i) ?: return -1
            val b = other.prerelease.getOrNull(i) ?: return 1
            if (a == b) continue
            val na = a.toLongOrNull(); val nb = b.toLongOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String =
        core.joinToString(".") + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    override fun equals(other: Any?): Boolean = other is SemVersion && compareTo(other) == 0
    override fun hashCode(): Int = toString().hashCode()

    companion object {
        /** Accepts `v`-prefixed tags, missing minor/patch (`1` → `1.0.0`), and `+build` metadata. */
        fun parse(raw: String): SemVersion? {
            var s = raw.trim().removePrefix("v").removePrefix("V")
            s = s.substringBefore('+')
            val parts = s.split('-', limit = 2)
            val nums = parts[0].split('.')
            if (nums.isEmpty() || nums.size > 4 || nums.any { it.isEmpty() }) return null
            val core = nums.map { it.toIntOrNull() ?: return null }.toIntArray()
            val pre = parts.getOrNull(1)?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() }) return null
            return SemVersion(core, pre)
        }
    }
}
