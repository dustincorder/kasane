package cord.kasane.desktop

import java.util.concurrent.TimeUnit

/**
 * The display scale the desktop session uses, for toolkits that cannot see it. The JVM reports 1.0 under
 * Wayland/XWayland on KDE even at 165 %, which is why the UI looked tiny. Sources, most exact first:
 * an explicit override, GDK/Qt scale variables, KDE's own output scale (`kscreen-doctor`), then Xft.dpi.
 * Returns 1.0 when nothing says otherwise. The result multiplies the window density only if the JVM did
 * not already apply the scale itself (see ScaledDensity).
 */
object SystemScale {
    fun detect(env: Map<String, String> = System.getenv(), run: (List<String>) -> String? = ::capture): Double {
        env["KASANE_SCALE"]?.toDoubleOrNull()?.takeIf { it in 0.5..4.0 }?.let { return it }
        listOf("GDK_SCALE", "QT_SCALE_FACTOR").firstNotNullOfOrNull { env[it]?.toDoubleOrNull()?.takeIf { v -> v in 0.5..4.0 } }?.let { return it }
        run(listOf("kscreen-doctor", "-o"))?.let { parseKScreen(it) }?.let { return it }
        run(listOf("xrdb", "-query"))?.let { parseXftDpi(it) }?.let { return it }
        return 1.0
    }

    /** The scale of the first enabled output, from `kscreen-doctor -o` (ANSI colours stripped). */
    fun parseKScreen(output: String): Double? {
        val text = output.replace(Regex("\u001B\\[[0-9;]*m"), "")
        val blocks = text.split(Regex("(?m)^Output:")).drop(1)
        for (block in blocks) {
            if (Regex("(?m)^\\s*disabled\\s*$").containsMatchIn(block)) continue
            Regex("Scale:\\s*([0-9.]+)").find(block)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.5..4.0 }?.let { return it }
        }
        return null
    }

    fun parseXftDpi(output: String): Double? =
        Regex("Xft\\.dpi:\\s*([0-9.]+)").find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.div(96.0)?.takeIf { it in 0.5..4.0 }

    private fun capture(command: List<String>): String? = runCatching {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else if (p.exitValue() == 0) out else null
    }.getOrNull()
}
