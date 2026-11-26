package cord.kasane.desktop

import cord.kasane.desktop.ui.runUi

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--verifier") exitDesktop(cord.kasane.desktop.verify.runVerifierHelper())
    if (args.firstOrNull() == "--login") exitDesktop(cord.kasane.desktop.login.runLoginHelper())
    if (args.firstOrNull() == "--connect-smoke") exitDesktop(runConnectSmoke())
    if (args.firstOrNull() == "--render") exitDesktop(runRender(java.io.File(args.getOrElse(1) { "/tmp/kasane-render" }), args.getOrNull(2)?.toDoubleOrNull() ?: 1.0))
    if (args.firstOrNull() == "--live") exitDesktop(runLive(args.drop(1)))
    if (args.firstOrNull() == "--play") exitDesktop(runPlay(args.drop(1)))
    if (args.firstOrNull() == "--idle") exitDesktop(runIdle(args.getOrElse(1) { "60" }.toIntOrNull() ?: 60))
    if (args.firstOrNull() == "--mpris") exitDesktop(runMpris(args.getOrElse(1) { "60" }.toIntOrNull() ?: 60, args.getOrNull(2)))
    if (args.firstOrNull() == "--smoke") exitDesktop(runSmoke(args.drop(1).joinToString(" ").ifBlank { "Daft Punk Around the World" }))
    runUi()
    exitDesktop(0)
}
